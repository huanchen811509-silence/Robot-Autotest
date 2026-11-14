package com.qa.autotest.application.editor;

import com.qa.autotest.application.caseasset.FileWorkspaceService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.editor.RobotSyntaxValidator.SyntaxReport;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.PasswordHasher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.RobotFileVersion;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 编辑器保存链路（EDT-09 / FR-v0.3-19 ~ 23）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>内容未变不建版本（sha256 相同直接返回当前版本，FR-v0.3-20）；</li>
 *   <li>并发保存用乐观锁，版本落后一律 409，不自动合并（DEV-PLAN §6）；</li>
 *   <li>保存前跑服务端语法校验，失败直接拒绝并带行号（FR-v0.3-23）。</li>
 * </ol>
 */
@Service
public class EditorSaveService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(EditorSaveService.class);

    private final RobotFileRepository files;
    private final AccessControlService access;
    private final AuditService audit;
    private final RobotSyntaxValidator syntax;

    /** W58-07 证据包通道（可选）：单测装配入口不注入时为 null，整段跳过。 */
    private com.qa.autotest.application.evidence.EvidenceBundleService evidenceBundles;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvidenceBundles(
            com.qa.autotest.application.evidence.EvidenceBundleService evidenceBundles) {
        this.evidenceBundles = evidenceBundles;
    }

    public EditorSaveService(RobotFileRepository files, AccessControlService access,
                             AuditService audit, RobotSyntaxValidator syntax) {
        this.files = files;
        this.access = access;
        this.audit = audit;
        this.syntax = syntax;
    }

    /** 保存请求：{@code path} 与 {@code fileId} 至少给一个。 */
    public record SaveCommand(long projectId, Long fileId, String path, String content,
                              String sha256, Integer baseVersion, Boolean parseOk,
                              String comment) {
    }

    /** 保存结果：前端据此更新「当前版本」与「未保存」标记。 */
    public record SaveResult(long fileId, String path, int version, String sha256,
                             boolean changed, boolean syntaxChecked) {
    }

    public SaveResult save(SaveCommand command) {
        access.bindProject(command.projectId());
        access.require(PermissionResource.CASE, PermissionAction.CREATE);
        String path = resolvePath(command);
        String content = command.content();
        if (content == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文件内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > FileWorkspaceService.MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "文件超过 " + (FileWorkspaceService.MAX_CONTENT_BYTES / 1024) + " KB 上限");
        }
        String sha256 = PasswordHasher.sha256Hex(content);
        // 客户端算的 sha256 只用来防传输/前端 bug：与内容不一致说明请求本身不可信
        if (command.sha256() != null && !command.sha256().isBlank()
                && !command.sha256().equalsIgnoreCase(sha256)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "sha256 与内容不一致");
        }
        SyntaxReport report = syntax.check(content);
        rejectIfInvalid(report);

        Long operator = TenantContext.userId();
        RobotFile existing = files.findByPath(command.projectId(), path).orElse(null);
        if (existing == null) {
            long id = files.insert(command.projectId(), path, content, sha256, operator);
            audit.record("file.create", "robot_file", String.valueOf(id), "path=" + path);
            recordSaveEvidence(id, path, content);
            return new SaveResult(id, path, 1, sha256, true, report.checked());
        }
        if (sha256.equalsIgnoreCase(existing.sha256())) {
            // 打开即保存：内容未变，不建版本
            return new SaveResult(existing.id(), path, existing.currentVersion(), sha256, false,
                    report.checked());
        }
        int expected = command.baseVersion() == null ? existing.currentVersion() : command.baseVersion();
        if (expected != existing.currentVersion()) {
            throw conflict(path, expected, existing.currentVersion());
        }
        boolean updated = files.updateContentIfVersion(command.projectId(), existing.id(), expected,
                content, sha256, commentOf(command.comment()), operator);
        if (!updated) {
            throw conflict(path, expected, existing.currentVersion());
        }
        audit.record("file.save", "robot_file", String.valueOf(existing.id()), "path=" + path);
        recordSaveEvidence(existing.id(), path, content);
        return new SaveResult(existing.id(), path, expected + 1, sha256, true, report.checked());
    }

    /**
     * 把这次保存的内容登记进证据包（W58-07，key=`editor-save:{fileId}`）。
     *
     * <p>「内容未变不建版本」那条分支**不写**证据：没产生新版本，就没有新事实可留，
     * 重复登记只会把指针换来换去。每次真保存都会原子替换同一 key 的指针，而历史版本仍在
     * `robot_file_version` 里一条不少 —— 证据包回答的是「最近一次保存的到底是什么字节」。
     *
     * <p>写失败只 warn：证据是旁路，不能因为它把用户的保存退回去。
     */
    private void recordSaveEvidence(long fileId, String path, String content) {
        if (evidenceBundles == null) {
            return;
        }
        Path dir = null;
        try {
            // 用原文件名建临时件，证据条目名才有意义（否则是一串随机临时名）
            dir = Files.createTempDirectory("editor-save-");
            Path temp = dir.resolve(Path.of(path).getFileName().toString());
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            evidenceBundles.write("editor-save:" + fileId, List.of(temp));
        } catch (IOException | RuntimeException ex) {
            LOG.warn("编辑器保存证据包写入失败 fileId={}：{}", fileId,
                    ex.getClass().getSimpleName());
        } finally {
            if (dir != null) {
                try {
                    Files.deleteIfExists(dir.resolve(Path.of(path).getFileName().toString()));
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响保存
                }
            }
        }
    }

    /**
     * 回滚到历史版本（FR-v0.3-22）：生成一个新版本，内容取旧版，历史一条不删。
     */
    public SaveResult rollback(long projectId, String rawPath, int targetVersion, String comment) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        String path = FileWorkspaceService.normalize(rawPath);
        RobotFile file = files.findByPath(projectId, path)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
        List<RobotFileVersion> history = files.versions(projectId, file.id());
        RobotFileVersion target = history.stream()
                .filter(item -> item.versionNo() == targetVersion)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "版本不存在：v" + targetVersion));
        boolean updated = files.updateContentIfVersion(projectId, file.id(), file.currentVersion(),
                target.content(), target.sha256(),
                comment == null || comment.isBlank() ? "回滚到 v" + targetVersion : comment,
                TenantContext.userId());
        if (!updated) {
            throw conflict(path, file.currentVersion(), file.currentVersion());
        }
        audit.record("file.rollback", "robot_file", String.valueOf(file.id()),
                "path=" + path + " to=v" + targetVersion);
        return new SaveResult(file.id(), path, file.currentVersion() + 1, target.sha256(), true, false);
    }

    /**
     * 两版 diff（FR-v0.3-21）：只读版本快照，不改任何东西。
     */
    public List<LineDiff.DiffLine> diff(long projectId, String rawPath, int fromVersion,
                                        int toVersion) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        String path = FileWorkspaceService.normalize(rawPath);
        RobotFile file = files.findByPath(projectId, path)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
        List<RobotFileVersion> history = files.versions(projectId, file.id());
        RobotFileVersion from = versionOf(history, fromVersion);
        RobotFileVersion to = versionOf(history, toVersion);
        return LineDiff.between(from.content(), to.content());
    }

    private static RobotFileVersion versionOf(List<RobotFileVersion> history, int versionNo) {
        return history.stream()
                .filter(item -> item.versionNo() == versionNo)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "版本不存在：v" + versionNo));
    }

    private String resolvePath(SaveCommand command) {
        if (command.path() != null && !command.path().isBlank()) {
            return FileWorkspaceService.normalize(command.path());
        }
        if (command.fileId() != null) {
            return files.find(command.projectId(), command.fileId())
                    .map(RobotFile::path)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
        }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 path 或 fileId");
    }

    private void rejectIfInvalid(SyntaxReport report) {
        if (report.checked() && !report.ok()) {
            String where = report.line() > 0 ? "第 " + report.line() + " 行：" : "";
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "语法校验失败，" + where + report.message());
        }
    }

    private BusinessException conflict(String path, int expected, int current) {
        return new BusinessException(ErrorCode.CONFLICT,
                "他人已修改（期望 v" + expected + "，当前 v" + current + "），请刷新对比：" + path);
    }

    private static String commentOf(String comment) {
        return comment == null ? "" : comment;
    }
}
