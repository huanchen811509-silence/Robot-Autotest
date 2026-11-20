package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CaseBindingConfirmService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 脚本绑定人工确认端点（v5.31.0 / FR-v5.31-01）。
 *
 * <p>单独成控制器而不是塞进 {@code CaseController}：后者有 3 参构造器被既有测试直接 new，
 * 加依赖会连带改一批测试桩；本端点与用例 CRUD 无耦合。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class CaseBindingController {

    public record BatchConfirmRequest(List<Long> caseIds, String kernel) {
    }

    private final CaseBindingConfirmService confirms;

    public CaseBindingController(CaseBindingConfirmService confirms) {
        this.confirms = confirms;
    }

    @PostMapping("/cases/{caseId}/binding/confirm")
    public ApiResponse<CaseBindingConfirmService.ConfirmResult> confirm(
            @PathVariable long projectId,
            @PathVariable long caseId,
            @RequestParam(required = false) String kernel) {
        return ApiResponse.ok(confirms.confirm(projectId, caseId, kernel));
    }

    @PostMapping("/cases/binding/confirm-batch")
    public ApiResponse<CaseBindingConfirmService.ConfirmResult> confirmBatch(
            @PathVariable long projectId,
            @RequestBody BatchConfirmRequest request) {
        return ApiResponse.ok(confirms.confirmBatch(projectId, request.caseIds(),
                request.kernel()));
    }
}
