package com.qa.autotest.api.group;

import com.qa.autotest.application.execution.RunExecutionService;
import com.qa.autotest.application.group.EffectiveRoleResolver;
import com.qa.autotest.application.group.GroupService;
import com.qa.autotest.application.group.ScenarioService;
import com.qa.autotest.application.group.SuperAdminViewService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 组授权 / 场景 / 超管视图 API（W5）。 */
@RestController
@RequestMapping("/api")
public class GroupController {

    private final GroupService groups;
    private final ScenarioService scenarios;
    private final SuperAdminViewService adminView;
    private final EffectiveRoleResolver resolver;
    private final AccessControlService access;
    /** SCEN-03：复用既有执行域的 run 创建链路，不重写执行引擎。 */
    private final RunExecutionService executions;

    public GroupController(GroupService groups, ScenarioService scenarios,
                           SuperAdminViewService adminView, EffectiveRoleResolver resolver,
                           AccessControlService access, RunExecutionService executions) {
        this.groups = groups;
        this.scenarios = scenarios;
        this.adminView = adminView;
        this.resolver = resolver;
        this.access = access;
        this.executions = executions;
    }

    @GetMapping("/groups")
    public ApiResponse<?> groups() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可管理用户组");
        }
        return ApiResponse.ok(groups.groups());
    }

    @PostMapping("/groups")
    public ApiResponse<Long> createGroup(@RequestBody GroupRequest request) {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可管理用户组");
        }
        return ApiResponse.ok(groups.createGroup(request.name(), request.description()));
    }

    @PostMapping("/groups/member")
    public ApiResponse<?> addMember(@RequestBody MemberRequest request) {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可管理用户组");
        }
        groups.addMember(request.groupId(), request.userId());
        return ApiResponse.ok(groups.members(request.groupId()));
    }

    @PostMapping("/groups/grant")
    public ApiResponse<?> grant(@RequestBody GrantRequest request) {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可授权");
        }
        groups.grant(request.projectId(), request.groupId(), request.roleCode());
        return ApiResponse.ok(groups.effective(request.projectId(), request.userId()));
    }

    @GetMapping("/groups/effective")
    public ApiResponse<?> effective(@RequestParam long projectId, @RequestParam long userId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(Map.of("role", resolver.resolve(userId, projectId).name()));
    }

    @PostMapping("/scenarios")
    public ApiResponse<Long> createScenario(@RequestBody ScenarioRequest request) {
        access.requireProjectAccess(request.projectId());
        return ApiResponse.ok(scenarios.createScenario(
                request.projectId(), request.name(), request.caseSetIds()));
    }

    @GetMapping("/scenarios")
    public ApiResponse<?> scenarios(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(Map.of("items", scenarios.scenarios(projectId),
                "oneLevelViolations", scenarios.assertOneLevel(projectId)));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<?> overview() {
        return ApiResponse.ok(adminView.overview());
    }

    /**
     * SCEN-03：按场景触发一次执行（补齐 404）。
     *
     * <p>最小改动：只经既有 {@code RunExecutionService.executeScenario} 落库一个独立 run，
     * 返回含 runId 的 ApiResponse；不派发 pabot、不重写执行引擎（完整编排仍在既有链路）。
     * runRequestId 留空，每次调用 RunService 生成新 UUID，故连续两次触发得到 2 个独立 RUN_ID。
     */
    @PostMapping("/scenarios/{id}/execute")
    public ApiResponse<?> executeScenario(@PathVariable long id,
                                          @RequestParam(required = false) Long projectId) {
        Long bound = TenantContext.projectId();
        long pid = projectId != null && projectId > 0
                ? projectId
                : (bound == null ? 0L : bound);
        if (pid <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "无法确定项目：请用 ?projectId= 指定，或绑定 X-Project-Id 头");
        }
        RunRecord created = executions.executeScenario(pid, id);
        return ApiResponse.ok(Map.<String, Object>of(
                "runId", created.runId(),
                "scenarioId", id,
                "projectId", pid,
                "state", created.state().name()));
    }

    public record GroupRequest(String name, String description) {
    }

    public record MemberRequest(long groupId, long userId) {
    }

    public record GrantRequest(long projectId, long groupId, String roleCode, long userId) {
    }

    public record ScenarioRequest(long projectId, String name, List<Long> caseSetIds) {
    }
}
