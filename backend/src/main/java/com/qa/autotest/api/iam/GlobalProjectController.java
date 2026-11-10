package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.Project;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局模式项目列表（v4.5.0 / FR-v4.5-05）。
 *
 * <p>ADMIN / PM 可进入；PM 对未加入项目只读。这里只提供读接口，
 * 写操作仍走项目内接口（{@code /api/projects/**}），由项目成员关系把关。
 */
@RestController
@RequestMapping("/api/global/projects")
public class GlobalProjectController {

    /** own：我创建；joined：我加入；readonly：可读不可写（PM 未加入的项目）。 */
    public record GlobalProject(Project project, boolean own, boolean joined, boolean readonly) {
    }

    private final ProjectRepository projects;
    private final AccessControlService access;
    private final PlatformProjectService platform;

    public GlobalProjectController(ProjectRepository projects, AccessControlService access,
                                   PlatformProjectService platform) {
        this.projects = projects;
        this.access = access;
        this.platform = platform;
    }

    @GetMapping
    public ApiResponse<PageResult<GlobalProject>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        access.requireGlobalEnter();
        long userId = TenantContext.requireUserId();
        boolean admin = access.isAdmin(userId);
        PageResult<Project> result = projects.pageGlobal(keyword, false,
                Math.max(1, page), Math.min(Math.max(1, size), 100));
        List<GlobalProject> rows = result.items().stream().map(project -> {
            boolean own = project.ownerId() != null && project.ownerId() == userId;
            var member = projects.findMember(project.id(), userId);
            boolean joined = member.isPresent();
            boolean canWrite = admin || member.map(item -> item.roleCodes().stream()
                    .anyMatch(code -> code.equals("admin") || code.equals("pm"))).orElse(false);
            return new GlobalProject(project, own, joined, !canWrite);
        }).toList();
        return ApiResponse.ok(new PageResult<>(rows, result.total(), result.page(), result.size()));
    }

    /** 全局只读详情：PM 未加入项目也可看，但不返回成员等敏感明细。 */
    @GetMapping("/{projectId}")
    public ApiResponse<Project> get(@PathVariable long projectId) {
        access.requireGlobalEnter();
        if (projects.platformProject().map(Project::id).orElse(-1L) == projectId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台项目不提供全局详情");
        }
        return ApiResponse.ok(projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在")));
    }

    /** 平台项目 id（前端公共库页面用；不返回平台项目本体）。 */
    @GetMapping("/platform-id")
    public ApiResponse<Long> platformId() {
        access.requireGlobalEnter();
        return ApiResponse.ok(platform.id());
    }
}
