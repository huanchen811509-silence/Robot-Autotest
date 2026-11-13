package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.CreateProjectFromTemplateRequest;
import com.qa.autotest.application.iam.ProjectService;
import com.qa.autotest.application.iam.ProjectTemplateService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目与成员（IAM-01 / IAM-03）。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    /** 建项目入参。{@code kind} 可选：空值由 {@code ProjectKind.of} 回落 standard，非法值抛 400（不静默回落）。 */
    public record CreateProjectRequest(String code, String name, String description,
                                       String visibility, String testKernel, String kind) {
    }

    public record UpdateProjectRequest(String name, String description, Boolean enabled,
                                       Long ownerId, String visibility, String testKernel) {
    }

    public record MemberRequest(Long userId, String roleCode, List<String> roleCodes) {
    }

    public record RoleRequest(String roleCode, List<String> roleCodes) {
    }

    public record BatchMemberRequest(List<MemberRequest> members) {
    }

    public record ChangeCodeRequest(String code, String reason) {
    }

    private final ProjectService projects;
    /** 可为 null：仅旧装配（单测 standaloneSetup）走一参构造，生产由 @Autowired 二参构造注入。 */
    private final ProjectTemplateService templates;

    public ProjectController(ProjectService projects) {
        this(projects, null);
    }

    @Autowired
    public ProjectController(ProjectService projects, ProjectTemplateService templates) {
        this.projects = projects;
        this.templates = templates;
    }

    @GetMapping
    public ApiResponse<List<Project>> list() {
        return ApiResponse.ok(projects.visible());
    }

    /** 服务端建议编码（FR-v4.4-07）。 */
    @GetMapping("/next-code")
    public ApiResponse<Map<String, String>> nextCode(
            @RequestParam(defaultValue = "PRJ") String prefix,
            @RequestParam(defaultValue = "robot") String kernel) {
        return ApiResponse.ok(Map.of("code", projects.nextCode(prefix, kernel)));
    }

    /** 前端能力码（只做显隐，后端仍逐请求判定）。 */
    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Boolean>> capabilities() {
        long userId = TenantContext.requireUserId();
        return ApiResponse.ok(Map.of("globalEnter", projects.canEnterGlobal(userId)));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> get(@PathVariable long projectId) {
        return ApiResponse.ok(projects.get(projectId));
    }

    @PostMapping
    public ApiResponse<Project> create(@RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projects.create(request.code(), request.name(),
                request.description(), request.visibility(), request.testKernel(),
                request.kind()));
    }

    /** 从模板建项目（OPT-06 / FR-49-22）：独立入参，不影响既有 POST /api/projects。 */
    @PostMapping("/from-template")
    public ApiResponse<Project> createFromTemplate(
            @RequestBody CreateProjectFromTemplateRequest request) {
        if (templates == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模板服务未装配");
        }
        return ApiResponse.ok(templates.createProjectFromTemplate(request));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<Project> update(@PathVariable long projectId,
                                       @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projects.update(projectId, request.name(), request.description(),
                request.enabled() == null || request.enabled(), request.ownerId(),
                request.visibility(), request.testKernel()));
    }

    /** 编码唯一一次变更（FR-v4.4-08）。 */
    @PutMapping("/{projectId}/code")
    public ApiResponse<Project> changeCode(@PathVariable long projectId,
                                           @RequestBody ChangeCodeRequest request) {
        return ApiResponse.ok(projects.changeCode(projectId, request.code(), request.reason()));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable long projectId) {
        projects.delete(projectId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<ProjectMember>> members(@PathVariable long projectId) {
        return ApiResponse.ok(projects.members(projectId));
    }

    @PostMapping("/{projectId}/members")
    public ApiResponse<ProjectMember> addMember(@PathVariable long projectId,
                                                @RequestBody MemberRequest request) {
        List<String> roles = request.roleCodes() != null && !request.roleCodes().isEmpty()
                ? request.roleCodes() : List.of(request.roleCode());
        return ApiResponse.ok(projects.addMember(projectId, request.userId(), roles));
    }

    /** 批量添加成员（FR-v4.4-03）：逐人失败原因返回，不整体回滚。 */
    @PostMapping("/{projectId}/members/batch")
    public ApiResponse<ProjectService.BatchMembersResult> addMembers(
            @PathVariable long projectId, @RequestBody BatchMemberRequest request) {
        List<ProjectService.MemberInput> inputs = request.members() == null ? List.of()
                : request.members().stream()
                        .map(item -> new ProjectService.MemberInput(item.userId(),
                                item.roleCode(), item.roleCodes()))
                        .toList();
        return ApiResponse.ok(projects.addMembers(projectId, inputs));
    }

    @PutMapping("/{projectId}/members/{userId}")
    public ApiResponse<ProjectMember> updateMember(@PathVariable long projectId,
                                                   @PathVariable long userId,
                                                   @RequestBody RoleRequest request) {
        List<String> roles = request.roleCodes() != null && !request.roleCodes().isEmpty()
                ? request.roleCodes() : List.of(request.roleCode());
        return ApiResponse.ok(projects.updateMemberRoles(projectId, userId, roles));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable long projectId,
                                          @PathVariable long userId) {
        projects.removeMember(projectId, userId);
        return ApiResponse.ok(null);
    }
}
