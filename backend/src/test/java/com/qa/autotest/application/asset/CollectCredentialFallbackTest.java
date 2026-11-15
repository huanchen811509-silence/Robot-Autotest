package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-03：回退链、显式优先、两者都未绑 400 点名、安全闸不回退。 */
class CollectCredentialFallbackTest {

    private JdbcTemplate jdbc;
    private HostInventoryService hostInventory;
    private CredentialVerificationService verifications;
    private GlobalAssetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        hostInventory = mock(HostInventoryService.class);
        verifications = mock(CredentialVerificationService.class);
        service = new GlobalAssetService(jdbc, mock(AccessControlService.class),
                mock(AuditService.class), mock(ProjectAssetRepository.class),
                mock(PlatformProjectService.class), hostInventory,
                mock(SecretCipher.class), new ObjectMapper(), verifications);
        when(hostInventory.collect(anyLong(), anyString(), any(), any(), any(), any(),
                anyBoolean())).thenReturn(new HostInventoryRecord(1L, 0L, 9L, "h",
                Instant.now(), "OK", "{}", "hash", 1, 0, null));
    }

    private void device(String ip, String bmcAddress, Long mgmtCred, Long bmcCred) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 9L);
        row.put("name", "gpu-01");
        if (ip != null) row.put("management_ip", ip);
        if (bmcAddress != null) row.put("bmc_address", bmcAddress);
        if (mgmtCred != null) row.put("management_credential_id", mgmtCred);
        if (bmcCred != null) row.put("bmc_credential_id", bmcCred);
        doReturn(List.of(row)).when(jdbc).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("不传 credentialId → 用管理面绑定凭证，验证事实记的也是它")
    void usesManagementBinding() {
        device("10.0.0.1", null, 3L, null);

        service.collect(9L, null, null, true);

        verify(hostInventory).collect(anyLong(), eq("10.0.0.1"), any(), any(), any(),
                eq(3L), eq(true));
        verify(verifications).recordCollectResult(eq(9L), eq(3L), eq("MGMT"),
                eq(true), any());
    }

    @Test
    @DisplayName("只绑 BMC 且无管理 IP → 用 BMC 地址 + BMC 凭证，channel=BMC")
    void fallsBackToBmc() {
        device(null, "192.168.1.5", null, 5L);

        service.collect(9L, null, null, true);

        verify(hostInventory).collect(anyLong(), eq("192.168.1.5"), any(), any(), any(),
                eq(5L), eq(true));
        verify(verifications).recordCollectResult(eq(9L), eq(5L), eq("BMC"),
                eq(true), any());
    }

    @Test
    @DisplayName("两者都未绑 → 400，信息含「未绑定凭证」与设备标识")
    void noBindingReturns400() {
        device("10.0.0.1", null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.collect(9L, null, null, true));

        assertTrue(ex.getMessage().contains("未绑定凭证"));
        assertTrue(ex.getMessage().contains("gpu-01"));
        assertTrue(ex.getMessage().contains("9"));
        verify(hostInventory, never()).collect(anyLong(), anyString(), any(), any(), any(),
                any(), anyBoolean());
    }

    @Test
    @DisplayName("显式传 credentialId 优先于绑定，不被绑定覆盖")
    void explicitCredentialWins() {
        device("10.0.0.1", null, 3L, null);

        service.collect(9L, 7L, null, true);

        verify(hostInventory).collect(anyLong(), anyString(), any(), any(), any(),
                eq(7L), anyBoolean());
        verify(verifications).recordCollectResult(eq(9L), eq(7L), eq("MGMT"),
                eq(true), any());
    }

    @Test
    @DisplayName("安全闸不回退：confirmed=false 一律 400，不读绑定、不触发采集")
    void confirmedFalseIsRejected() {
        device("10.0.0.1", null, 3L, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.collect(9L, null, null, false));

        assertTrue(ex.getMessage().contains("二次确认"));
        verify(hostInventory, never()).collect(anyLong(), anyString(), any(), any(), any(),
                any(), anyBoolean());
        verify(verifications, never()).recordCollectResult(anyLong(), anyLong(), anyString(),
                anyBoolean(), any());
    }

    @Test
    @DisplayName("都未绑 + 未确认：报二次确认而不是「未绑定凭证」（不借回退泄漏绑定状态）")
    void gateDoesNotLeakBindingState() {
        device("10.0.0.1", null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.collect(9L, null, null, false));

        assertTrue(ex.getMessage().contains("二次确认"));
    }
}
