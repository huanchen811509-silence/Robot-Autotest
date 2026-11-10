package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.WorkspaceRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceReport;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工作区导出（FR-v4.1-01）：只读事务取快照 → 临时目录写包 → 原子替换 → 任务终态与审计。
 *
 * <p>导出粒度 PROJECT / DIR / FILE 由 {@link WorkspaceBundleWriter} 负责裁剪；
 * 任务行既是进度也是项目锁（活动任务唯一键），重复导出返回 409。</p>
 */
@Service
public class WorkspaceExportService {

    private static final Pattern PROJECT_CODE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final List<String> LEVELS = List.of("PROJECT", "DIR", "FILE");

    /** 包目录条目；{@code path} 是相对工作区根，可直接回传给预检/导入。 */
    public record BundleEntry(String path, String projectCode, String exportedAt,
                              String granularity, long bytes, int fileCount, boolean signed) {
    }

    private final WorkspaceRepository repo;
    private final ProjectRepository projects;
    private final WorkspaceBundleWriter writer;
    private final WorkspaceProperties properties;
    private final AuditService audit;
    private final AccessControlService accessControl;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @Autowired
    public WorkspaceExportService(WorkspaceRepository repo, ProjectRepository projects,
                                  WorkspaceBundleWriter writer, WorkspaceProperties properties,
                                  AuditService audit, AccessControlService accessControl,
                                  ObjectMapper mapper, PlatformTransactionManager txManager) {
        this(repo, projects, writer, properties, audit, accessControl, mapper,
                new TransactionTemplate(txManager));
    }

    /** 测试装配：{@code tx=null} 表示直接取快照（假仓储无事务语义）。 */
    public WorkspaceExportService(WorkspaceRepository repo, ProjectRepository projects,
                                  WorkspaceBundleWriter writer, WorkspaceProperties properties,
                                  AuditService audit, AccessControlService accessControl,
                                  ObjectMapper mapper, TransactionTemplate tx) {
        this.repo = repo;
        this.projects = projects;
        this.writer = writer;
        this.properties = properties;
        this.audit = audit;
        this.accessControl = accessControl;
        this.mapper = mapper;
        this.tx = tx;
        if (this.tx != null) {
            this.tx.setReadOnly(true);
        }
    }

    public WorkspaceReport export(long projectId, String granularity, String rootPath) {
        access(projectId);
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        String level = normalizeLevel(granularity);
        String scope = normalizeScope(level, rootPath);
        Path target = bundleDir(project.code());

        Long operatorId = TenantContext.userId();
        long jobId = repo.createJob(projectId, "EXPORT", level, scope, null, null, operatorId);
        try {
            repo.markRunning(projectId, jobId);
            WorkspaceSnapshot snapshot = snapshot(projectId, project);
            WorkspaceBundleWriter.Result result = writer.write(target, snapshot, level, scope);
            repo.finishJob(projectId, jobId, JobStatus.SUCCESS, result.bundlePath(),
                    result.manifestSha256(), reportJson(result), null, operatorId);
            audit.recordChange("workspace:export", "workspace", project.code(), null,
                    "granularity=" + level + "; files=" + result.fileCount()
                            + "; bytes=" + result.totalBytes() + "; job=" + jobId);
            return new WorkspaceReport(true, result.bundlePath(), "OK", "导出完成",
                    result.fileCount(), 0, 0,
                    List.of(new WorkspaceReport.Item("PROJECT", project.code(), "EXPORT",
                            "粒度 " + level + "，文件 " + result.fileCount() + " 个")),
                    List.of(), List.of());
        } catch (BusinessException exc) {
            fail(projectId, jobId, project.code(), exc.getMessage(), operatorId);
            throw exc;
        } catch (RuntimeException exc) {
            fail(projectId, jobId, project.code(), exc.toString(), operatorId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出失败：" + exc.getMessage());
        }
    }

    /** 任务中心读取（只读权限即可）。 */
    public List<WorkspaceJob> recentJobs(long projectId, int limit) {
        access(projectId);
        return repo.recentJobs(projectId, limit);
    }

    public WorkspaceJob findJob(long projectId, long jobId) {
        access(projectId);
        return repo.findJob(projectId, jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));
    }

