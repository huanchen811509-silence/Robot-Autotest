package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ReportLifecycleService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行记录（run 展示层）的 PATCH / DELETE / 恢复 / 影响面（v5.7.0 / W57-33、34）。
 *
 * <p>用 {@code /api/runs} 独立路径而不是塞进 {@code /api/reports}：后者的 {@code {runId}}
 * 已被 ReportController 的 GET 与 ReportExportController 的若干子路径占用，
 * 新开一段路径可以让「运行记录」与「执行报告展示」两套语义各自演进、互不抢匹配。
 */
@RestController
@RequestMapping("/api/runs")
public class RunRecordController {

    private final ReportLifecycleService lifecycle;
    private final AccessControlService access;

    public RunRecordController(ReportLifecycleService lifecycle, AccessControlService access) {
        this.lifecycle = lifecycle;
        this.access = access;
    }

    /** 展示层元数据局部更新（与 PUT /api/reports/{runId} 同一服务方法）。 */
    @PatchMapping("/{runId}")
    public ApiResponse<ReportLifecycleService.RunMeta> patch(
            @PathVariable String runId,
            @RequestParam long projectId,
            @RequestBody(required = false) ReportLifecycleService.RunUpdate body) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.updateRun(projectId, runId, body));
    }

    /** 读展示层元数据（备注 / 展示名 / 标签），供编辑框回填（复核 P1-5）。 */
    @GetMapping("/{runId}")
    public ApiResponse<ReportLifecycleService.RunMeta> get(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.readRun(projectId, runId));
    }

    /** 软删（幂等）。 */
    @DeleteMapping("/{runId}")
    public ApiResponse<ReportLifecycleService.DeleteResult> delete(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.deleteRun(projectId, runId));
    }

    /** 恢复软删的运行记录。 */
    @PostMapping("/{runId}/restore")
    public ApiResponse<ReportLifecycleService.RunMeta> restore(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.restoreRun(projectId, runId));
    }

    /** 删除前的影响面预览：test_report_export / run_artifact 条数。 */
    @GetMapping("/{runId}/impact")
    public ApiResponse<ReportLifecycleService.RunImpact> impact(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.impact(projectId, runId));
    }
}
