package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.application.repository.WorkspaceRepository;
import com.qa.autotest.application.workspace.WorkspaceBundleReader.VerifiedBundle;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestCaseStep;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceReport;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工作区导入（FR-v4.1-02）：验签 → 预检（dry-run）→ 单事务落库 → 隔离/归档与报告。
 *
 * <p>纪律（ADR-4X-13 / 契约 §6）：
 * <ol>
 *   <li>只写 {@code robot_file}、{@code test_case}（按 caseCode 新建，不覆盖台账）、
 *       {@code test_set}；成员/资产/凭据/环境只做引用映射，报告只归档索引；</li>
 *   <li>可疑脚本先隔离到 {@code workspace/.quarantine/<code>/<jobId>/}，导入者显式确认才写库；</li>
 *   <li>整包要么全部落库，要么全部回滚（隔离副本除外），幂等：重复导入全部 SKIP。</li>
 * </ol>
 */
@Service
public class WorkspaceImportService {

    private static final Set<String> STRATEGIES = Set.of("SKIP", "OVERWRITE", "RENAME");

    private final WorkspaceRepository repo;
    private final ProjectRepository projects;
    private final RobotFileRepository files;
    private final TestCaseRepository cases;
    private final TestSetRepository sets;
    private final WorkspaceBundleReader reader;
    private final ScriptSafetyScanner scanner;
    private final WorkspaceProperties properties;
    private final AuditService audit;
    private final AccessControlService accessControl;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @Autowired
    public WorkspaceImportService(WorkspaceRepository repo, ProjectRepository projects,
                                  RobotFileRepository files, TestCaseRepository cases,
                                  TestSetRepository sets, WorkspaceBundleReader reader,
                                  ScriptSafetyScanner scanner,
                                  WorkspaceProperties properties, AuditService audit,
                                  AccessControlService accessControl, ObjectMapper mapper,
                                  PlatformTransactionManager txManager) {
        this(repo, projects, files, cases, sets, reader, scanner, properties, audit,
                accessControl, mapper, new TransactionTemplate(txManager));
    }

    /** 测试装配：{@code tx=null} 表示不分事务（假仓储）。 */
    public WorkspaceImportService(WorkspaceRepository repo, ProjectRepository projects,
                                  RobotFileRepository files, TestCaseRepository cases,
                                  TestSetRepository sets, WorkspaceBundleReader reader,
                                  ScriptSafetyScanner scanner,
                                  WorkspaceProperties properties, AuditService audit,
                                  AccessControlService accessControl, ObjectMapper mapper,
                                  TransactionTemplate tx) {
        this.repo = repo;
        this.projects = projects;
        this.files = files;
        this.cases = cases;
        this.sets = sets;
        this.reader = reader;
        this.scanner = scanner;
        this.properties = properties;
        this.audit = audit;
        this.accessControl = accessControl;
        this.mapper = mapper;
        this.tx = tx;
    }

    /** 预检：验签 + 逐条判定，不写库、不建任务。 */
    public WorkspaceReport preflight(long projectId, String bundlePath, String strategy) {
        accessRead(projectId);
        Project project = requireProject(projectId);
        VerifiedBundle bundle = verified(projectId, project, bundlePath);
        return plan(projectId, project, bundle, normalizeStrategy(strategy), false, false, 0);
    }

