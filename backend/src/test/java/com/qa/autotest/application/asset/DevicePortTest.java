package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.12.0 / W62-13：端口删除的「引用闸门」。
 *
 * <p>后端早有 deletePort，但直接删被链路引用的端口会让外键异常冒成 500；
 * 前端又一直没有删入口。这里锁住：删之前先问「谁引用着」，被引用就 409 点名链路，
 * 且真实 SQL 必须带 project_id 并过 TenantGuard（不能跨项目查别人的链路名）。
 */
class DevicePortTest {

    private static final long PROJECT = 7L;
    private static final long DEVICE = 11L;
    private static final long PORT = 88L;

    private DeviceRepository devices;
    private AuditService audit;
    private DeviceService service;

    @BeforeEach
    void setUp() {
        devices = mock(DeviceRepository.class);
        audit = mock(AuditService.class);
        service = new DeviceService(devices, mock(ProjectAssetRepository.class),
                mock(AccessControlService.class), audit);
        TenantContext.set(1L, "tester", null);
        when(devices.findPort(PROJECT, PORT)).thenReturn(Optional.of(port()));
        when(devices.find(PROJECT, DEVICE)).thenReturn(Optional.of(device()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DevicePort port() {
        return new DevicePort(PORT, DEVICE, "eth0", "ETH", "roce", null, 200, 9000,
                "10.0.0.2", "UP", null, 0);
    }

    private static Device device() {
        return new Device(DEVICE, PROJECT, "leaf-01", "switch", "model", "vendor",
                "SN-1", "10.0.0.1", "lab", "rack1", "net", true, null, null);
    }

    @Test
    @DisplayName("端口被链路引用 → 409 且点名链路，绝不落到外键 500")
    void referencedPortReturnsConflictNamingLinks() {
        when(devices.linkNamesByPort(PROJECT, PORT))
                .thenReturn(List.of("上联-A", "上联-B"));

        assertThatThrownBy(() -> service.deletePort(PROJECT, PORT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.deletePort(PROJECT, PORT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上联-A")
                .hasMessageContaining("上联-B")
                .hasMessageContaining("请先解除引用");
        verify(devices, never()).deletePort(PROJECT, PORT);
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("未引用的端口照常删除并留审计")
    void unreferencedPortIsDeleted() {
        when(devices.linkNamesByPort(PROJECT, PORT)).thenReturn(List.of());

        service.deletePort(PROJECT, PORT);

        verify(devices).deletePort(PROJECT, PORT);
        verify(audit).record(eq("device.port-delete"), eq("device_port"),
                eq(String.valueOf(PORT)), eq(""));
    }

    @Test
    @DisplayName("端口不存在 → 404，不再往下查引用")
    void missingPortIs404() {
        when(devices.findPort(PROJECT, PORT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePort(PROJECT, PORT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(devices, never()).deletePort(PROJECT, PORT);
    }

    @Test
    @DisplayName("真实 SQL 按项目收口：link 表、project_id、TenantGuard 三者齐全")
    void jdbcImplementationIsProjectScoped() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/qa/autotest/infrastructure/DeviceRepositoryJdbc.java"));
        String block = source.substring(source.indexOf("linkNamesByPort"));
        String method = block.substring(0, Math.min(block.length(), 900));
        assertThat(method).contains("FROM link")
                .contains("l.project_id = ?")
                .contains("a_port_id")
                .contains("b_port_id")
                .contains("TenantGuard.requireProjectScope");
    }

    @Test
    @DisplayName("仓储 default 空实现保留，既有测试桩零改动")
    void repositoryDefaultKeepsExistingStubsWorking() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/qa/autotest/application/repository/DeviceRepository.java"));
        assertThat(source).contains("default List<String> linkNamesByPort")
                .contains("return List.of();");
    }
}
