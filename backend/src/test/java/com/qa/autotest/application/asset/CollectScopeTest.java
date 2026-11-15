package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-v5.13-09 / NFR-v5.13-06：采集端点不再只认 PLATFORM，且跨项目凭证被点名拒绝。 */
class CollectScopeTest {

    private static final long PLATFORM_ID = 1L;

    @Test
    @DisplayName("代码机检：collect() 的 SQL 不再带 owner_scope='PLATFORM' 限定")
    void collectSqlNoLongerPlatformOnly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/qa/autotest/application/asset/GlobalAssetService.java"));
        int start = source.indexOf("public CollectResult collect(");
        int end = source.indexOf("private void autoBindIfNull", start);
        String body = source.substring(start, end);
        assertTrue(!body.contains("owner_scope = 'PLATFORM'"));
        assertTrue(body.contains("assertDeviceVisible"));
        assertTrue(body.contains("assertCredentialVisible"));
    }

    @Test
    @DisplayName("项目私有设备 → 本项目可见")
    void privateDeviceVisible() {
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        when(assets.scopeOf("DEVICE", 9L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, 7L, "PROJECT")));

        assertDoesNotThrow(() -> DeviceCredentialBindingService.assertDeviceVisible(
                assets, 7L, 9L, PLATFORM_ID));
    }

    @Test
    @DisplayName("别的项目私有设备 → 404")
    void otherProjectDeviceRejected() {
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        when(assets.scopeOf("DEVICE", 9L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, 8L, "PROJECT")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> DeviceCredentialBindingService.assertDeviceVisible(
                        assets, 7L, 9L, PLATFORM_ID));
        assertTrue(ex.getMessage().contains("不在本项目可见范围"));
    }

    @Test
    @DisplayName("平台设备已授权到项目 → 可见；未授权 → 404")
    void platformDeviceNeedsReference() {
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        when(assets.scopeOf("DEVICE", 9L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, null, "PLATFORM")));

        when(assets.referencedBy(7L, "DEVICE", 9L)).thenReturn(true);
        assertDoesNotThrow(() -> DeviceCredentialBindingService.assertDeviceVisible(
                assets, 7L, 9L, PLATFORM_ID));

        when(assets.referencedBy(7L, "DEVICE", 9L)).thenReturn(false);
        assertThrows(BusinessException.class,
                () -> DeviceCredentialBindingService.assertDeviceVisible(
                        assets, 7L, 9L, PLATFORM_ID));
    }

    @Test
    @DisplayName("跨项目私有凭证 → 400 点名；平台项目自己恒可见")
    void crossProjectCredentialRejected() {
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        when(assets.scopeOf("CREDENTIAL", 3L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(3L, 8L, "PROJECT")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> DeviceCredentialBindingService.assertCredentialVisible(
                        assets, 7L, 3L, PLATFORM_ID));
        assertTrue(ex.getMessage().contains("凭证不属于本项目"));

        when(assets.scopeOf("CREDENTIAL", 3L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(3L, null, "PLATFORM")));
        assertDoesNotThrow(() -> DeviceCredentialBindingService.assertCredentialVisible(
                assets, PLATFORM_ID, 3L, PLATFORM_ID));
    }

    @Test
    @DisplayName("平台项目自身的平台设备恒可见（全局资产页口径）")
    void platformProjectSeesPlatformDevices() {
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        when(assets.scopeOf("DEVICE", 9L)).thenReturn(
                Optional.of(new ProjectAssetRepository.AssetScope(9L, null, "PLATFORM")));

        assertDoesNotThrow(() -> DeviceCredentialBindingService.assertDeviceVisible(
                assets, PLATFORM_ID, 9L, PLATFORM_ID));
    }
}
