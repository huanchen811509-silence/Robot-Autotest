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

    /** 当前用户的有效角色集合：全局角色 ∪ 项目角色（含组授权），去重，非法值忽略。 */
    public List<RoleCode> effectiveRoles(long userId, Long projectId) {
        Set<RoleCode> roles = new LinkedHashSet<>();
        roles.addAll(parse(users.roleCodesOf(userId)));
        if (projectId != null) {
            // v5.9.0 / FR-v5.9-06：项目角色改读三源并集（成员 ∪ 负责人 ∪ 用户组授权）。
            // 旧实现只读 project_member_role，组授权因此在判权里完全失效。
            roles.addAll(parse(projects.effectiveRoleCodes(projectId, userId)));
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

    /**
     * 当前登录用户的菜单能力集（v4.9.2 / OPT-08 / FR-49-32）：服务端按最高优先级全局角色
     * 求值，不信任前端传来的能力表；前端导航只按此数组做显隐。
     *
     * <p>v5.21.0 / FR-v5.21-02 / W71-03：**无全局角色时返回最小集**。
     * 旧实现走到这里返回空数组——前端把空数组当"还没加载完"，于是侧栏一个字都不显示，
     * 用户看到的是一个没有任何入口的页面（v5.21 调研 F-21-07）。现在明确给最小集：
     * 只读「关于 / 版本迭代」，并且**不放大**（既不是全量，也不是空集）。
     */
    public List<String> currentMenuCapabilities() {
        long userId = TenantContext.requireUserId();
        RoleCode role = effectiveRole(userId, null);
        if (role == null) {
            return PermissionMatrix.minimalMenuCapabilities();
        }
        return PermissionMatrix.menuCapabilities(role);
    }

    /**
     * 菜单能力兜底（FR-49-33）：敏感设置页接口在真正读数据前调用本方法，
     * 不具备对应 capability 的角色一律 403，不能只靠前端把菜单藏起来。
     * 未知 / 空 capability 视为参数错误（400），不静默放行。
     */
    public void requireCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 capability");
        }
        String normalized = capability.trim();
        if (!PermissionMatrix.MENU_CAPABILITIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未知 capability：" + normalized);
        }
        long userId = TenantContext.requireUserId();
        if (!PermissionMatrix.menuAllows(effectiveRole(userId, null), normalized)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限：" + normalized);
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
     * 项目资源级校验：管理员跨项目放行（设计如此），其他人必须三源之一（成员/负责人/组授权）。
     * <b>返回 403 而不是 404</b>，且消息不含项目是否存在的信息，防止枚举探测。
     *
     * <p>v5.9.0 / FR-v5.9-04：判据与 {@link #visibleProjects()} 同一份 SQL 片段。
     * 旧实现只认 {@code project_member}，于是「自己建的项目 / 组授权进来的项目」
     * 在列表里能看到（如果列表口径松）却在接口上 403——两处口径必须同源。
     */
    public void requireProjectAccess(long projectId) {
        long userId = TenantContext.requireUserId();
        if (isAdmin(userId)) {
            return;
        }
        if (!projects.isVisibleTo(projectId, userId)) {
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
