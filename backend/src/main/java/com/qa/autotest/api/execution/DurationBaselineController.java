package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.DurationBaselineService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.execution.CaseDurationBaseline;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 时长基线与软超时（FR-v5.3-03/04 / W53-03+04）。
 *
 * <p>只读三个 + 一个重算。软超时不提供「改阈值」接口：阈值由 P90×1.5 推出，
 * 允许逐用例手改就会变成「谁觉得慢就把阈值调大」——那样基线就不再是事实，
 * 而是一份人情记录。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/duration-baselines")
public class DurationBaselineController {

    private final DurationBaselineService service;
    private final AccessControlService access;

    public DurationBaselineController(DurationBaselineService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<CaseDurationBaseline>> list(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.list(projectId));
    }

    /** 手工重算（定时任务每日也会跑一次）。返回各计数值，便于核对。 */
    @PostMapping("/recompute")
    public ApiResponse<DurationBaselineService.RecalcResult> recompute(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.recompute(projectId));
    }

    /** 判定单次耗时是 NORMAL / SLOW / SUSPICIOUS_FAST / UNKNOWN。 */
    @GetMapping("/judge")
    public ApiResponse<DurationBaselineService.DurationVerdict> judge(
            @PathVariable long projectId,
            @RequestParam long caseId,
            @RequestParam(defaultValue = "robot") String kernel,
            @RequestParam long durationMs,
            @RequestParam(defaultValue = "PASS") String verdict) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.judge(projectId, caseId, kernel, durationMs, verdict));
    }

    /** 建议硬超时（秒）：软超时阈值取整到 5 秒，不低于 30 秒。 */
    @GetMapping("/suggest")
    public ApiResponse<Long> suggest(@PathVariable long projectId,
                                     @RequestParam long caseId,
                                     @RequestParam(defaultValue = "robot") String kernel) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.suggestedTimeoutSeconds(projectId, caseId, kernel));
    }
}
