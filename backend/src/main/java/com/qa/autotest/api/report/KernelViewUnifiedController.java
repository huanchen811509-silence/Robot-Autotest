package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.KernelUnifiedViewService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双内核统一视图（FR-v5.4-06 / W54-06）。
 *
 * <p>与 {@code /result-split} 的分工：那个按「执行方式」拆（自动/手工），
 * 这个按「内核」拆（RF/pytest）。两者是正交的两个维度，不能混在一个接口里——
 * 混起来会让「pytest 手工执行」这种组合变得无法表达。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/kernel-view")
public class KernelViewUnifiedController {

    private final KernelUnifiedViewService service;
    private final AccessControlService access;

    public KernelViewUnifiedController(KernelUnifiedViewService service,
                                       AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<KernelUnifiedViewService.UnifiedView> view(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "7") int days) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.view(projectId, days));
    }
}
