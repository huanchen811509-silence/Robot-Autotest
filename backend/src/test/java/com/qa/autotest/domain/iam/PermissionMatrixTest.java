package com.qa.autotest.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 权限矩阵单测（PRD §3.1，IAM-03；v4.4.0 起含全局/模板/同步资源）。 */
class PermissionMatrixTest {

    /** 资源按 {@link PermissionResource} 声明顺序排列，与 {@link #EXPECTED} 的字符串逐位对应。 */
    private static final List<PermissionResource> RESOURCES = List.of(PermissionResource.values());

    /** 动作按 {@link PermissionAction} 声明顺序。 */
    private static final List<PermissionAction> ACTIONS = List.of(PermissionAction.values());

    /**
     * 全量期望表：每个角色一行，按资源顺序给 11 个 11 字符串，位序同 {@link #ACTIONS}，
     * {@code '-'} 表示拒绝。
     *
     * <p>这张表是**独立于实现**的 PRD 抄录，不是从 {@code PermissionMatrix} 反推的；
     * 改矩阵必须同步改这里，否则全量用例会指出具体是哪一格变了。
     */
    private static final Map<RoleCode, List<String>> EXPECTED = Map.of(
            RoleCode.ADMIN, List.of(
                    "RCUDESEIEMP", "RCUDESEIEMP", "RCUDESEIEMP", "RCUDESEIEMP",
                    "RCUDESEIEMP", "RCUDESEIEMP", "RCUDESEIEMP", "RCUDESEIEMP",
                    "RCUDESEIEMP", "RCUDESEIEMP", "RCUDESEIEMP"),
            RoleCode.PM, List.of(
                    "R----------", // 用户/角色：只读
                    "RCU--------", // 项目：读 + 建 + 改自己
                    "RCUDES-----", // 设备/端口/拓扑
                    "RCUDES-----", // 凭据
                    "RCUDES-----", // 用例/用例集
                    "RCUDES-----", // 执行
                    "----E--I---", // 导入：执行 + 批量导入
                    "R----------", // 审计：只读
                    "R-U---E----", // 全局：读 + 写 + 进入
                    "RCUD-----M-", // 模板：读/建/改/删/治理（不可提升 public）
                    "R---E------"), // 同步：预览 + 执行
            RoleCode.ENGINEER, List.of(
                    "R----------", // 用户/角色：只读（仅自己由服务层细化）
                    "R----------", // 项目：只读
                    "RCU--------", // 设备/端口/拓扑：读 / 建 / 改，不可删
                    "-----S-----", // 凭据：只可使用，不可读清单
                    "RCUDES-----", // 用例/用例集
                    "RCU-E------", // 执行：读 / 建 / 改 / 执行，不可删
                    "----E------", // 导入：只执行
                    "-----------", // 审计：无
                    "-----------", // 全局：不可进入
                    "-----------", // 模板：不渲染
                    "R----------"), // 同步：只允许预览
            RoleCode.VIEWER, List.of(
                    "-----------", "R----------", "R----------", "-----------",
                    "R----------", "R----------", "-----------", "-----------",
                    "-----------", "-----------", "-----------"));

    @Test
    @DisplayName("全量权限矩阵：4 角色 × 11 资源 × 11 动作 = 484 格逐格断言")
    void 全量矩阵逐格断言() {
        int checked = 0;
        for (RoleCode role : RoleCode.values()) {
            List<String> row = EXPECTED.get(role);
            assertEquals(RESOURCES.size(), row.size(), role + " 的期望表必须覆盖全部资源");
            for (int r = 0; r < RESOURCES.size(); r++) {
                String flags = row.get(r);
                assertEquals(ACTIONS.size(), flags.length(), role + ":" + RESOURCES.get(r) + " 位数不对");
                for (int a = 0; a < ACTIONS.size(); a++) {
                    boolean want = flags.charAt(a) != '-';
                    PermissionResource resource = RESOURCES.get(r);
                    PermissionAction action = ACTIONS.get(a);
                    assertEquals(want, PermissionMatrix.allows(role, resource, action),
                            role + " " + resource + ":" + action + " 期望 " + (want ? "允许" : "拒绝"));
                    checked++;
                }
            }
        }
        assertEquals(RoleCode.values().length * RESOURCES.size() * ACTIONS.size(), checked,
                "必须遍历全部角色 × 资源 × 动作，不允许抽样");
        assertEquals(484, checked);
    }

