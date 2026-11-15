package com.qa.autotest.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.common.tenant.TenantGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.13.0 W7 真实 HTTP 探针抓到的缺陷回归：
 * 平台项目自身没有 project_asset(DEVICE) 引用，旧 SQL 让全局画像恒返回空列表。
 */
class DeviceCollectProfilePlatformScopeTest {

    @Test
    @DisplayName("平台态用 PLATFORM_SQL，项目态仍用 BASE_SQL")
    void scopeSelectsSql() {
        assertEquals(DeviceCollectProfileRepositoryJdbc.PLATFORM_SQL,
                DeviceCollectProfileRepositoryJdbc.sqlFor(88L, 88L));
        assertEquals(DeviceCollectProfileRepositoryJdbc.BASE_SQL,
                DeviceCollectProfileRepositoryJdbc.sqlFor(95L, 88L));
        assertFalse(DeviceCollectProfileRepositoryJdbc.sqlFor(95L, 88L)
                .contains("OR d.owner_scope = 'PLATFORM'"));
    }

    @Test
    @DisplayName("平台态分支让公共资产恒可见，且 SELECT 部分与项目态同源")
    void platformBranchSeesSharedAssets() {
        String base = DeviceCollectProfileRepositoryJdbc.BASE_SQL;
        String platform = DeviceCollectProfileRepositoryJdbc.PLATFORM_SQL;
        assertTrue(platform.contains("OR d.owner_scope = 'PLATFORM'"));
        assertTrue(platform.contains("d.project_id = ?"));
        assertTrue(base.startsWith(DeviceCollectProfileRepositoryJdbc.SELECT_SQL));
        assertTrue(platform.startsWith(DeviceCollectProfileRepositoryJdbc.SELECT_SQL));
        assertEquals(base.replace(AssetScopeSql.DEVICE_WHERE, ""),
                platform.replace(AssetScopeSql.DEVICE_WHERE_PLATFORM, ""));
    }

    @Test
    @DisplayName("可见性公式仍是单一真值：平台 WHERE = 项目 WHERE + 一个 OR 分支")
    void singleSourceOfTruth() {
        String base = AssetScopeSql.DEVICE_WHERE;
        String expected = base.substring(0, base.length() - 1)
                + " OR d.owner_scope = 'PLATFORM')";
        assertEquals(expected, AssetScopeSql.DEVICE_WHERE_PLATFORM);
        assertTrue(AssetScopeSql.DEVICE_WHERE_PLATFORM
                .contains("d.project_id = ? OR (d.owner_scope = 'PLATFORM' AND pa.id IS NOT NULL)"));
    }

    @Test
    @DisplayName("平台态 SQL 仍过 TenantGuard（不能靠放宽租户换可见性）")
    void platformSqlKeepsTenantGuard() {
        assertDoesNotThrow(() -> TenantGuard.requireProjectScope(
                DeviceCollectProfileRepositoryJdbc.PLATFORM_SQL, "device"));
    }
}
