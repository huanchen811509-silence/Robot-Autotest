package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-v5.13-01：画像三态、BLOCKED 原因枚举、掩码与「最新验证」口径。 */
class DeviceCollectProfileTest {

    private static DeviceCollectProfile.RawRow row(String ip, Long mgmt, Long bmc,
                                                   String mgmtStatus, String verify) {
        return new DeviceCollectProfile.RawRow(9L, 1L, "PLATFORM", "gpu-01", ip,
                mgmt, bmc, mgmt == null ? null : "rd-admin-01",
                bmc == null ? null : "bmc-admin-01", mgmtStatus, "active",
                3L, "2026-11-15 10:00:00", verify, null,
                "2026-11-15 11:00:00", "OK", 2);
    }

    @Test
    @DisplayName("D1 有 IP + 已绑 + 验证成功 → READY")
    void readyWhenVerified() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", 3L, null, "active", "SUCCESS"));
        assertEquals(DeviceCollectProfile.READY, profile.profileState());
        assertNull(profile.blockedReason());
        assertTrue(profile.usable());
        assertEquals(3L, profile.lastVerifiedCredentialId());
        assertEquals("2026-11-15 10:00:00", profile.lastVerifiedAt());
        assertEquals("OK", profile.lastCollectStatus());
        assertEquals(2, profile.collectCount());
    }

    @Test
    @DisplayName("D2 有 IP + 已绑 + 未验证 → PARTIAL（仍可用，只是没验证过）")
    void partialWhenNotVerified() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", 3L, null, "active", null));
        assertEquals(DeviceCollectProfile.PARTIAL, profile.profileState());
        assertNull(profile.blockedReason());
        assertTrue(profile.usable());
    }

    @Test
    @DisplayName("验证失败不得冒充 READY")
    void failedVerificationIsNotReady() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", 3L, null, "active", "FAILED"));
        assertEquals(DeviceCollectProfile.PARTIAL, profile.profileState());
    }

    @Test
    @DisplayName("D3 有 IP + 无任何绑定 → BLOCKED / NO_CREDENTIAL")
    void blockedWithoutCredential() {
        var state = DeviceCollectProfile.deriveState("10.0.0.1", null, null, false, null);
        assertEquals(DeviceCollectProfile.BLOCKED, state.state());
        assertEquals(DeviceCollectProfile.NO_CREDENTIAL, state.reason());
        assertFalse(state.usable());
    }

    @Test
    @DisplayName("D4 无管理 IP + 只绑 BMC → BLOCKED / NO_MANAGEMENT_IP（原因不互换）")
    void blockedWithoutManagementIp() {
        var state = DeviceCollectProfile.deriveState(null, null, 5L, false, "SUCCESS");
        assertEquals(DeviceCollectProfile.BLOCKED, state.state());
        assertEquals(DeviceCollectProfile.NO_MANAGEMENT_IP, state.reason());
        assertFalse(state.usable());
        // 两个缺失项同时存在时，先报缺 IP —— 先补 IP 才谈得上绑凭证
        assertEquals(DeviceCollectProfile.NO_MANAGEMENT_IP,
                DeviceCollectProfile.deriveState("", null, null, false, null).reason());
    }

    @Test
    @DisplayName("绑定凭证被标记 deprecated → BLOCKED / CREDENTIAL_REVOKED")
    void blockedWhenCredentialRevoked() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", 3L, null, "deprecated", "SUCCESS"));
        assertEquals(DeviceCollectProfile.BLOCKED, profile.profileState());
        assertEquals(DeviceCollectProfile.CREDENTIAL_REVOKED, profile.blockedReason());
        assertFalse(profile.usable());
    }

    @Test
    @DisplayName("只绑 BMC 但有管理 IP → PARTIAL，boundCredentialName 取 BMC 那条")
    void bmcOnlyBinding() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", null, 5L, "active", null));
        assertEquals(DeviceCollectProfile.PARTIAL, profile.profileState());
        assertEquals("bmc-admin-01", profile.boundCredentialName());
        assertEquals(5L, profile.bmcCredentialId());
    }

    @Test
    @DisplayName("掩码只留前两位；画像里不得带明文 secret 字段")
    void maskedNameNeverLeaksSecret() {
        DeviceCollectProfile profile = DeviceCollectProfile.of(
                row("10.0.0.1", 3L, null, "active", "SUCCESS"));
        assertEquals("rd***", profile.boundCredentialMasked());
        assertFalse(profile.boundCredentialMasked().contains("admin"));
        for (java.lang.reflect.RecordComponent component
                : DeviceCollectProfile.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("secret"), "画像字段不得含 secret：" + name);
            assertFalse(name.contains("password"), "画像字段不得含 password：" + name);
        }
    }

    @Test
    @DisplayName("result 枚举沿用 V11_64 的 SUCCESS/FAILED，大小写不敏感")
    void verifyEnumIsCaseInsensitive() {
        assertEquals(DeviceCollectProfile.READY,
                DeviceCollectProfile.deriveState("10.0.0.1", 3L, null, false, "success").state());
        assertEquals(DeviceCollectProfile.PARTIAL,
                DeviceCollectProfile.deriveState("10.0.0.1", 3L, null, false, "FAILED").state());
    }
}
