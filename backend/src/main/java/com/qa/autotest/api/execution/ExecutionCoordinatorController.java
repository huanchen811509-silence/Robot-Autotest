package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.ExecutionWindow;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.execution.ProjectRunCoordinator;
import com.qa.autotest.application.execution.RunSplitter;
import com.qa.autotest.application.execution.RunVerdictPolicy;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.execution.RunRecord;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目执行协调（FR-v5.2-03 / W52-04~06）。
 *
 * <p>四个只读/决策端点 + 一个提交端点。把「准入 → 拆单预览 → 提交 → 台账」拆开暴露，
 * 是为了让页面能在**提交之前**就把「会拆成几单、现在能不能跑」摆给用户看——
 * 先让用户看见代价，再让他点确认。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/execution-coordination")
public class ExecutionCoordinatorController {

    private final ProjectRunCoordinator coordinator;
    private final AccessControlService access;

    public ExecutionCoordinatorController(ProjectRunCoordinator coordinator,
                                          AccessControlService access) {
        this.coordinator = coordinator;
        this.access = access;
    }

    /** 当前是否允许发起（执行窗口 + 独占）。 */
    @GetMapping("/admission")
    public ApiResponse<ProjectRunCoordinator.Admission> admission(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(coordinator.admit(projectId));
    }

    /**
     * 长跑判定（W53-08）：按用例列表估算总耗时，判断是否属长跑。
     *
     * <p>给「发起前先看一眼」用：用户可以据此决定现在跑还是排到夜间窗口。
     * {@code caseCodes} 为空时按空列表算（返回 0 秒、非长跑），不是一个错误——
     * 页面刚打开、还没选用例时就是这种状态。
     *
     * @param kernel 内核；同一批用例在 RF 与 pytest 下耗时不同，不传按 robot 估
     */
    @GetMapping("/long-run")
    public ApiResponse<ProjectRunCoordinator.LongRunCheck> longRun(
            @PathVariable long projectId,
            @RequestParam(required = false) String caseCodes,
            @RequestParam(defaultValue = "robot") String kernel) {
        access.requireProjectAccess(projectId);
        List<RunSplitter.PlannedCase> cases = new ArrayList<>();
        if (caseCodes != null && !caseCodes.isBlank()) {
            for (String code : caseCodes.split(",")) {
                if (!code.isBlank()) {
                    cases.add(new RunSplitter.PlannedCase(code.trim(), kernel, null, 0));
                }
            }
        }
        return ApiResponse.ok(coordinator.longRunOf(projectId, cases));
    }

    /** 窗口配置（给配置页展示与校验用）。 */
    @GetMapping("/window")
    public ApiResponse<WindowView> window(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        ExecutionWindow window = coordinator.windowOf(projectId);
        return ApiResponse.ok(new WindowView(window.describe(), window.timeBounded(),
                window.exclusive(), window.nextOpening()));
    }

    /** 今日台账。 */
    @GetMapping("/ledger")
    public ApiResponse<ProjectRunCoordinator.Ledger> ledger(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(coordinator.ledger(projectId));
    }

    /** 拆单预览（不落库）。 */
    @PostMapping("/split")
    public ApiResponse<ProjectRunCoordinator.SplitPlan> split(
            @PathVariable long projectId,
            @RequestBody List<RunSplitter.PlannedCase> cases) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(coordinator.plan(projectId, cases));
    }

    /** 提交执行：拆单 + 逐单建 run。 */
    @PostMapping("/submit")
    public ApiResponse<ProjectRunCoordinator.SubmitResult> submit(
            @PathVariable long projectId,
            @RequestBody ProjectRunCoordinator.SubmitRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(coordinator.submit(projectId, request));
    }

    /** 失败裁决（页面给它当前分类与计数，拿到 retry/stop/handover）。 */
    @PostMapping("/verdict")
    public ApiResponse<RunVerdictPolicy.Verdict> verdict(
            @PathVariable long projectId,
            @RequestBody ProjectRunCoordinator.FailureContext context) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(coordinator.judge(projectId, context));
    }

    /** 窗口视图。 */
    public record WindowView(String description, boolean timeBounded, boolean exclusive,
                             String nextOpening) {
    }
}
