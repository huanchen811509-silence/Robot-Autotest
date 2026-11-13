package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.HostInventoryRepository;
import com.qa.autotest.domain.host.HostInventoryRecord;
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

    /**
     * FR-v5.6-41：GPU / CPU / 存储从**已采集的主机清单**派生，不另建资产表。
     *
     * <p>三条判据：清单里有的三类必须出现在 inventory；资源池必须按 {@code host/key}
     * 限定（框架对带主机的资源只认这种形式）；根分区与物理盘分开记。
     */
    @Test
    void GPU与CPU与存储从主机清单派生(@TempDir Path dir) throws Exception {
        String spec = """
                {"items":[
                  {"category":"GPU","label":"GPU","value":[
                    {"name":"NVIDIA A100-SXM4-80GB","memoryTotal":"81920 MiB",
                     "driverVersion":"535.129.03"}]},
                  {"category":"CPU","label":"CPU","value":
                    {"model":"Intel Xeon Gold 6338","cores":64}},
                  {"category":"DISK","label":"DISK","value":
                    {"root":{"size":"1.8T","used":"500G","available":"1.2T"},
                     "disks":[{"name":"nvme0n1","size":"1.8T","type":"SSD"}]}}
                ]}""";

        PytestEnvRenderer.RenderResult result = rendererWithAssets(spec)
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "real", dir));
        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);
        String runEnv = Files.readString(result.runEnvFile(), StandardCharsets.UTF_8);

        assertThat(inventory).contains("gpu0:").contains("NVIDIA A100-SXM4-80GB");
        assertThat(inventory).contains("memory_total:").contains("81920 MiB");
        assertThat(inventory).contains("cpu0:").contains("Intel Xeon Gold 6338");
        assertThat(inventory).contains("nvme0n1:").contains("rootfs:");
        // 资源池必须带主机前缀，否则框架 get_resource_config 判 PermissionError
        // 资源键带主机前缀（inventory_key_rule 默认是 name@ip，与 hosts 键一致）
        assertThat(runEnv).contains("/gpu0").contains("/cpu0").contains("/nvme0n1");
        assertThat(runEnv).contains("gpus:").contains("cpus:").contains("storages:");
        // selected_targets 必须与 resource_pool 同类同量：框架先查池、再查授权清单，
        // 池里给了 gpus 却只在 targets 写 nic，GPU 用例会在 fixture 里被判「不在授权范围」。
        assertThat(runEnv).contains("gpu:gpu01@10.0.0.1/gpu0");
        assertThat(runEnv).contains("cpu:gpu01@10.0.0.1/cpu0");
        assertThat(runEnv).contains("storage:gpu01@10.0.0.1/nvme0n1");
        assertThat(result.warnings()).noneMatch(w -> w.contains("未采集到 GPU"));
    }

    /**
     * 多卡与回环盘的取舍（FR-v5.6-41）。
     *
     * <p>两条判据各自守一个真问题：{@code loop*} 是 snap 回环挂载，
     * 一台机器实测 19 个，混进资源池会让用例误挑到只读几十兆的假盘；
     * gpu0/gpu1 的顺序必须按采集顺序稳定——顺序不稳会让同配置两次渲染的
     * 产物哈希不同，「同配置同哈希」这条证据链就失效了。
     */
    @Test
    void 多卡保序且过滤回环盘(@TempDir Path dir) throws Exception {
        String spec = """
                {"items":[
                  {"category":"GPU","label":"GPU","value":[
                    {"name":"NVIDIA A100-SXM4-80GB","memoryTotal":"81920 MiB"},
                    {"name":"NVIDIA A100-SXM4-80GB","memoryTotal":"81920 MiB"}]},
                  {"category":"DISK","label":"DISK","value":
                    {"root":{"size":"1.8T","used":"500G","available":"1.2T"},
                     "disks":[{"name":"loop7","size":"64M","type":"SSD"},
                              {"name":"nvme0n1","size":"3.5T","type":"SSD"}]}}
                ]}""";

        PytestEnvRenderer.RenderResult first = rendererWithAssets(spec)
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "real", dir));
        String runEnv = Files.readString(first.runEnvFile(), StandardCharsets.UTF_8);

        assertThat(runEnv.indexOf("/gpu0")).isLessThan(runEnv.indexOf("/gpu1"));
        assertThat(runEnv).doesNotContain("/loop7");
        assertThat(runEnv).contains("/nvme0n1");

        // 同配置再渲染一次：哈希必须一致，否则「这条证据对应这份配置」不成立。
        PytestEnvRenderer.RenderResult second = rendererWithAssets(spec)
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "real", dir));
        assertThat(second.runEnvSha256()).isEqualTo(first.runEnvSha256());
    }

    /**
     * 没有采集过时：三类为空 + 逐台主机一条警告。
     *
     * <p>这条比上一条更重要——「机器没卡」和「还没采」必须能分清，
     * 否则用户看到空的 gpus 只会去查硬件。
     */
    @Test
    void 未采集时为空并逐台告警(@TempDir Path dir) throws Exception {
        PytestEnvRenderer.RenderResult result = rendererWithAssets("{\"items\":[]}")
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "fake", dir));
        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);

        // YAML 序列化器会把连续的空 map 复用成锚点（&id001），所以不比对字面量，
        // 而是断言「没有派生出来的资源项」+ 有明确告警
        assertThat(inventory).doesNotContain("gpu0:").doesNotContain("cpu0:");
        assertThat(result.warnings()).anyMatch(w -> w.contains("未采集到 GPU/CPU/存储"));
    }

    /** 带一份已采集清单的渲染器（其余与 {@link #renderer} 相同）。 */
    private static PytestEnvRenderer rendererWithAssets(String specJson) {
        return rendererWithAssets(specJson, null);
    }

    private static PytestEnvRenderer rendererWithAssets(String specJson, String repoPath) {
        Device device = new Device(1L, PROJECT, "gpu01", "gpu", "NF5280", "inspur", "SN1",
                "10.0.0.1", null, null, "rdma", true, null, null,
                null, null, null, null, null);
        DevicePort port = new DevicePort(10L, 1L, "ens17np0", "eth", "ethernet", null, 400, 1500,
                null, "up", "ens17np0", 0, "nvidia", "NVIDIA", "ConnectX-7", "1x400G",
                "0000:31:00.0", "0x15b3", null, "mlx5_core", null, "mlx5_0", 1, 0, "rx-data",
                "[\"roce\"]", "{}");
        Map<Long, List<DevicePort>> byDevice = new LinkedHashMap<>();
        byDevice.put(1L, List.of(port));
        HostInventoryRecord record = new HostInventoryRecord(1L, PROJECT, 1L, "10.0.0.1",
                java.time.Instant.parse("2026-11-13T10:00:00Z"), "OK", specJson, null, 1, 0, null);
        Map<String, String> settings = new LinkedHashMap<>();
        if (repoPath != null) {
            // 生产仓储 findAll(projectId, "pytest.") 已把前缀剥掉，这里也必须不带前缀
            settings.put("repo_path", repoPath);
        }
        return new PytestEnvRenderer(new FakeDevices(List.of(device), byDevice),
                new FakeSettings(settings), new FakeInventories(record), new ObjectMapper());
    }

    private record FakeInventories(HostInventoryRecord record)
            implements HostInventoryRepository {

        @Override
        public long insert(HostInventoryRecord one, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HostInventoryRecord> find(long projectId, long id) {
            return record.id() == id ? Optional.of(record) : Optional.empty();
        }

        @Override
        public List<HostInventoryRecord> list(long projectId, Long deviceId, int limit) {
            // 与生产仓储一致：摘要列不含 spec_json，这里也必须是 null。
            // 早先这个假实现直接回完整记录，把「渲染器误用 list()」的缺陷盖住了，
            // 结果单测全绿、真机上 GPU/CPU/存储 恒空。
            return List.of(new HostInventoryRecord(record.id(), record.projectId(),
                    record.deviceId(), record.host(), record.collectedAt(), record.status(),
                    null, record.specHash(), record.nicCount(), record.rocePorts(),
                    record.roceMaxGbps()));
        }

        @Override
        public Optional<HostInventoryRecord> latest(long projectId, Long deviceId) {
            return Optional.of(record);
        }
    }

    /**
     * GPU 的 {@code model} 必须是对应用例库规格表的键（FR-v5.6-41）。
     *
     * <p>采集器只给全名，规格表按 {@code display_name} 反查键——这一步没有算法可推，
     * 所以必须显式映射。缺了它，用例在 fixture 里报「未登记 gpus 型号 None」，
     * 看上去像规格表缺条目，实际是 inventory 少字段，排查方向会被带偏。
     */
    @Test
    void GPU型号按用例库规格表映射(@TempDir Path repo, @TempDir Path dir) throws Exception {
        Path specs = repo.resolve("config/specs");
        Files.createDirectories(specs);
        Files.writeString(specs.resolve("device_specs.yaml"), """
                gpus:
                  pro-6000:
                    display_name: "NVIDIA RTX PRO 6000 Blackwell Server Edition"
                    vendor: nvidia
                """, StandardCharsets.UTF_8);

        String spec = """
                {"items":[{"category":"GPU","label":"GPU","value":[
                  {"name":"NVIDIA RTX PRO 6000 Blackwell Server Edition",
                   "memoryTotal":"97887 MiB","driverVersion":"595.71.05"}]}]}""";
        PytestEnvRenderer.RenderResult result = rendererWithAssets(spec, repo.toString())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "real", dir));
        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);

        assertThat(inventory).contains("model: pro-6000");
        // vendor 决定 GPU Adapter；只给 model 会让框架回落 generic 报「厂商尚未适配」。
        assertThat(inventory).contains("vendor: nvidia");
        assertThat(result.warnings()).noneMatch(w -> w.contains("未在用例库 device_specs.yaml"));
    }

    /** 规格表里查不到时要留空并告警，不能猜一个近似键让用例拿着错规格继续跑。 */
    @Test
    void GPU型号查不到时留空并告警(@TempDir Path repo, @TempDir Path dir) throws Exception {
        Path specs = repo.resolve("config/specs");
        Files.createDirectories(specs);
        Files.writeString(specs.resolve("device_specs.yaml"), """
                gpus:
                  pro-6000:
                    display_name: "NVIDIA RTX PRO 6000 Blackwell Server Edition"
                    vendor: nvidia
                """, StandardCharsets.UTF_8);

        String spec = """
                {"items":[{"category":"GPU","label":"GPU","value":[
                  {"name":"Some Unknown Accelerator X1","memoryTotal":"1 MiB"}]}]}""";
        PytestEnvRenderer.RenderResult result = rendererWithAssets(spec, repo.toString())
                .render(new PytestEnvRenderer.RenderRequest(PROJECT, "RUN-1", "real", dir));
        String inventory = Files.readString(result.inventoryFile(), StandardCharsets.UTF_8);

        assertThat(inventory).contains("model: ''").contains("vendor: ''");
        assertThat(result.warnings()).anyMatch(w -> w.contains("未在用例库 device_specs.yaml"));
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
