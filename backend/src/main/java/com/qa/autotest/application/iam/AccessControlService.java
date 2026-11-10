package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionMatrix;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.iam.RoleCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 权限判定与项目访问校验（IAM-03 / IAM-04 的应用层实现）。
 *
 * <p>两层口径：接口注解做粗粒度拦截；这里做资源级校验（项目是否属于当前用户），
 * 同一接口被跨项目调用时只有这一层能拦住。
 *
 * <p>v4.4.0 / FR-v4.4-02：有效权限 = 全局角色权限 ∪ 项目角色权限（并集）。
 */
@Service
public class AccessControlService {

    private static final List<RoleCode> PRIORITY =
            List.of(RoleCode.ADMIN, RoleCode.PM, RoleCode.ENGINEER, RoleCode.VIEWER);

    private final UserRepository users;
    private final ProjectRepository projects;

    public AccessControlService(UserRepository users, ProjectRepository projects) {
        this.users = users;
        this.projects = projects;
    }

    /** 当前请求用户是否是全局管理员。 */
    public boolean isAdmin() {
        return isAdmin(TenantContext.requireUserId());
    }

    public boolean isAdmin(long userId) {
        return users.roleCodesOf(userId).stream()
                .anyMatch(code -> code.equalsIgnoreCase(RoleCode.ADMIN.code()));
    }

    /** 当前用户的有效角色集合：全局角色 ∪ 项目角色（去重，非法值忽略）。 */
    public List<RoleCode> effectiveRoles(long userId, Long projectId) {
        Set<RoleCode> roles = new LinkedHashSet<>();
        roles.addAll(parse(users.roleCodesOf(userId)));
        if (projectId != null) {
            projects.findMember(projectId, userId)
                    .ifPresent(member -> roles.addAll(parse(member.roleCodes())));
        }
        return new ArrayList<>(roles);
    }

    private static List<RoleCode> parse(List<String> codes) {
        List<RoleCode> parsed = new ArrayList<>();
        if (codes == null) {
            return parsed;
        }
        for (String code : codes) {
            try {
                parsed.add(RoleCode.from(code));
            } catch (IllegalArgumentException ignored) {
                // 脏数据不放大权限：未知角色直接忽略
            }
        }
        return parsed;
    }

    /** 当前用户在指定项目上的展示角色：全局管理员优先，其次按项目/全局角色优先级取最高。 */
    public RoleCode effectiveRole(long userId, Long projectId) {
        List<RoleCode> roles = effectiveRoles(userId, projectId);
        return roles.stream()
                .min(Comparator.comparingInt(PRIORITY::indexOf))
                .orElse(null);
    }

    /** 按当前上下文（用户 + 项目）判权。 */
    public void require(PermissionResource resource, PermissionAction action) {
        long userId = TenantContext.requireUserId();
        Long projectId = TenantContext.projectId();
        List<RoleCode> roles = effectiveRoles(userId, projectId);
        if (!PermissionMatrix.allows(roles, resource, action)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "无权限：" + resource.name() + ":" + action.name());
        }
    }

    /** 是否可进入全局模式（ADMIN / PM）。 */
    public boolean canEnterGlobal(long userId) {
        return PermissionMatrix.allows(effectiveRoles(userId, null),
                PermissionResource.GLOBAL, PermissionAction.ENTER);
    }

    /** 全局模式入口校验：无权限统一 403，不泄露全局资源是否存在。 */
    public void requireGlobalEnter() {
        long userId = TenantContext.requireUserId();
        if (!canEnterGlobal(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问全局模式");
        }
    }

    /**
     * 项目资源级校验：管理员跨项目放行（设计如此），其他人必须是项目成员。
     * <b>返回 403 而不是 404</b>，且消息不含项目是否存在的信息，防止枚举探测。
     */
    public void requireProjectAccess(long projectId) {
        long userId = TenantContext.requireUserId();
        if (isAdmin(userId)) {
            return;
        }
        Optional<Project> project = projects.findById(projectId);
        if (project.isEmpty() || !projects.findMember(projectId, userId).isPresent()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该项目");
        }
    }

    /**
     * 绑定路径上的项目到当前请求上下文（项目内接口统一走这里）。
     *
     * <p>路径参数是权威来源，不依赖请求头 {@code X-Project-Id}；非成员一律 403。
     */
    public void bindProject(long projectId) {
        requireProjectAccess(projectId);
        TenantContext.setProjectId(projectId);
    }

    /** 当前用户可见的项目。 */
    public List<Project> visibleProjects() {
        long userId = TenantContext.requireUserId();
        if (isAdmin(userId)) {
            return projects.page(null, 1, 1000).items();
        }
        return projects.findByMember(userId);
    }
}
