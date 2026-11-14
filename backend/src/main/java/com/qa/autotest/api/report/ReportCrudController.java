package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ReportLifecycleService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行报告（run 展示层）的编辑 / 软删（v5.7.0 / W57-31、33）。
 *
 * <p>挂在 {@code /api/reports} 下的<b>独立 Controller</b>：既有 {@link ReportController}
 * 已有两个公开构造器（其中 3 参装配被 {@code ReportControllerTest} 直接 new），按硬规矩
 * 「不要再加第三个、也不要删原有构造器」，故 PUT/DELETE 落到同路径的另一个类——
 * Spring 允许同路径不同方法分属不同 Controller。GET 列表/详情的软删过滤在
 * {@code ReportRepositoryJdbc} 的查询条件里，不在本类。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportCrudController {

    private final ReportLifecycleService lifecycle;
    private final AccessControlService access;

    public ReportCrudController(ReportLifecycleService lifecycle, AccessControlService access) {
        this.lifecycle = lifecycle;
        this.access = access;
    }

    /** 编辑展示层元数据：display_name / remark / tags。 */
    @PutMapping("/{runId}")
    public ApiResponse<ReportLifecycleService.RunMeta> update(
            @PathVariable String runId,
            @RequestParam long projectId,
            @RequestBody(required = false) ReportLifecycleService.RunUpdate body) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.updateRun(projectId, runId, body));
    }

    /** 软删；返回 deleted（幂等时 false）与影响面 count。 */
    @DeleteMapping("/{runId}")
    public ApiResponse<ReportLifecycleService.DeleteResult> delete(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(lifecycle.deleteRun(projectId, runId));
    }
}
