package com.qa.autotest.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.asset.DeviceCollectProfile;
import com.qa.autotest.common.tenant.TenantGuard;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-v5.13-01 判据 3/5：最新一条取法、租户收口、零新增表、零泄漏。 */
class DeviceCollectProfileRepositoryJdbcTest {

    private static final String SQL = DeviceCollectProfileRepositoryJdbc.BASE_SQL;

    @Test
    @DisplayName("聚合只读既有三张事实源，SQL 带 project_id 且过 TenantGuard")
    void sqlHasTenantScope() {
        String lower = SQL.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("d.project_id"));
        assertTrue(lower.contains("d.management_credential_id"));
        assertTrue(lower.contains("d.bmc_credential_id"));
        assertTrue(lower.contains("credential_verification"));
        assertTrue(lower.contains("device_host_spec"));
        assertTrue(lower.contains("left join project_asset pa"));
        assertDoesNotThrow(() -> TenantGuard.requireProjectScope(SQL, "device"));
    }

    @Test
    @DisplayName("最新验证：时间倒序 + 同刻取 id 大")
    void latestVerificationOrder() {
        assertTrue(SQL.contains("ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1"));
        assertTrue(SQL.contains("ORDER BY h.collected_at DESC, h.id DESC LIMIT 1"));
    }

    @Test
    @DisplayName("聚合 SQL 不得 select secret_ref / password 等明文列")
    void sqlNeverSelectsSecrets() {
        String lower = SQL.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("secret_ref"));
        assertFalse(lower.contains("password"));
        assertFalse(lower.contains("private_key"));
        assertFalse(lower.contains("insert into"));
        assertFalse(lower.contains("update "));
    }

    @Test
    @DisplayName("派生状态过滤是纯函数：大小写不敏感，空值不过滤")
    void stateFilterIsPure() {
        DeviceCollectProfile ready = DeviceCollectProfile.of(new DeviceCollectProfile.RawRow(
                1L, 1L, "PLATFORM", "a", "10.0.0.1", 3L, null, "rd", null,
                "active", "active", 3L, null, "SUCCESS", null, null, "OK", 1));
        DeviceCollectProfile partial = DeviceCollectProfile.of(new DeviceCollectProfile.RawRow(
                2L, 1L, "PLATFORM", "b", "10.0.0.1", 4L, null, "rd", null,
                "active", "active", null, null, null, null, null, null, 0));
        List<DeviceCollectProfile> rows = List.of(ready, partial);
        assertEquals(2, DeviceCollectProfileRepositoryJdbc.filterByState(rows, null).size());
        assertEquals(1, DeviceCollectProfileRepositoryJdbc.filterByState(rows, " partial ").size());
        assertEquals(DeviceCollectProfile.PARTIAL,
                DeviceCollectProfileRepositoryJdbc.filterByState(rows, "PARTIAL").get(0).profileState());
        assertEquals(0, DeviceCollectProfileRepositoryJdbc.filterByState(rows, "NO_SUCH_STATE").size());
    }
}