    /** 重试失败的导出任务：复用原粒度/范围与目标目录。 */
    public WorkspaceReport retry(long projectId, long jobId) {
        access(projectId);
        WorkspaceJob job = repo.findJob(projectId, jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));
        if (!"EXPORT".equals(job.jobType())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该任务不是导出任务");
        }
        if (job.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务仍在进行中");
        }
        return export(projectId, job.granularity(), job.targetPath());
    }

    private WorkspaceSnapshot snapshot(long projectId, Project project) {
        WorkspaceSnapshot.ProjectInfo info = new WorkspaceSnapshot.ProjectInfo(
                project.code(), project.name(), project.description(), project.testKernel());
        if (tx == null) {
            return repo.snapshot(projectId, info);
        }
        return tx.execute(status -> repo.snapshot(projectId, info));
    }

    private void fail(long projectId, long jobId, String projectCode, String reason, Long operatorId) {
        repo.finishJob(projectId, jobId, JobStatus.FAILED, null, null, null, reason, operatorId);
        audit.recordFailure("workspace:export", "workspace", projectCode, reason);
    }

    private void access(long projectId) {
        accessControl.bindProject(projectId);
        accessControl.require(PermissionResource.CASE, PermissionAction.READ);
    }

    /** 工作区根下可导入的包（有 manifest.json 的一级目录），只做浅解析供向导选择。 */
    public List<BundleEntry> bundles(long projectId) {
        access(projectId);
        Path root = properties.rootPath();
        if (!java.nio.file.Files.isDirectory(root)) {
            return List.of();
        }
        List<BundleEntry> result = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(root)) {
            stream.filter(java.nio.file.Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .forEach(path -> result.add(entry(root, path)));
        } catch (java.io.IOException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取工作区目录失败: " + exc.getMessage());
        }
        return result;
    }

    private BundleEntry entry(Path root, Path dir) {
        Path manifestPath = dir.resolve("manifest.json");
        if (!java.nio.file.Files.isRegularFile(manifestPath)) {
            return new BundleEntry(root.relativize(dir).toString().replace('\\', '/'),
                    dir.getFileName().toString(), null, null, 0, 0, false);
        }
        try {
            var node = mapper.readTree(manifestPath.toFile());
            long bytes = 0;
            var files = node.path("files");
            if (files.isArray()) {
                for (var file : files) {
                    bytes += file.path("bytes").asLong(0);
                }
            }
            boolean signed = node.hasNonNull("signature")
                    && node.path("signature").hasNonNull("value");
            return new BundleEntry(root.relativize(dir).toString().replace('\\', '/'),
                    node.path("projectCode").asText(dir.getFileName().toString()),
                    node.path("exportedAt").asText(null),
                    node.path("granularity").asText(null),
                    bytes, files.isArray() ? files.size() : 0, signed);
        } catch (java.io.IOException exc) {
            return new BundleEntry(root.relativize(dir).toString().replace('\\', '/'),
                    dir.getFileName().toString(), null, null, 0, 0, false);
        }
    }

    private String reportJson(WorkspaceBundleWriter.Result result) {
        try {
            return mapper.writeValueAsString(new WorkspaceReport(true, result.bundlePath(), "OK",
                    "导出完成", result.fileCount(), 0, 0, List.of(), List.of(), List.of()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务报告序列化失败");
        }
    }

    private Path bundleDir(String projectCode) {
        if (projectCode == null || !PROJECT_CODE.matcher(projectCode).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "项目 code 含非法字符，无法作为工作区目录名: " + projectCode);
        }
        return properties.rootPath().resolve(projectCode);
    }

    private static String normalizeLevel(String granularity) {
        String level = granularity == null || granularity.isBlank()
                ? "PROJECT" : granularity.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "导出粒度只允许 PROJECT / DIR / FILE");
        }
        return level;
    }

    private static String normalizeScope(String level, String rootPath) {
        String scope = rootPath == null ? "" : rootPath.trim();
        if (scope.startsWith("/")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "范围路径必须是不以 / 开头的相对路径");
        }
        if ("PROJECT".equals(level)) {
            return scope.isEmpty() ? "" : scope;
        }
        if (scope.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "DIR / FILE 粒度必须指定包内相对路径");
        }
        return scope;
    }

    /** 任务状态常量与契约一致（V11_28 列注释）。 */
    private static final class JobStatus {
        static final String SUCCESS = "SUCCESS";
        static final String FAILED = "FAILED";
    }
}
