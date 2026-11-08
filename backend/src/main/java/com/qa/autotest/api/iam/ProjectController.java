package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.ProjectService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目与成员（IAM-01 / IAM-03）。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record CreateProjectRequest(String code, String name, String description,
                                       String visibility, String testKernel) {
    }

    public record UpdateProjectRequest(String name, String description, Boolean enabled,
                                       Long ownerId, String visibility, String testKernel) {
    }

    public record MemberRequest(Long userId, String roleCode) {
    }

    public record RoleRequest(String roleCode) {
    }

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    public ApiResponse<List<Project>> list() {
        return ApiResponse.ok(projects.visible());
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> get(@PathVariable long projectId) {
        return ApiResponse.ok(projects.get(projectId));
    }

    @PostMapping
    public ApiResponse<Project> create(@RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projects.create(request.code(), request.name(),
                request.description(), request.visibility(), request.testKernel()));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<Project> update(@PathVariable long projectId,
                                       @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projects.update(projectId, request.name(), request.description(),
                request.enabled() == null || request.enabled(), request.ownerId(),
                request.visibility(), request.testKernel()));
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
        return ApiResponse.ok(projects.addMember(projectId, request.userId(), request.roleCode()));
    }

    @PutMapping("/{projectId}/members/{userId}")
    public ApiResponse<ProjectMember> updateMember(@PathVariable long projectId,
                                                   @PathVariable long userId,
                                                   @RequestBody RoleRequest request) {
        return ApiResponse.ok(projects.updateMemberRole(projectId, userId, request.roleCode()));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable long projectId,
                                          @PathVariable long userId) {
        projects.removeMember(projectId, userId);
        return ApiResponse.ok(null);
    }
}
