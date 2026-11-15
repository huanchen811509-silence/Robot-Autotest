package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-05：手工关联写库、越权点名、解绑、审计、自动填空单一 SQL。 */
class CredentialBindingTest {

    private static final long PLATFORM_ID = 1L;

    private JdbcTemplate jdbc;
    private ProjectAssetRepository assets;
    private AuditService audit;
    private DeviceCredentialBindingService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        assets = mock(ProjectAssetRepository.class);
        audit = mock(AuditService.class);
        service = new DeviceCredentialBindingService(jdbc, assets, audit);
        when(assets.scopeOf(eq("DEVICE"), anyLong())).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, 7L, "PROJECT")));
        doReturn(List.of(Map.of("management_credential_id", 0, "bmc_credential_id", 0)))
                .when(jdbc).queryForList(anyString(), any(Object[].class));
    }

    private void credentialScope(long id, Long projectId, String ownerScope) {
        when(assets.scopeOf(eq("CREDENTIAL"), eq(id)))
                .thenReturn(Optional.of(new ProjectAssetRepository.AssetScope(
                        id, projectId, ownerScope)));
    }

    @Test
    @DisplayName("项目侧关联：两列都写库，审计含 who / device / credential")
    void projectBindWritesBothColumns() {
        credentialScope(3L, 7L, "PROJECT");
        credentialScope(5L, 7L, "PROJECT");

        DeviceCredentialBindingService.BindingResult result = service.bind(7L, 9L, 3L, 5L, PLATFORM_ID);

        assertEquals(3L, result.managementCredentialId());
        assertEquals(5L, result.bmcCredentialId());
        verify(jdbc).update(argThat(sql -> sql.contains("management_credential_id = ?")
                && sql.contains("bmc_credential_id = ?")), any(Object[].class));
        verify(audit).recordChange(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("null = 这一面不动，0 = 清空")
    void nullMeansUntouchedZeroMeansClear() {
        DeviceCredentialBindingService.BindingResult result = service.bind(7L, 9L, 0L, null, PLATFORM_ID);

        assertNull(result.managementCredentialId());
        Object[] args = DeviceCredentialBindingService.bindArgs(null, 0, null, 9L);
        assertNull(args[0]);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("越权凭证 id → 400 点名，不静默成功")
    void crossProjectCredentialIsRejected() {
        when(assets.scopeOf(eq("CREDENTIAL"), org.mockito.ArgumentMatchers.eq(99L)))
                .thenReturn(Optional.of(new ProjectAssetRepository.AssetScope(
                        99L, 8L, "PROJECT")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bind(7L, 9L, 99L, null, PLATFORM_ID));

        assertTrue(ex.getMessage().contains("凭证不属于本项目"));
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    @DisplayName("平台设备未被本项目引用 → 404，不能跨项目采集/关联")
    void unreferencedPlatformDeviceIsRejected() {
        when(assets.scopeOf(eq("DEVICE"), anyLong())).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, null, "PLATFORM")));
        when(assets.referencedBy(7L, "DEVICE", 9L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bind(7L, 9L, null, null, PLATFORM_ID));

        assertTrue(ex.getMessage().contains("不在本项目可见范围"));
    }

    @Test
    @DisplayName("平台设备已授权到本项目 → 可见，关联成功")
    void referencedPlatformDeviceIsVisible() {
        when(assets.scopeOf(eq("DEVICE"), anyLong())).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, null, "PLATFORM")));
        when(assets.referencedBy(7L, "DEVICE", 9L)).thenReturn(true);

        DeviceCredentialBindingService.BindingResult result = service.bind(7L, 9L, null, null, PLATFORM_ID);

        assertEquals(9L, result.deviceId());
    }

    @Test
    @DisplayName("自动填空 SQL 带 IS NULL；手工绑定 SQL 不带（允许显式覆盖）")
    void fillIfNullNeverOverwrites() {
        assertTrue(DeviceCredentialBindingService.SQL_FILL_IF_NULL
                .contains("management_credential_id IS NULL"));
        assertTrue(!DeviceCredentialBindingService.SQL_BIND.contains("IS NULL"));

        service.fillIfNull(9L, 3L);
        verify(jdbc).update(argThat(sql -> sql.contains("IS NULL")), any(Object[].class));
    }
}
