package com.qa.autotest.api.workspace;

import com.qa.autotest.application.workspace.WorkspaceExportService;
import com.qa.autotest.application.workspace.WorkspaceImportService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceReport;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区导出/导入 7 端点（FR-v4.1-01..04）。
 *
 * <p>权限：导出/看任务用 CASE:READ（viewer 可）；导入用 IMPORT:EXECUTE（viewer 403）。
 * 任务行即项目锁，重复触发返回 409。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/workspace")
public class WorkspaceController {

    public record ExportRequest(String granularity, String rootPath) {
    }

    public record ImportRequest(String bundlePath, String strategy, boolean confirmQuarantine) {
    }

    public record ConfirmRequest(boolean confirmQuarantine) {
    }

    private final WorkspaceExportService exports;
    private final WorkspaceImportService imports;

    public WorkspaceController(WorkspaceExportService exports, WorkspaceImportService imports) {
        this.exports = exports;
        this.imports = imports;
    }

    @GetMapping("/bundles")
    public ApiResponse<List<WorkspaceExportService.BundleEntry>> bundles(@PathVariable long projectId) {
        return ApiResponse.ok(exports.bundles(projectId));
    }

    @PostMapping("/export")
    public ApiResponse<WorkspaceReport> export(@PathVariable long projectId,
                                               @RequestBody ExportRequest request) {
        return ApiResponse.ok(exports.export(projectId, request.granularity(), request.rootPath()));
    }

    @PostMapping("/import/preflight")
    public ApiResponse<WorkspaceReport> preflight(@PathVariable long projectId,
                                                  @RequestBody ImportRequest request) {
        return ApiResponse.ok(imports.preflight(projectId, request.bundlePath(), request.strategy()));
    }

    @PostMapping("/import")
    public ApiResponse<WorkspaceReport> importBundle(@PathVariable long projectId,
                                                     @RequestBody ImportRequest request) {
        return ApiResponse.ok(imports.importBundle(projectId, request.bundlePath(),
                request.strategy(), request.confirmQuarantine()));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<WorkspaceJob>> jobs(@PathVariable long projectId,
                                                @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(exports.recentJobs(projectId, Math.min(Math.max(limit, 1), 100)));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<WorkspaceJob> job(@PathVariable long projectId, @PathVariable long jobId) {
        return ApiResponse.ok(exports.findJob(projectId, jobId));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ApiResponse<WorkspaceReport> retry(@PathVariable long projectId, @PathVariable long jobId,
                                              @RequestBody(required = false) ConfirmRequest request) {
        WorkspaceJob job = exports.findJob(projectId, jobId);
        boolean confirm = request != null && request.confirmQuarantine();
        if ("EXPORT".equals(job.jobType())) {
            return ApiResponse.ok(exports.retry(projectId, jobId));
        }
        return ApiResponse.ok(imports.retry(projectId, jobId, confirm));
    }
}