    /** 真实导入：建任务（项目锁）→ 单事务落库 → 终态与审计。 */
    public WorkspaceReport importBundle(long projectId, String bundlePath, String strategy,
                                        boolean confirmQuarantine) {
        accessImport(projectId);
        Project project = requireProject(projectId);
        VerifiedBundle bundle = verified(projectId, project, bundlePath);
        String level = bundle.manifest().granularity() == null
                || bundle.manifest().granularity().isBlank()
                ? "PROJECT" : bundle.manifest().granularity();
        String chosen = normalizeStrategy(strategy);
        long jobId = repo.createJob(projectId, "IMPORT", level, null, chosen, bundlePath,
                TenantContext.userId());
        Long operatorId = TenantContext.userId();
        try {
            repo.markRunning(projectId, jobId);
            WorkspaceReport report = tx == null
                    ? plan(projectId, project, bundle, chosen, true, confirmQuarantine, jobId)
                    : tx.execute(status ->
                            plan(projectId, project, bundle, chosen, true, confirmQuarantine, jobId));
            repo.finishJob(projectId, jobId, "SUCCESS", bundlePath, bundle.manifestSha256(),
                    toJson(report), null, operatorId);
            audit.recordChange("workspace:import", "workspace", project.code(), null,
                    "job=" + jobId + "; imported=" + report.filesImported()
                            + "; skipped=" + report.filesSkipped()
                            + "; quarantined=" + report.filesQuarantined()
                            + "; strategy=" + chosen);
            if (report.filesQuarantined() > 0) {
                audit.record("workspace:import-quarantine", "workspace", project.code(),
                        "job=" + jobId + "; files=" + report.filesQuarantined());
            }
            return report;
        } catch (BusinessException exc) {
            fail(projectId, jobId, project.code(), exc.getMessage(), operatorId);
            throw exc;
        } catch (RuntimeException exc) {
            fail(projectId, jobId, project.code(), exc.toString(), operatorId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入失败：" + exc.getMessage());
        }
    }

    /** 重试失败的导入任务：沿用原包路径与策略。 */
    public WorkspaceReport retry(long projectId, long jobId, boolean confirmQuarantine) {
        accessImport(projectId);
        WorkspaceJob job = repo.findJob(projectId, jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));
        if (!"IMPORT".equals(job.jobType())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该任务不是导入任务");
        }
        if (job.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务仍在进行中");
        }
        if (job.bundlePath() == null || job.bundlePath().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "任务缺少包路径，无法重试");
        }
        return importBundle(projectId, job.bundlePath(), job.strategy(), confirmQuarantine);
    }

    private WorkspaceReport plan(long projectId, Project project, VerifiedBundle bundle,
                                 String strategy, boolean apply, boolean confirmQuarantine,
                                 long jobId) {
        List<WorkspaceReport.Item> items = new ArrayList<>();
        List<WorkspaceReport.Quarantine> quarantine = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int quarantined = 0;
        Long operatorId = TenantContext.userId();
        Map<String, Long> caseIds = new HashMap<>();

        for (WorkspaceSnapshot.FileInfo file : bundle.files()) {
            List<String> findings = scanner.scan(file.path(), file.content());
            if (!findings.isEmpty() && !confirmQuarantine) {
                quarantined++;
                quarantine.add(new WorkspaceReport.Quarantine(file.path(), findings));
                items.add(new WorkspaceReport.Item("FILE", file.path(), "QUARANTINE",
                        "命中可疑脚本，已隔离；确认后可重新导入"));
                if (apply) {
                    quarantineCopy(project.code(), jobId, file);
                }
                continue;
            }
            if (!findings.isEmpty()) {
                warnings.add("脚本 " + file.path() + " 命中可疑模式，已按显式确认导入");
            }
            Optional<RobotFile> existing = files.findByPath(projectId, file.path());
            if (existing.isEmpty()) {
                if (apply) {
                    files.insert(projectId, file.path(), file.content(), sha256(file.content()), operatorId);
                }
                imported++;
                items.add(new WorkspaceReport.Item("FILE", file.path(), "IMPORT", "新建脚本"));
                continue;
            }
            if ("SKIP".equals(strategy)) {
                skipped++;
                items.add(new WorkspaceReport.Item("FILE", file.path(), "SKIP", "同路径已存在（策略 SKIP）"));
            } else if ("OVERWRITE".equals(strategy)) {
                if (apply) {
                    files.updateContent(projectId, existing.get().id(), file.content(),
                            sha256(file.content()), "工作区导入覆盖", operatorId);
                }
                imported++;
                items.add(new WorkspaceReport.Item("FILE", file.path(), "OVERWRITE", "覆盖已有脚本"));
            } else {
                String renamed = nextPath(projectId, file.path());
                if (apply) {
                    files.insert(projectId, renamed, file.content(), sha256(file.content()), operatorId);
                }
                imported++;
                items.add(new WorkspaceReport.Item("FILE", renamed, "RENAME",
                        "原路径 " + file.path() + " 已存在，改名写入"));
            }
        }

        for (WorkspaceSnapshot.CaseInfo one : bundle.cases()) {
            if (one.caseCode() == null || one.caseCode().isBlank()) {
                skipped++;
                items.add(new WorkspaceReport.Item("CASE", "(空)", "SKIP", "缺少 caseCode"));
                continue;
            }
            Optional<TestCase> existing = cases.findByCode(projectId, one.caseCode());
            if (existing.isPresent()) {
                caseIds.put(one.caseCode(), existing.get().id());
                skipped++;
                items.add(new WorkspaceReport.Item("CASE", one.caseCode(), "SKIP",
                        "同 caseCode 已存在（导入不覆盖台账）"));
                continue;
            }
            long id = -1;
            if (apply) {
                id = cases.insert(new TestCase(null, projectId, one.caseCode(), one.title(),
                        one.kind(), one.priority(), null, null, one.domainTag(), "draft",
                        "import", null, one.description(), "LX"), operatorId);
                List<TestCaseStep> steps = new ArrayList<>();
                for (WorkspaceSnapshot.StepInfo step : one.steps()) {
                    steps.add(new TestCaseStep(null, id, step.stepNo(), step.action(), step.expectation()));
                }
                cases.replaceSteps(id, steps, operatorId);
            }
            caseIds.put(one.caseCode(), id);
            imported++;
            items.add(new WorkspaceReport.Item("CASE", one.caseCode(), "IMPORT",
                    "新建用例（review_status=draft，safety_level=LX）"));
        }

        for (WorkspaceSnapshot.CaseSetInfo set : bundle.caseSets()) {
            if (set.setCode() == null || set.setCode().isBlank()) {
                skipped++;
                items.add(new WorkspaceReport.Item("CASE_SET", "(空)", "SKIP", "缺少 setCode"));
                continue;
            }
            if (sets.codeExists(projectId, set.setCode(), null)) {
                skipped++;
                items.add(new WorkspaceReport.Item("CASE_SET", set.setCode(), "SKIP",
                        "同 setCode 已存在（导入不覆盖用例集）"));
                continue;
            }
            long setId = -1;
            if (apply) {
                setId = sets.insertWithKind(new TestSet(null, projectId, set.setCode(), set.name(),
                        set.description(), operatorId, "active", 0), operatorId, "case_set", null);
            }
            for (WorkspaceSnapshot.SetItem item : set.items()) {
                if (item.caseCode() == null || item.caseCode().isBlank()) {
                    continue;
                }
                Long caseId = caseIds.get(item.caseCode());
                if (caseId == null || caseId <= 0) {
                    caseId = cases.findByCode(projectId, item.caseCode())
                            .map(TestCase::id).orElse(null);
                }
                if (caseId == null) {
                    warnings.add("用例集 " + set.setCode() + " 引用用例 " + item.caseCode()
                            + " 不存在，跳过该成员");
                    continue;
                }
                caseIds.put(item.caseCode(), caseId);
                if (apply) {
                    sets.addItem(projectId, setId, caseId, item.seqNo(), operatorId);
                }
            }
            imported++;
            items.add(new WorkspaceReport.Item("CASE_SET", set.setCode(), "IMPORT", "新建用例集"));
        }

        for (WorkspaceSnapshot.MemberInfo member : bundle.members()) {
            skipped++;
            items.add(new WorkspaceReport.Item("MEMBER", member.login(), "SKIP",
                    "仅做引用映射，不自动建用户或改授权"));
        }
        for (WorkspaceSnapshot.AssetInfo asset : bundle.assets()) {
            skipped++;
            items.add(new WorkspaceReport.Item("ASSET", asset.type() + ":" + asset.name(), "SKIP",
                    "仅做引用映射，不写入设备/链路"));
        }
        for (WorkspaceSnapshot.CredentialInfo credential : bundle.credentials()) {
            skipped++;
            items.add(new WorkspaceReport.Item("CREDENTIAL", credential.name(), "SKIP",
                    "仅做引用映射，凭据密文永不出包"));
        }
        for (WorkspaceSnapshot.EnvironmentInfo environment : bundle.environments()) {
            skipped++;
            items.add(new WorkspaceReport.Item("ENV", environment.name(), "SKIP",
                    "仅做引用映射，不写入环境画像"));
        }
        for (WorkspaceSnapshot.ReportInfo report : bundle.reports()) {
            warnings.add("报告 " + report.reportNo() + " 只归档索引，不重建报告正文");
            skipped++;
            items.add(new WorkspaceReport.Item("REPORT", report.reportNo(), "SKIP", "仅归档索引"));
        }
        for (WorkspaceSnapshot.RunInfo run : bundle.runs()) {
            skipped++;
            items.add(new WorkspaceReport.Item("RUN", run.runId(), "SKIP", "仅归档索引"));
        }

        List<String> archived = archiveList(bundle);
        for (String path : archived) {
            skipped++;
            items.add(new WorkspaceReport.Item("ARCHIVE", path, "SKIP",
                    "仅归档索引，不复制正文（ADR-4X-13）"));
        }

        return new WorkspaceReport(true, bundle.root().toString(), "OK", "导入完成",
                imported, skipped, quarantined, items, quarantine, warnings);
    }

    private VerifiedBundle verified(long projectId, Project project, String bundlePath) {
        Path dir = bundleDir(bundlePath);
        VerifiedBundle bundle = reader.verify(dir);
        if (!bundle.project().code().equalsIgnoreCase(project.code())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "包内项目 code=" + bundle.project().code() + " 与目标项目 code="
                            + project.code() + " 不一致，拒绝导入（冷启动请显式导草稿）");
        }
        return bundle;
    }

    private Path bundleDir(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请选择工作区包目录");
        }
        Path relative;
        try {
            relative = Paths.get(bundlePath.trim());
        } catch (java.nio.file.InvalidPathException exc) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "包路径非法: " + bundlePath);
        }
        if (relative.isAbsolute()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "包路径必须是工作区下的相对路径");
        }
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "包路径不得指向隐藏目录");
            }
        }
        Path root = properties.rootPath();
        Path dir = root.resolve(relative).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "包路径越界: " + bundlePath);
        }
        return dir;
    }

    private void quarantineCopy(String projectCode, long jobId, WorkspaceSnapshot.FileInfo file) {
        Path base = properties.rootPath().resolve(".quarantine")
                .resolve(projectCode).resolve(String.valueOf(jobId));
        Path target = base.resolve(file.path()).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "隔离路径越界: " + file.path());
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content() == null ? "" : file.content(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写入隔离区失败: " + exc.getMessage());
        }
    }

    private List<String> archiveList(VerifiedBundle bundle) {
        Path archive = bundle.root().resolve("archive");
        if (!Files.isDirectory(archive)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(archive)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path ->
                    paths.add(archive.relativize(path).toString().replace('\\', '/')));
        } catch (IOException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 archive 目录失败: " + exc.getMessage());
        }
        return paths;
    }

    private String nextPath(long projectId, String path) {
        int dot = path.lastIndexOf('.');
        String base = dot > 0 ? path.substring(0, dot) : path;
        String ext = dot > 0 ? path.substring(dot) : "";
        for (int n = 1; n <= 999; n++) {
            String candidate = base + "__" + n + ext;
            if (!files.pathExists(projectId, candidate, null)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "无法为重名文件生成可用路径: " + path);
    }

    private void fail(long projectId, long jobId, String projectCode, String reason, Long operatorId) {
        repo.finishJob(projectId, jobId, "FAILED", null, null, null, reason, operatorId);
        audit.recordFailure("workspace:import", "workspace", projectCode, reason);
    }

    private Project requireProject(long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
    }

    private void accessRead(long projectId) {
        accessControl.bindProject(projectId);
        accessControl.require(PermissionResource.CASE, PermissionAction.READ);
    }

    private void accessImport(long projectId) {
        accessControl.bindProject(projectId);
        accessControl.require(PermissionResource.IMPORT, PermissionAction.EXECUTE);
    }

    private static String normalizeStrategy(String strategy) {
        String value = strategy == null || strategy.isBlank()
                ? "SKIP" : strategy.trim().toUpperCase(Locale.ROOT);
        if (!STRATEGIES.contains(value)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "冲突策略只允许 SKIP / OVERWRITE / RENAME");
        }
        return value;
    }

    private String toJson(WorkspaceReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入报告序列化失败");
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用");
        }
    }
}
