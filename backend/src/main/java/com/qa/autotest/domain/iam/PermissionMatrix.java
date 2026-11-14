package com.qa.autotest.domain.iam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * 菜单 capability 维度（v4.9.2 / OPT-08 / FR-49-31~34）：4 角色 × 6 能力。
     *
     * <p>口径：{@code settings:read} 四角色全有；{@code ai:config} / {@code user:write} 仅 ADMIN；
     * {@code audit:read} ADMIN + PM；{@code about} / {@code changelog} public（四角色全有）。
     * 前端导航只按 capability 做显隐，<b>真正的越权仍由 {@code AccessControlService.requireCapability}
     * 在接口层 403 兜底</b>。本映射刻意做成可变副本：单测需要「移除能力」做负向注入验证。
     */
    private static Map<RoleCode, Set<String>> MENU_MATRIX = buildMenuMatrix();

    /**
     * 菜单能力固定顺序，服务端下发能力数组时按此顺序，避免前端拿到散序集合。
     *
     * <p>v5.7.0 / 复核 P1-1 追加 {@code case:write}：前端「平台用例集能不能写」原先按
     * {@code roles.includes('admin')} 判，而服务端用的是 {@code CASE:UPDATE}——两套口径
     * 导致 PM 有写权限却看不到入口。能力码与资源矩阵保持单一真源：见 {@link #menuAllows}。
     */
    public static final List<String> MENU_CAPABILITIES = List.of(
            "settings:read", "ai:config", "user:write", "audit:read", "about", "changelog",
            "case:write");

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

    // ------------------------------------------------------------ 菜单能力（MENU 维度）

    /** 单角色是否具备某菜单能力；public 能力（about/changelog）对四角色一律放行。 */
    public static boolean menuAllows(RoleCode role, String capability) {
        if (role == null || capability == null) {
            return false;
        }
        // case:write 不落 MENU_MATRIX：它必须与「写平台用例集」的那道闸完全同源，
        // 否则前端的显隐和后端的 403 迟早会分叉。
        if ("case:write".equals(capability)) {
            return allows(role, PermissionResource.CASE, PermissionAction.UPDATE);
        }
        return MENU_MATRIX.getOrDefault(role, Set.of()).contains(capability);
    }

    /** 按 {@link #MENU_CAPABILITIES} 固定顺序输出该角色的能力数组（防御性副本）。 */
    public static List<String> menuCapabilities(RoleCode role) {
        List<String> result = new ArrayList<>();
        for (String capability : MENU_CAPABILITIES) {
            if (menuAllows(role, capability)) {
                result.add(capability);
            }
        }
        return result;
    }

    /**
     * 负向验证注入口（供单测）：移除某角色的某个能力，返回是否命中。
     * 生产不会调用；调用后 {@code menuCapabilities} 立即不再返回该能力。
     */
    public static boolean removeMenuCapability(RoleCode role, String capability) {
        Set<String> caps = MENU_MATRIX.get(role);
        return caps != null && caps.remove(capability);
    }

    /** 恢复为初始矩阵（单测 {@code @AfterEach} 清理，避免静态状态污染后续用例）。 */
    public static void restoreMenuCapabilities() {
        MENU_MATRIX = buildMenuMatrix();
    }

    private static Map<RoleCode, Set<String>> buildMenuMatrix() {
        Map<RoleCode, Set<String>> matrix = new EnumMap<>(RoleCode.class);
        matrix.put(RoleCode.ADMIN, new LinkedHashSet<>(List.of(
                "settings:read", "ai:config", "user:write", "audit:read", "about", "changelog")));
        matrix.put(RoleCode.PM, new LinkedHashSet<>(List.of(
                "settings:read", "audit:read", "about", "changelog")));
        matrix.put(RoleCode.ENGINEER, new LinkedHashSet<>(List.of(
                "settings:read", "about", "changelog")));
        matrix.put(RoleCode.VIEWER, new LinkedHashSet<>(List.of(
                "settings:read", "about", "changelog")));
        return matrix;
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
