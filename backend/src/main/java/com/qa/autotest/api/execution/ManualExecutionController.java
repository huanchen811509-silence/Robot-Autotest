package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.ManualExecutionService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.execution.ManualExecution;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工执行（FR-v5.4-04 / W54-04）。
 *
 * <p>四个端点对应「开始 → 逐步确认 → 提交」这条单向流程，没有「改结论」：
 * 结论一旦提交就是事实，要改只能重新发起一次手工执行。这与自动执行一致——
 * 跑砸了的 run 不会被就地改成通过，而是再跑一次。
 *
 * <p>手工 run 由调用方先建（{@code trigger_type=manual}、{@code kernel=manual}），
 * 本控制器只负责执行记录本身；不碰租约、不碰队列、不触发自动报告（R6）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/manual-executions")
public class ManualExecutionController {

    private final ManualExecutionService service;
    private final AccessControlService access;

    public ManualExecutionController(ManualExecutionService service,
                                     AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    /** 开始一次手工执行。{@code stepNumbers} 来自用例定义的步骤号。 */
    public record StartRequest(long runId, long caseId, List<Integer> stepNumbers,
                               Long executorId, String executorName, String envNote) {
    }

    /** 确认一步。 */
    public record ConfirmRequest(int stepNo, String verdict, String note,
                                 List<String> attachments) {
    }

    /** 提交结论。 */
    public record SubmitRequest(String verdict, String note) {
    }

    @PostMapping
    public ApiResponse<ManualExecution> start(@PathVariable long projectId,
                                              @RequestBody StartRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.start(projectId, request.runId(), request.caseId(),
                request.stepNumbers(), request.executorId(), request.executorName(),
                request.envNote()));
    }

    @PostMapping("/{executionId}/steps")
    public ApiResponse<ManualExecution> confirm(@PathVariable long projectId,
                                                @PathVariable long executionId,
                                                @RequestBody ConfirmRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.confirm(projectId, executionId, request.stepNo(),
                request.verdict(), request.note(), request.attachments()));
    }

    /**
     * 提交结论。未确认全部步骤时返回 {@code submitted=false} 与未确认步骤号，
     * HTTP 仍是 200——这是「业务上不允许」，不是「请求错了」，
     * 用 200 + 明确原因比 4xx 更好用：页面可以直接把缺的步骤高亮出来。
     */
    @PostMapping("/{executionId}/submit")
    public ApiResponse<ManualExecutionService.SubmitResult> submit(
            @PathVariable long projectId,
            @PathVariable long executionId,
            @RequestBody SubmitRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.submit(projectId, executionId, request.verdict(),
                request.note()));
    }

    @GetMapping("/{executionId}")
    public ApiResponse<ManualExecution> find(@PathVariable long projectId,
                                             @PathVariable long executionId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.find(projectId, executionId));
    }

    /** 按 run 列出（一个手工 run 可能含多条用例）。 */
    @GetMapping
    public ApiResponse<List<ManualExecution>> listByRun(@PathVariable long projectId,
                                                        @RequestParam long runId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.listByRun(projectId, runId));
    }
}