    @Test
    @DisplayName("管理员拥有全部资源全部动作")
    void 管理员全权() {
        for (PermissionResource resource : PermissionResource.values()) {
            for (PermissionAction action : PermissionAction.values()) {
                assertTrue(PermissionMatrix.allows(RoleCode.ADMIN, resource, action),
                        "管理员应允许 " + resource + ":" + action);
            }
        }
    }

    @Test
    @DisplayName("访客只读，不能写任何资源，也不能进入全局模式")
    void 访客只读() {
        assertTrue(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.CASE,
                PermissionAction.READ));
        assertFalse(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.CASE,
                PermissionAction.CREATE));
        assertFalse(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.CREDENTIAL,
                PermissionAction.READ));
        assertFalse(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.GLOBAL,
                PermissionAction.ENTER));
    }

    @Test
    @DisplayName("工程师可用凭据但不能读凭据清单、不能删资产、不能进全局")
    void 工程师边界() {
        assertTrue(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.CREDENTIAL,
                PermissionAction.USE));
        assertFalse(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.CREDENTIAL,
                PermissionAction.READ));
        assertFalse(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.ASSET,
                PermissionAction.DELETE));
        assertFalse(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.GLOBAL,
                PermissionAction.ENTER));
        assertTrue(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.SYNC,
                PermissionAction.READ));
        assertFalse(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.SYNC,
                PermissionAction.EXECUTE));
    }

    @Test
    @DisplayName("项目经理不能删用户、但可建项目/进全局/治理模板/执行同步")
    void 项目经理边界() {
        assertFalse(PermissionMatrix.allows(RoleCode.PM, PermissionResource.USER_ROLE,
                PermissionAction.DELETE));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.PROJECT,
                PermissionAction.CREATE));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.IMPORT,
                PermissionAction.EXECUTE));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.GLOBAL,
                PermissionAction.ENTER));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.TEMPLATE,
                PermissionAction.MANAGE));
        assertFalse(PermissionMatrix.allows(RoleCode.PM, PermissionResource.TEMPLATE,
                PermissionAction.PROMOTE));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.SYNC,
                PermissionAction.EXECUTE));
    }

    @Test
    @DisplayName("多角色并集求值：任一角色允许即允许（FR-v4.4-02）")
    void 多角色并集() {
        assertTrue(PermissionMatrix.allows(List.of(RoleCode.ENGINEER, RoleCode.VIEWER),
                PermissionResource.CASE, PermissionAction.CREATE));
        assertTrue(PermissionMatrix.allows(List.of(RoleCode.VIEWER, RoleCode.PM),
                PermissionResource.GLOBAL, PermissionAction.ENTER));
        assertFalse(PermissionMatrix.allows(List.of(RoleCode.VIEWER),
                PermissionResource.GLOBAL, PermissionAction.ENTER));
        assertFalse(PermissionMatrix.allows(List.of(), PermissionResource.CASE,
                PermissionAction.READ));
    }

    @Test
    @DisplayName("空角色一律拒绝")
    void 空角色拒绝() {
        assertFalse(PermissionMatrix.allows((RoleCode) null, PermissionResource.CASE,
                PermissionAction.READ));
    }

    @Test
    @DisplayName("执行域权限：工程师可建可取消，访客只读")
    void 执行域权限() {
        assertTrue(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.EXECUTION,
                PermissionAction.CREATE));
        assertTrue(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.EXECUTION,
                PermissionAction.UPDATE));
        assertFalse(PermissionMatrix.allows(RoleCode.ENGINEER, PermissionResource.EXECUTION,
                PermissionAction.DELETE));
        assertTrue(PermissionMatrix.allows(RoleCode.PM, PermissionResource.EXECUTION,
                PermissionAction.DELETE));
        assertTrue(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.EXECUTION,
                PermissionAction.READ));
        assertFalse(PermissionMatrix.allows(RoleCode.VIEWER, PermissionResource.EXECUTION,
                PermissionAction.CREATE));
    }

    // ---------------------------------------------------------------- MENU 维度（OPT-08 / FR-49-31）

    /**
     * MENU 期望表：写死 PRD 口径，<b>不调用生产矩阵推导</b>。
     * settings:read 四角色全有；ai:config/user:write 仅 ADMIN；audit:read ADMIN+PM；
     * about/changelog public（四角色全有）。
     */
    private static final Map<RoleCode, Set<String>> MENU_EXPECTED = menuExpected();

    private static Map<RoleCode, Set<String>> menuExpected() {
        Map<RoleCode, Set<String>> matrix = new EnumMap<>(RoleCode.class);
        matrix.put(RoleCode.ADMIN, Set.of(
                "settings:read", "ai:config", "user:write", "audit:read", "about", "changelog"));
        matrix.put(RoleCode.PM, Set.of(
                "settings:read", "audit:read", "about", "changelog"));
        matrix.put(RoleCode.ENGINEER, Set.of(
                "settings:read", "about", "changelog"));
        matrix.put(RoleCode.VIEWER, Set.of(
                "settings:read", "about", "changelog"));
        return matrix;
    }

    static Stream<Arguments> menuCells() {
        List<Arguments> cells = new ArrayList<>();
        for (RoleCode role : List.of(RoleCode.ADMIN, RoleCode.PM, RoleCode.ENGINEER, RoleCode.VIEWER)) {
            for (String capability : PermissionMatrix.MENU_CAPABILITIES) {
                cells.add(Arguments.of(role, capability, MENU_EXPECTED.get(role).contains(capability)));
            }
        }
        return cells.stream();
    }

    @Test
    @DisplayName("MENU 矩阵维度：4 角色 × 6 能力 = 24 格")
    void menu矩阵维度() {
        assertThat(MENU_EXPECTED.keySet()).hasSize(4);
        assertThat(PermissionMatrix.MENU_CAPABILITIES).hasSize(6);
        assertThat(menuCells().count()).isEqualTo(24);
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @MethodSource("menuCells")
    @DisplayName("24 格逐格断言：menuAllows 与 menuCapabilities 口径一致")
    void menu逐格断言(RoleCode role, String capability, boolean expected) {
        assertThat(PermissionMatrix.menuAllows(role, capability))
                .as("menuAllows %s / %s", role, capability).isEqualTo(expected);
        assertThat(PermissionMatrix.menuCapabilities(role).contains(capability))
                .as("menuCapabilities %s / %s", role, capability).isEqualTo(expected);
    }

    @Test
    @DisplayName("public 能力：about / changelog / settings:read 四角色全有")
    void menuPublic能力() {
        for (RoleCode role : RoleCode.values()) {
            assertThat(PermissionMatrix.menuCapabilities(role))
                    .as("%s", role).contains("about", "changelog", "settings:read");
        }
    }

    @Test
    @DisplayName("能力移除后 ADMIN 不再含 ai:config（供负向验证注入）")
    void menu能力移除负向验证() {
        assertThat(PermissionMatrix.menuCapabilities(RoleCode.ADMIN)).contains("ai:config");
        assertThat(PermissionMatrix.removeMenuCapability(RoleCode.ADMIN, "ai:config")).isTrue();
        assertThat(PermissionMatrix.menuAllows(RoleCode.ADMIN, "ai:config")).isFalse();
        assertThat(PermissionMatrix.menuCapabilities(RoleCode.ADMIN)).doesNotContain("ai:config");
    }

    @AfterEach
    void restoreMenuMatrix() {
        PermissionMatrix.restoreMenuCapabilities();
    }
}
