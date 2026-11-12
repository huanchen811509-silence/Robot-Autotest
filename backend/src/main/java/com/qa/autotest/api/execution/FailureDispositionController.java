package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.FailureDispositionService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.execution.FailureDisposition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 失败处置闭环（FR-v5.3-06 / W53-06）。
 *
 * <p>六个动作端点：record / assign / decide / close / reopen + 查询。
 * 全部 POST 而不是 PATCH：每次都是**状态迁移**，不是字段覆盖，
 * 用 PATCH 会让「改结论」和「改备注」看起来是同一类操作。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/dispositions")
public class FailureDispositionController {

    private final FailureDispositionService service;
    private final AccessControlService access;

    public FailureDispositionController(FailureDispositionService service,
                                        AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<FailureDisposition>> list(
            @PathVariable long projectId,
            @RequestParam(required = false) String status) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.list(projectId, status));
    }

    @GetMapping("/ledger")
    public ApiResponse<FailureDispositionService.Ledger> ledger(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.ledger(projectId));
    }

    @GetMapping("/by-case/{caseId}")
    public ApiResponse<List<FailureDisposition>> byCase(@PathVariable long projectId,
                                                        @PathVariable long caseId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.listByCase(projectId, caseId));
    }

    /** 记录一次失败（幂等累加；已定论且非 known_issue 时自动 reopen）。 */
    @PostMapping("/record")
    public ApiResponse<FailureDisposition> record(
            @PathVariable long projectId,
            @RequestBody FailureDispositionService.FailureContext context) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.record(projectId, context));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<FailureDisposition> assign(@PathVariable long projectId,
                                                  @PathVariable long id,
                                                  @RequestParam long assigneeId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.assign(projectId, id, assigneeId));
    }

    @PostMapping("/{id}/decide")
    public ApiResponse<FailureDisposition> decide(
            @PathVariable long projectId,
            @PathVariable long id,
            @RequestBody DecideRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.decide(projectId, id, request.decision(), request.note()));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<FailureDisposition> close(@PathVariable long projectId,
                                                 @PathVariable long id,
                                                 @RequestParam(required = false) String note) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.close(projectId, id, note));
    }

    @PostMapping("/{id}/reopen")
    public ApiResponse<FailureDisposition> reopen(@PathVariable long projectId,
                                                  @PathVariable long id,
                                                  @RequestParam String reason) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.reopen(projectId, id, reason));
    }

    public record DecideRequest(String decision, String note) {
    }
}
