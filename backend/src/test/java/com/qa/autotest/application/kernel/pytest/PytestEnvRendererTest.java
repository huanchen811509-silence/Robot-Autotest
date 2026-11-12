package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 环境渲染（W51-04 / FR-v5.1-02/03）。
 *
 * 用最简的假仓储（不引 Mockito），重点验证三件事：
 * ① 凭据只写 `${ENV:}` 引用；② 字段名与框架逐字对齐；③ 缺 os_family 时显式告警而不是静默补。
 */
class PytestEnvRendererTest {

    private static final long PROJECT = 7L;

    @Test
    void 渲染产物含凭据引用且无明文(@TempDir Path dir) throws Exception {
        PytestEnvRenderer.RenderResult result = renderer(List.of(), Map.of())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "fake", dir));

        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);
        String runEnv = Files.readString(result.runEnvFile(), StandardCharsets.UTF_8);

        assertThat(inventory).contains("${ENV:PYTEST_SSH_USER}");
        assertThat(inventory).contains("${ENV:PYTEST_SSH_PASSWORD}");
        assertThat(inventory).doesNotContain("Eht_lab2026");
        assertThat(runEnv).contains("inventory_file: inventory.yaml");
        assertThat(runEnv).contains("mode: fake");
        assertThat(result.inventorySha256()).hasSize(64);
        assertThat(result.runEnvSha256()).hasSize(64);
    }

    @Test
    void NIC字段名与框架逐字对齐(@TempDir Path dir) throws Exception {
        PytestEnvRenderer.RenderResult result = renderer(List.of(), Map.of())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "fake", dir));

        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);

        for (String field : List.of("netdev", "vendor", "manufacturer", "product_model",
                "form_factor", "pci_bdf", "vendor_id", "device_id", "driver", "firmware",
                "rdma_device", "port", "numa_node", "role", "capabilities",
                "counter_patterns")) {
            assertThat(inventory).as("NIC 字段 %s", field).contains(field + ":");
        }
        assertThat(inventory).contains("0000:31:00.0");
        assertThat(inventory).contains("product_model: ConnectX-7");
    }

    @Test
    void 缺osFamily时用显式默认并告警(@TempDir Path dir) throws Exception {
        PytestEnvRenderer.RenderResult result = renderer(List.of(), Map.of())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "fake", dir));

        assertThat(result.warnings()).anyMatch(w -> w.contains("未填 os_family"));
        assertThat(Files.readString(result.inventoryFile(), StandardCharsets.UTF_8))
                .contains("os_family: ubuntu");
    }

    @Test
    void 选中不存在的主机时拒绝渲染(@TempDir Path dir) {
        assertThatThrownBy(() -> renderer(List.of(), Map.of())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1",
                        List.of("ghost@10.0.0.9"), List.of(), "fake", dir)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 未选任何设备时拒绝渲染(@TempDir Path dir) {
        PytestEnvRenderer empty = new PytestEnvRenderer(new FakeDevices(List.of(), Map.of()),
                new FakeSettings(Map.of()));
        assertThatThrownBy(() -> empty.render(new PytestEnvRenderer.RenderRequest(
                PROJECT, "RUN-1", "fake", dir)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未选中任何设备");
    }

    @Test
    void runEnv的资源池来自选中设备(@TempDir Path dir) throws Exception {
        PytestEnvRenderer.RenderResult result = renderer(List.of(), Map.of())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "fake", dir));

        String runEnv = Files.readString(result.runEnvFile(), StandardCharsets.UTF_8);
        assertThat(runEnv).contains("gpu01@10.0.0.1");
        assertThat(runEnv).contains("nic:gpu01@10.0.0.1/ens17np0");
        assertThat(result.hostKeys()).containsExactly("gpu01@10.0.0.1");
        assertThat(result.nicKeys()).containsExactly("gpu01@10.0.0.1/ens17np0");
    }

    private static PytestEnvRenderer renderer(List<DevicePort> extra, Map<String, String> settings) {
        Device device = new Device(1L, PROJECT, "gpu01", "gpu", "NF5280", "inspur", "SN1",
                "10.0.0.1", null, null, "rdma", true, null, null,
                null, null, null, null, null);
        DevicePort port = new DevicePort(10L, 1L, "ens17np0", "eth", "ethernet", null, 400, 1500,
                null, "up", "ens17np0", 0, "nvidia", "NVIDIA", "ConnectX-7", "1x400G",
                "0000:31:00.0", "0x15b3", null, "mlx5_core", null, "mlx5_0", 1, 0, "rx-data",
                "[\"roce\",\"rdma_write\"]", "{}");
        List<DevicePort> ports = new java.util.ArrayList<>();
        ports.add(port);
        ports.addAll(extra);
        Map<Long, List<DevicePort>> byDevice = new LinkedHashMap<>();
        byDevice.put(1L, ports);
        return new PytestEnvRenderer(new FakeDevices(List.of(device), byDevice),
                new FakeSettings(settings));
    }

    /** 假设备仓储：只实现渲染用到的两个方法。 */
    private record FakeDevices(List<Device> devices, Map<Long, List<DevicePort>> ports)
            implements DeviceRepository {

        @Override
        public List<Device> listAll(long projectId) {
            return devices;
        }

        @Override
        public List<DevicePort> ports(long projectId, long deviceId) {
            return ports.getOrDefault(deviceId, List.of());
        }

        @Override
        public com.qa.autotest.domain.common.PageResult<Device> page(long projectId, String keyword,
                Integer status, String domain, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Device> find(long projectId, long id) {
            return devices.stream().filter(d -> d.id() == id).findFirst();
        }

        @Override
        public long insert(Device device, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(Device device, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long projectId, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean serialExists(long projectId, String serialNo, Long excludeId) {
            return false;
        }

        @Override
        public List<DevicePort> portsOfVisibleDevices(long projectId) {
            return List.of();
        }

        @Override
        public Optional<DevicePort> findPort(long projectId, long portId) {
            return Optional.empty();
        }

        @Override
        public long insertPort(long projectId, DevicePort port, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updatePort(long projectId, DevicePort port, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePort(long projectId, long portId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean portNameExists(long projectId, long deviceId, String portName,
                Long excludeId) {
            return false;
        }

        @Override
        public List<String> domains(long projectId) {
            return List.of();
        }
    }

    /** 假配置仓储。 */
    private record FakeSettings(Map<String, String> values) implements ProjectSettingRepository {

        @Override
        public Optional<String> find(long projectId, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, String> findAll(long projectId, String prefix) {
            return values;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(long projectId, String key) {
            throw new UnsupportedOperationException();
        }
    }
}
