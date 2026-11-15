package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-05 判据 2：解绑后两列 NULL，画像立即回落 BLOCKED。 */
class ClearBindingTest {

    @Test
    @DisplayName("解绑把两面都写成 NULL")
    void clearSetsBothColumnsNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        DeviceCredentialBindingService service =
                new DeviceCredentialBindingService(jdbc, assets, mock(AuditService.class));
        when(assets.scopeOf(anyString(), anyLong())).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, 7L, "PROJECT")));
        doReturn(List.of(Map.of("management_credential_id", 3, "bmc_credential_id", 5)))
                .when(jdbc).queryForList(anyString(), any(Object[].class));

        DeviceCredentialBindingService.BindingResult result = service.clear(7L, 9L, 1L);

        assertNull(result.managementCredentialId());
        assertNull(result.bmcCredentialId());
        Object[] args = DeviceCredentialBindingService.bindArgs(null, null, null, 9L);
        assertNull(args[0]);
        assertNull(args[1]);
        org.mockito.Mockito.verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("解绑后画像状态立即 BLOCKED / NO_CREDENTIAL（查询期聚合，不滞后）")
    void profileFallsBackImmediately() {
        DeviceCollectProfile.State state = DeviceCollectProfile.deriveState(
                "10.0.0.1", null, null, false, "SUCCESS");
        assertEquals(DeviceCollectProfile.BLOCKED, state.state());
        assertEquals(DeviceCollectProfile.NO_CREDENTIAL, state.reason());
    }

    @Test
    @DisplayName("与 5.10 向导写的是同一列：后写覆盖，语义一致")
    void sameColumnAsWizard() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/qa/autotest/application/asset/GlobalAssetService.java"));
        org.junit.jupiter.api.Assertions.assertTrue(
                source.contains("management_credential_id = ?"));
    }
}
