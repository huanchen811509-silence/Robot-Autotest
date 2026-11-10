package com.qa.autotest.domain.iam;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色 × 资源 × 动作 的权限矩阵（PRD §3.1，本版定稿）。
 *
 * <p>纯函数：给定角色与资源动作返回是否允许。接口层与服务层都查同一张表，
 * 避免「注解允许、服务层又拒」的两套口径。v4.4.0 起支持<b>角色集合求值</b>：
 * 有效权限 = 全局角色权限 ∪ 项目角色权限（并集，取最大允许）。
 */
public final class PermissionMatrix {

    private static final Map<RoleCode, Map<PermissionResource, Set<PermissionAction>>> MATRIX =
            build();

    private PermissionMatrix() {
    }

    /** 是否允许（单角色）。 */
    public static boolean allows(RoleCode role, PermissionResource resource, PermissionAction action) {
        if (role == null) {
            return false;
        }
        Set<PermissionAction> actions = MATRIX.getOrDefault(role,
                        Map.<PermissionResource, Set<PermissionAction>>of())
                .getOrDefault(resource, Set.of());
        return actions.contains(action);
    }

    /** 是否允许（多角色并集，FR-v4.4-02）。 */
    public static boolean allows(Collection<RoleCode> roles, PermissionResource resource,
                                 PermissionAction action) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        for (RoleCode role : roles) {
            if (allows(role, resource, action)) {
                return true;
            }
        }
        return false;
    }

    private static Set<PermissionAction> set(PermissionAction... actions) {
        Set<PermissionAction> result = EnumSet.noneOf(PermissionAction.class);
        java.util.Collections.addAll(result, actions);
        return result;
    }

    private static Map<RoleCode, Map<PermissionResource, Set<PermissionAction>>> build() {
        Map<RoleCode, Map<PermissionResource, Set<PermissionAction>>> matrix = new EnumMap<>(RoleCode.class);

        // 管理员：全部资源全部动作
        Map<PermissionResource, Set<PermissionAction>> admin = new EnumMap<>(PermissionResource.class);
        for (PermissionResource resource : PermissionResource.values()) {
            admin.put(resource, EnumSet.allOf(PermissionAction.class));
        }
        matrix.put(RoleCode.ADMIN, admin);

        // 项目经理：用户读、项目可建可改自己、资产与凭据全、用例全、导入执行、审计读；
        // v4.4.0 起可进入全局模式（全局只读、自己项目可写），可治理团队模板与执行同步；
        // v4.6.0 起可导入/导出公共用例与资产。公共库编辑（CASE:UPDATE）仍由服务层限定 ADMIN。
        matrix.put(RoleCode.PM, Map.ofEntries(
                Map.entry(PermissionResource.USER_ROLE, set(PermissionAction.READ)),
                Map.entry(PermissionResource.PROJECT, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE)),
                Map.entry(PermissionResource.ASSET, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.EXECUTE,
                        PermissionAction.USE)),
                Map.entry(PermissionResource.CREDENTIAL, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.EXECUTE,
                        PermissionAction.USE)),
                Map.entry(PermissionResource.CASE, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.EXECUTE,
                        PermissionAction.USE)),
                Map.entry(PermissionResource.EXECUTION, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.EXECUTE,
                        PermissionAction.USE)),
                Map.entry(PermissionResource.IMPORT, set(PermissionAction.EXECUTE,
                        PermissionAction.IMPORT)),
                Map.entry(PermissionResource.AUDIT, set(PermissionAction.READ)),
                Map.entry(PermissionResource.GLOBAL, set(PermissionAction.ENTER,
                        PermissionAction.READ, PermissionAction.UPDATE)),
                Map.entry(PermissionResource.TEMPLATE, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.MANAGE)),
                Map.entry(PermissionResource.SYNC, set(PermissionAction.READ,
                        PermissionAction.EXECUTE))));

        // 测试工程师：读用户（仅自己由服务层细化）、读项目、资产增改、凭据使用、用例全、导入执行；
        // 不能进入全局模式；同步只允许预览（READ）。
        matrix.put(RoleCode.ENGINEER, Map.of(
                PermissionResource.USER_ROLE, set(PermissionAction.READ),
                PermissionResource.PROJECT, set(PermissionAction.READ),
                PermissionResource.ASSET, set(PermissionAction.READ, PermissionAction.CREATE,
                        PermissionAction.UPDATE),
                PermissionResource.CREDENTIAL, set(PermissionAction.USE),
                PermissionResource.CASE, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE,
                        PermissionAction.DELETE, PermissionAction.EXECUTE,
                        PermissionAction.USE),
                PermissionResource.EXECUTION, set(PermissionAction.READ,
                        PermissionAction.CREATE, PermissionAction.UPDATE, PermissionAction.EXECUTE),
                PermissionResource.IMPORT, set(PermissionAction.EXECUTE),
                PermissionResource.SYNC, set(PermissionAction.READ)));

        // 访客：只读
        matrix.put(RoleCode.VIEWER, Map.of(
                PermissionResource.PROJECT, set(PermissionAction.READ),
                PermissionResource.ASSET, set(PermissionAction.READ),
                PermissionResource.CASE, set(PermissionAction.READ),
                PermissionResource.EXECUTION, set(PermissionAction.READ)));

        return matrix;
    }
}
