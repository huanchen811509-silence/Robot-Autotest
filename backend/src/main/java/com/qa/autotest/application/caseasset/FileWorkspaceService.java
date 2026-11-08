package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.PasswordHasher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.FileNode;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.RobotFileVersion;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 文件工作区（CAS-04，ADR-0005）：{@code robot_file.content} 是脚本唯一真源。
 *
 * <p>所有路径先规范化再入库；绝对路径、{@code ..}、反斜杠、控制字符一律拒绝，
 * 防止路径穿越把文件写到工作区之外。
 */
@Service
public class FileWorkspaceService {

    /** 单个文件最大字节数（防止把整库拖垮）。 */
    public static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private static final List<String> ALLOWED_EXTENSIONS =
            List.of(".robot", ".resource", ".py", ".ini", ".cfg", ".yaml", ".yml", ".json", ".txt", ".md");

    private final RobotFileRepository files;
    private final AccessControlService access;
    private final AuditService audit;

    public FileWorkspaceService(RobotFileRepository files, AccessControlService access,
                                AuditService audit) {
        this.files = files;
        this.access = access;
        this.audit = audit;
    }

    public List<FileNode> tree(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return files.nodes(projectId);
    }

    public RobotFile read(long projectId, String path) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return files.findByPath(projectId, normalize(path))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
    }

    public List<RobotFileVersion> versions(long projectId, String path) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        RobotFile file = read(projectId, path);
        return files.versions(projectId, file.id());
    }

    /** 上传或保存（存在则新增版本，不存在则创建）。 */
    public RobotFile save(long projectId, String path, String content, String comment) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.CREATE);
        String normalized = normalize(path);
        if (content == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文件内容不能为空");
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "文件超过 " + (MAX_CONTENT_BYTES / 1024) + " KB 上限");
        }
        String sha256 = PasswordHasher.sha256Hex(content);
        Long operator = TenantContext.userId();
        RobotFile existing = files.findByPath(projectId, normalized).orElse(null);
        if (existing == null) {
            long id = files.insert(projectId, normalized, content, sha256, operator);
            audit.record("file.create", "robot_file", String.valueOf(id), "path=" + normalized);
        } else {
            files.updateContent(projectId, existing.id(), content, sha256, comment, operator);
            audit.record("file.save", "robot_file", String.valueOf(existing.id()),
                    "path=" + normalized);
        }
        return read(projectId, normalized);
    }

    public RobotFile move(long projectId, String fromPath, String toPath) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        String from = normalize(fromPath);
        String to = normalize(toPath);
        RobotFile existing = files.findByPath(projectId, from)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
        if (to.equals(from)) {
            return existing;
        }
        if (files.pathExists(projectId, to, existing.id())) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标路径已存在：" + to);
        }
        files.move(projectId, existing.id(), to, TenantContext.userId());
        audit.record("file.move", "robot_file", String.valueOf(existing.id()),
                from + " -> " + to);
        return read(projectId, to);
    }

    /** 删除文件或目录（目录按前缀整棵删）。 */
    public void delete(long projectId, String path) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.DELETE);
        String normalized = normalize(path);
        RobotFile existing = files.findByPath(projectId, normalized).orElse(null);
        if (existing != null) {
            files.delete(projectId, existing.id());
            audit.record("file.delete", "robot_file", String.valueOf(existing.id()),
                    "path=" + normalized);
            return;
        }
        String prefix = normalized + "/";
        boolean hasChildren = files.nodes(projectId).stream()
                .anyMatch(node -> node.path().startsWith(prefix));
        if (!hasChildren) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "路径不存在");
        }
        files.deleteByPrefix(projectId, prefix);
        audit.record("file.delete-dir", "robot_file", normalized, "prefix=" + prefix);
    }

    /**
     * 路径规范化：只接受相对路径、正斜杠，禁止 {@code ..} 与绝对路径。
     *
     * <p>返回去掉首尾斜杠、折叠重复斜杠后的路径。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径不能为空");
        }
        String value = raw.trim().replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "只允许工作区内的相对路径");
        }
        StringBuilder cleaned = new StringBuilder();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径不允许包含 ..");
            }
            if (segment.chars().anyMatch(Character::isISOControl)) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径含非法控制字符");
            }
            if (!cleaned.isEmpty()) {
                cleaned.append('/');
            }
            cleaned.append(segment);
        }
        if (cleaned.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "路径不能为空");
        }
        String normalized = cleaned.toString();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int lastSlash = lower.lastIndexOf('/');
        String lastName = lower.substring(lastSlash + 1);
        // 没有扩展名的当作目录（目录允许 import / delete）；有扩展名的必须在白名单内。
        if (lastName.contains(".") && ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的文件类型：" + normalized);
        }
        return normalized;
    }
}
