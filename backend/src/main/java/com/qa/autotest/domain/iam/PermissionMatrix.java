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
     *
     * <p>v5.19.0 / W69-02 追加 {@code execution:read}：前端 5 个执行态叶子
     * （执行计划 / 运行记录 / 测试计划 / 执行节点 / 执行内核）依赖它，而白名单里**从未定义**，
     * 于是对所有角色恒隐藏（含 admin）——主链路第一跳就断，用户只能手输 URL。
     * 授予口径与 {@link #MATRIX} 的 {@code EXECUTION:READ} 一致：ADMIN / PM / ENGINEER 有，VIEWER 无。
     *
     * <p>v5.19.0 / W69-02 同时补 {@code project:create}：前端「新建项目」入口用的就是这个能力码，
     * 而后端白名单里也没有它——同一次契约机检把两处一起抓出来，避免只修一个再犯一次。
     */
    public static final List<String> MENU_CAPABILITIES = List.of(
            "settings:read", "ai:config", "user:write", "audit:read", "about", "changelog",
            "case:write", "execution:read", "project:create",
            // v5.21.0 / FR-v5.21-02 / W71-03：导航改**白名单制**（每个叶子必须声明
            // capability 或 public），于是「只读」这一档必须有码可用。原先只读页复用的是
            // `settings:read`——等于「能看设置就能看一切」，与「能力码要能解释自己」相悖。
            // 这 5 个码的授予与 {@link #MATRIX} 的 READ 动作完全同源，不新增权限，只是把
            // 已有的读权限**命名**出来。（注释里不要出现半角双引号，机检按字面量抓。）
            "project:read", "case:read", "asset:read", "report:read", "env:read",
            // v5.21.0 / FR-v5.21-03 / W71-05：写操作也要有码可判。
            // `v-can` 指令按能力码决定「这个按钮要不要渲染」，而 `case:write` 之外
            // 两类高频写操作原先没有码——发起执行（EXECUTION:CREATE）与成员管理（PROJECT:UPDATE）。
            // 没有码就只能用 read 码冒充，等于「能看就能写」。这两个码与 {@link #MATRIX} 的动作同源。
            "execution:write", "project:write");

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
     * 无全局角色账号的**最小能力集**（v5.21.0 / FR-v5.21-02 / W71-03）。
     *
     * <p>两个反例都不能要：返回全量＝放大权限；返回空集＝前端当"未加载"，
     * 侧栏空白且没有任何解释。最小集选「关于 / 版本迭代」——这两页是公开信息，
     * 不碰任何业务数据，同时让用户至少能看到"我是谁、这个系统什么版本"。
     */
    public static List<String> minimalMenuCapabilities() {
        return List.of("about", "changelog");
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
        // v5.21.0 / W71-03：5 个只读码按 MATRIX 的 READ 动作授予——
        // ADMIN/PM/ENGINEER/VIEWER 四角色都有（VIEWER 本来就是只读角色）。
        // 注意 VIEWER **仍然没有** `execution:read`：执行态入口（发起/运行/计划/节点/内核）
        // 属 v5.19 FR-02 的授予口径，本版不动。VIEWER 能看到「报告态」三页（report:read）。
        java.util.List<String> readOnly = List.of(
                "project:read", "case:read", "asset:read", "report:read", "env:read");
        // v5.21.0 / W71-05：写能力的授予与 MATRIX 同源——EXECUTION:CREATE 三角色有（VIEWER 无），
        // PROJECT:UPDATE 只有 ADMIN/PM（ENGINEER 是只读项目、不能改成员）。
        java.util.List<String> execWrite = List.of("execution:write");
        java.util.List<String> projWrite = List.of("project:write");
        matrix.put(RoleCode.ADMIN, new LinkedHashSet<>(concat(concat(List.of(
                "settings:read", "ai:config", "user:write", "audit:read", "about", "changelog",
                "case:write", "execution:read", "project:create"), readOnly),
                concat(execWrite, projWrite))));
        matrix.put(RoleCode.PM, new LinkedHashSet<>(concat(concat(List.of(
                "settings:read", "audit:read", "about", "changelog", "case:write",
                "execution:read", "project:create"), readOnly),
                concat(execWrite, projWrite))));
        matrix.put(RoleCode.ENGINEER, new LinkedHashSet<>(concat(concat(List.of(
                "settings:read", "about", "changelog", "case:write", "execution:read"),
                readOnly), execWrite)));
        matrix.put(RoleCode.VIEWER, new LinkedHashSet<>(concat(List.of(
                "settings:read", "about", "changelog"), readOnly)));
        return matrix;
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head.size() + tail.size());
        all.addAll(head);
        all.addAll(tail);
        return all;
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

        // 测试工程师：读项目、资产增改、凭据使用、用例全、导入执行；
        // 不能进入全局模式；同步只允许预览（READ）。
        //
        // v5.9.0 / FR-v5.9-04 / W59-18：**移除 USER_ROLE:READ**。
        // 原先 ENGINEER 能 `GET /api/users` 拿到全平台账号列表——「用户与权限」独立成管理域后，
        // 这与「ENGINEER 访问 /g/iam/users 应 403」直接矛盾（后端放行、前端拦住＝两套判据）。
        // 读自己的信息走 `/api/me`（MeController 不要求 USER_ROLE），不受影响；
        // 看项目成员走 `/api/projects/{id}/members`，也不受影响。
        matrix.put(RoleCode.ENGINEER, Map.of(
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
