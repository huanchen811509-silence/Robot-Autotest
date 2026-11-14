package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ReportLifecycleService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户报告（test_report）的编辑 / 软删 / 恢复（v5.7.0 / W57-31、34）。
 *
 * <p>刻意做成 {@code /api/test-reports} 下的<b>独立 Controller</b>：既有
 * {@link TestReportController} 的 freeze / revoke / archive 三个动作语义零改动——
 * 一个字都不碰，也不动它的唯一公开构造器。「现有删除文案」实为
 * {@code POST /{id}/revoke}（状态机流转 REVOKED），不是物理/软删，本类不复用它，
 * 而是新增真正的 {@code DELETE} 软删。
 */
@RestController
@RequestMapping("/api/test-reports")
public class TestReportCrudController {

    private final ReportLifecycleService lifecycle;
    private final AccessControlService access;

    public TestReportCrudController(ReportLifecycleService lifecycle, AccessControlService access) {
        this.lifecycle = lifecycle;
        this.access = access;
    }

    /** 编辑 title / remark / tags；冻结件改标题会被服务层拒绝。 */
    @PutMapping("/{id}")
    public ApiResponse<ReportLifecycleService.TestReportMeta> update(
            @PathVariable long id,
            @RequestParam long projectId,
            @RequestBody(required = false) ReportLifecycleService.TestReportUpdate body) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(lifecycle.updateTestReport(projectId, id, body));
    }

    /** 软删（幂等）；冻结件拒绝删除。 */
    @DeleteMapping("/{id}")
    public ApiResponse<ReportLifecycleService.DeleteResult> delete(
            @PathVariable long id,
            @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(lifecycle.deleteTestReport(projectId, id));
    }

    /** 恢复软删的用户报告。 */
    @PostMapping("/{id}/restore")
    public ApiResponse<ReportLifecycleService.TestReportMeta> restore(
            @PathVariable long id,
            @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(lifecycle.restoreTestReport(projectId, id));
    }
}
