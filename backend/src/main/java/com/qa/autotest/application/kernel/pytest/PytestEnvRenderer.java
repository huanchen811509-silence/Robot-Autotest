package com.qa.autotest.application.kernel.pytest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.HostInventoryRepository;
import com.qa.autotest.domain.host.HostInventoryRecord;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * pytest 环境渲染器（FR-v5.1-02 / FR-v5.1-03，验收 A3–A6）。
 *
 * <p>把主程序的资产台账渲染成自研框架真正能吃的两份 YAML：
 * <ol>
 *   <li>{@code inventory.yaml} —— hosts → devices.nics，字段名与框架
 *       {@code load_inventory()} 逐字对齐（NIC 级 15 项见 {@link DevicePort}）；</li>
 *   <li>{@code run_env.yaml} —— {@code inventory_file} 指向同目录的 inventory，
 *       {@code resource_pool} / {@code selected_targets} 从**选中的**设备与端口派生，
 *       {@code mode} 取工程配置（默认 fake）。</li>
 * </ol>
 *
 * <p><b>凭据零明文（D5）</b>：渲染产物里只出现 {@code ${ENV:XXX}} 引用，不出现任何口令。
 * 框架的 {@code _resolve_secret_refs} 在加载时替换；变量未注入时保留原样并交给执行机报错，
 * 这比在平台侧解密再写盘安全得多——产物会被归档、被导出、被贴进报告。
 *
 * <p><b>不编假值</b>：设备缺 {@code os_family} 时用工程配置的显式默认值，
 * 并在 {@link RenderResult#warnings()} 里逐条列出「哪台设备用了默认值」。
 * 静默补默认值会让「渲染成功」变成假象，评审时看不出资产是空的。
 */
@Service
public class PytestEnvRenderer {

    /** 主机用户名环境变量名（执行机侧注入）。 */
    public static final String ENV_SSH_USER = "PYTEST_SSH_USER";
    /** 主机口令环境变量名（执行机侧注入）。 */
    public static final String ENV_SSH_PASSWORD = "PYTEST_SSH_PASSWORD";

    private static final String SETTING_OS_FAMILY_DEFAULT = "os_family_default";
    private static final String SETTING_FAKE_PLAN = "fake_plan_file";
    private static final String SETTING_SCHEMA_VERSION = "inventory_schema_version";

    private final DeviceRepository devices;
    private final ProjectSettingRepository settings;
    private final HostInventoryRepository inventories;
    private final ObjectMapper mapper;

    @Autowired
    public PytestEnvRenderer(DeviceRepository devices, ProjectSettingRepository settings,
                             HostInventoryRepository inventories, ObjectMapper mapper) {
        this.devices = devices;
        this.settings = settings;
        this.inventories = inventories;
        this.mapper = mapper;
    }

    /**
     * 兼容 v5.1.0 的两参写法（不派生 GPU/CPU/存储，渲染出的三类资产为空）。
     *
     * <p>两个公开构造器并存时 Spring 无法自行选择，会退化成「找不到默认构造器」，
     * 应用上下文直接启动失败（实测 crash-loop）。所以主构造器必须显式 {@code @Autowired}。
     */
    public PytestEnvRenderer(DeviceRepository devices, ProjectSettingRepository settings) {
        this(devices, settings, null, null);
    }

    /**
     * 渲染请求：选中哪些主机、哪些网口。
     *
     * <p>用 {@code hostKeys}（inventory 键）而不是设备 id：执行链的 {@code deviceKeys}
     * 本来就是自由格式资源键，RF 侧按它申请租约；pytest 侧直接把它当 inventory 键用，
     * 两边对「选的是哪台机器」只有一种表达，不需要再翻译一次。
     *
     * @param hostKeys 空表示全部设备
     * @param nicRefs  {@code host/netdev} 形态；空表示这些主机的全部网口
     */
    public record RenderRequest(long projectId,
                                String runId,
                                List<String> hostKeys,
                                List<String> nicRefs,
                                String mode,
                                Path outputDir) {

        /** 兼容只给项目与产出目录的极简调用（全部设备 + 全部网口）。 */
        public RenderRequest(long projectId, String runId, String mode, Path outputDir) {
            this(projectId, runId, List.of(), List.of(), mode, outputDir);
        }
    }

    /** 渲染结果：两份产物 + 哈希 + 警告。哈希进 run 证据，便于判断「这次跑的到底是哪份配置」。 */
    public record RenderResult(Path inventoryFile,
                               Path runEnvFile,
                               String inventorySha256,
                               String runEnvSha256,
                               List<String> hostKeys,
                               List<String> nicKeys,
                               List<String> warnings) {
    }

    /**
     * 渲染。仓库不可读、设备/端口不存在、选中端口不属于选中设备 —— 全部直接抛
     * {@link IllegalStateException}，由预检转成「环境不健康」拒绝，绝不产出半成品。
     */
    public RenderResult render(RenderRequest request) {
        List<Device> selected = resolveDevices(request);
        Map<Long, List<DevicePort>> portsByDevice = resolvePorts(request, selected);
        Map<String, String> config = PytestProjectSettings.load(settings, request.projectId());
        String keyRule = PytestProjectSettings.value(config, PytestProjectSettings.INVENTORY_KEY_RULE);
        String osDefault = defaultIfBlank(
                PytestProjectSettings.value(config, SETTING_OS_FAMILY_DEFAULT), "ubuntu");
        String schemaVersion = defaultIfBlank(
                PytestProjectSettings.value(config, SETTING_SCHEMA_VERSION), "1.2");
        String mode = request.mode() != null && !request.mode().isBlank()
                ? request.mode().trim() : PytestProjectSettings.modeDefault(config);

        List<String> warnings = new ArrayList<>();
        // FR-v5.6-41：GPU/CPU/存储从已采集的主机清单派生。没有任何一次采集时保持为空并写警告——
        // 空对象会让框架报「资源池里没有可用 GPU」，警告让人知道该先去采，而不是以为机器没卡。
        Map<Long, PytestHostAssets> assets = loadAssets(request.projectId(), selected, warnings);
        // 采集只给型号全名，用例库按规格键取规格；这一层映射必须显式做（见类注释）。
        PytestGpuModelCatalog gpuCatalog = PytestGpuModelCatalog.load(
                PytestProjectSettings.repoPath(config), warnings);
        Map<String, Object> inventory = buildInventory(request.projectId(), selected,
                portsByDevice, assets, gpuCatalog, keyRule, osDefault, schemaVersion, warnings);
        Map<String, Object> runEnv = buildRunEnv(request.runId(), selected, portsByDevice,
                assets, keyRule, mode, config);

        Path dir = request.outputDir();
        Path inventoryFile = dir.resolve("inventory.yaml");
        Path runEnvFile = dir.resolve("run_env.yaml");
        try {
            Files.createDirectories(dir);
            Files.writeString(inventoryFile, dump(inventory), StandardCharsets.UTF_8);
            Files.writeString(runEnvFile, dump(runEnv), StandardCharsets.UTF_8);
            return new RenderResult(inventoryFile, runEnvFile, sha256(inventoryFile),
                    sha256(runEnvFile), hostKeys(selected, keyRule), nicKeys(selected,
                    portsByDevice, keyRule), List.copyOf(warnings));
        } catch (IOException e) {
            throw new IllegalStateException("渲染产物写入失败: " + dir + " — " + e.getMessage(), e);
        }
    }

    /** 每台设备取最近一次采集；没有任何采集时返回 EMPTY 并记一条警告。 */
    private Map<Long, PytestHostAssets> loadAssets(long projectId, List<Device> selected,
                                                   List<String> warnings) {
        Map<Long, PytestHostAssets> result = new LinkedHashMap<>();
        if (inventories == null || mapper == null) {
            return result;
        }
        for (Device device : selected) {
            PytestHostAssets assets = PytestHostAssets.EMPTY;
            try {
                // 必须取完整记录：list() 只查摘要列，specJson 恒为 null，
                // 用它判断「有没有采到 GPU」永远得到「没有」。
                assets = inventories.latest(projectId, device.id())
                        .map(record -> PytestHostAssets.from(record.specJson(), mapper))
                        .orElse(PytestHostAssets.EMPTY);
            } catch (RuntimeException ex) {
                warnings.add("设备 " + device.name() + " 的主机清单读取失败：" + ex.getMessage());
            }
            if (!assets.any()) {
                warnings.add("设备 " + device.name()
                        + " 未采集到 GPU/CPU/存储（devices.gpus/cpu/storage 为空），"
                        + "请先在「环境采集」里采一次");
            }
            result.put(device.id(), assets);
        }
        return result;
    }

    private List<Device> resolveDevices(RenderRequest request) {
        Map<String, String> config = PytestProjectSettings.load(settings, request.projectId());
        String keyRule = PytestProjectSettings.value(config, PytestProjectSettings.INVENTORY_KEY_RULE);
        List<Device> all = devices.listAll(request.projectId());
        Set<String> wanted = request.hostKeys() == null ? Set.of()
                : new LinkedHashSet<>(request.hostKeys());
        List<Device> selected = new ArrayList<>();
        for (Device device : all) {
            if (wanted.isEmpty() || wanted.contains(device.inventoryKey(keyRule))) {
                selected.add(device);
            }
        }
        // 顺序有讲究：先核「点名要的主机是否都在」，再判「是否有设备」。
        // 反过来的话，点名一台不存在的主机会先命中「未选中任何设备」，
        // 用户拿到的报错就变成「你没选设备」，而真实原因是「你选的那台不在台账里」——误导排查方向。
        if (!wanted.isEmpty()) {
            // 逐个核对而不是比个数：`wanted` 里有一个幽灵键时，个数可能与命中数相等，
            // 那时候比 size 会放过「选了两台但只命中一台」的情况。
            List<String> missing = new ArrayList<>();
            for (String key : wanted) {
                if (selected.stream().noneMatch(d -> d.inventoryKey(keyRule).equals(key))) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("选中的主机在资产台账里不存在或不属于本项目: "
                        + String.join(", ", missing));
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("未选中任何设备：pytest 工程至少要一台可 SSH 的主机");
        }
        return selected;
    }

    private Map<Long, List<DevicePort>> resolvePorts(RenderRequest request, List<Device> selected) {
        Set<String> wantedRefs = request.nicRefs() == null ? Set.of()
                : new LinkedHashSet<>(request.nicRefs());
        Map<String, String> config = PytestProjectSettings.load(settings, request.projectId());
        String keyRule = PytestProjectSettings.value(config, PytestProjectSettings.INVENTORY_KEY_RULE);
        Map<Long, List<DevicePort>> result = new LinkedHashMap<>();
        int matched = 0;
        for (Device device : selected) {
            String host = device.inventoryKey(keyRule);
            List<DevicePort> ports = new ArrayList<>();
            for (DevicePort port : devices.ports(request.projectId(), device.id())) {
                String ref = host + "/" + port.netdev();
                if (wantedRefs.isEmpty() || wantedRefs.contains(ref)) {
                    ports.add(port);
                    matched++;
                }
            }
            result.put(device.id(), ports);
        }
        if (!wantedRefs.isEmpty() && matched != wantedRefs.size()) {
            throw new IllegalStateException(
                    "选中的网口中有不属于所选设备的引用（选中 " + wantedRefs.size()
                            + " 个，匹配到 " + matched + " 个）");
        }
        return result;
    }

    private Map<String, Object> buildInventory(long projectId, List<Device> selected,
                                               Map<Long, List<DevicePort>> portsByDevice,
                                               Map<Long, PytestHostAssets> assets,
                                               PytestGpuModelCatalog gpuCatalog,
                                               String keyRule, String osDefault,
                                               String schemaVersion, List<String> warnings) {
        Map<String, Object> hosts = new LinkedHashMap<>();
        for (Device device : selected) {
            String key = device.inventoryKey(keyRule);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("host", hostBlock(device, osDefault, warnings));
            node.put("devices", devicesBlock(portsByDevice.getOrDefault(device.id(), List.of()),
                    assets.getOrDefault(device.id(), PytestHostAssets.EMPTY), gpuCatalog,
                    device.name(), warnings));
            hosts.put(key, node);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", schemaVersion);
        root.put("environment_id", "project-" + projectId);
        root.put("hosts", hosts);
        // Switch 是外围独立资源，本版本不从资产表派生（资产侧尚无交换机实体）。
        // 保留空节点而不是省略：框架读取 external_resources.switches 时不会 KeyError。
        root.put("external_resources", Map.of("switches", Map.of()));
        return root;
    }

    private Map<String, Object> hostBlock(Device device, String osDefault, List<String> warnings) {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("hostname", device.name());
        host.put("management_ip", device.managementIp());
        // 凭据只写引用：产物会归档、导出、贴报告，任何明文都是泄漏面。
        host.put("username", "${ENV:" + ENV_SSH_USER + "}");
        host.put("password", "${ENV:" + ENV_SSH_PASSWORD + "}");
        host.put("ssh_port", device.sshPort() == null ? 22 : device.sshPort());
        host.put("connect_timeout_s", 10);
        host.put("auth_timeout_s", 10);
        host.put("known_hosts_policy", "reject");
        String osFamily = device.osFamily();
        if (osFamily == null || osFamily.isBlank()) {
            osFamily = osDefault;
            warnings.add("设备 " + device.name() + " 未填 os_family，已用工程默认值 " + osDefault
                    + "（请补齐资产台账）");
        }
        host.put("os_family", osFamily);
        host.put("os_version", device.osVersion() == null ? "" : device.osVersion());
        host.put("architecture", defaultIfBlank(device.architecture(), "x86_64"));
        return host;
    }

    private Map<String, Object> devicesBlock(List<DevicePort> ports, PytestHostAssets assets,
                                             PytestGpuModelCatalog gpuCatalog, String hostName,
                                             List<String> warnings) {
        Map<String, Object> nics = new LinkedHashMap<>();
        for (DevicePort port : ports) {
            String netdev = port.netdev();
            if (netdev.isEmpty()) {
                continue;
            }
            nics.put(netdev, nicBlock(port, netdev));
        }
        Map<String, Object> devices = new LinkedHashMap<>();
        devices.put("nics", nics);
        // FR-v5.6-41：三类从主机清单派生；vms / ovs 仍为空（资产侧没有这两类实体），
        // 给空对象保证框架读取时不 KeyError。
        devices.put("gpus", gpuBlock(assets.gpus(), gpuCatalog, hostName, warnings));
        devices.put("cpu", assets.cpus());
        devices.put("vms", Map.of());
        devices.put("ovs", Map.of());
        devices.put("storage", assets.storages());
        return devices;
    }

    /**
     * GPU 段：在派生出来的 {@code index/name/memory_total/driver_version} 上补 {@code model}。
     *
     * <p>{@code model} 是用例库规格表的键（如 {@code pro-6000}），与采集到的全名
     * 没有算法关系，只能按 {@code display_name} 反查。取不到时留空并逐卡告警——
     * 空 model 会让用例在取规格时报「未登记型号 None」，这条告警就是让人知道该补哪里。
     */
    private Map<String, Object> gpuBlock(Map<String, Object> derived,
                                         PytestGpuModelCatalog gpuCatalog, String hostName,
                                         List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : derived.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) {
                result.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            raw.forEach((k, v) -> card.put(String.valueOf(k), v));
            String name = String.valueOf(card.getOrDefault("name", ""));
            PytestGpuModelCatalog.Entry spec = gpuCatalog.lookup(name);
            if (!spec.complete()) {
                warnings.add("主机 " + hostName + " 的 GPU 型号「" + name
                        + "」未在用例库 device_specs.yaml 的 gpus 下登记（按 display_name 匹配），"
                        + "inventory 里 model/vendor 留空：GPU 用例取规格会报「未登记型号」，"
                        + "取适配器会报「厂商尚未适配」");
            }
            // vendor 决定 GPU Adapter：缺它时框架回落 generic，报「厂商尚未适配」，
            // 与「model 缺失」是两处不同的坑，必须一起写。
            card.put("model", spec.model());
            card.put("vendor", spec.vendor());
            result.put(entry.getKey(), card);
        }
        return result;
    }

    private Map<String, Object> nicBlock(DevicePort port, String netdev) {
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("netdev", netdev);
        nic.put("vendor", port.nicVendor());
        nic.put("manufacturer", port.nicManufacturer());
        nic.put("product_model", port.nicModel());
        nic.put("form_factor", port.nicFormFactor());
        nic.put("pci_bdf", port.pciBdf());
        nic.put("vendor_id", port.vendorId());
        nic.put("device_id", port.pciDeviceId());
        nic.put("driver", port.driver());
        nic.put("firmware", port.firmware());
        nic.put("rdma_device", port.rdmaDevice());
        nic.put("port", port.nicPort());
        nic.put("numa_node", port.numaNode());
        nic.put("role", port.nicRole());
        nic.put("capabilities", jsonList(port.capabilitiesJson()));
        nic.put("counter_patterns", jsonMap(port.counterPatternsJson()));
        return nic;
    }

    private Map<String, Object> buildRunEnv(String runId, List<Device> selected,
                                            Map<Long, List<DevicePort>> portsByDevice,
                                            Map<Long, PytestHostAssets> assets,
                                            String keyRule, String mode,
                                            Map<String, String> config) {
        List<String> hosts = new ArrayList<>();
        List<String> nics = new ArrayList<>();
        List<String> gpus = new ArrayList<>();
        List<String> cpus = new ArrayList<>();
        List<String> storages = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (Device device : selected) {
            String key = device.inventoryKey(keyRule);
            hosts.add(key);
            for (DevicePort port : portsByDevice.getOrDefault(device.id(), List.of())) {
                String netdev = port.netdev();
                if (netdev.isEmpty()) {
                    continue;
                }
                nics.add(key + "/" + netdev);
            }
            PytestHostAssets hostAssets = assets.getOrDefault(device.id(), PytestHostAssets.EMPTY);
            for (String local : hostAssets.gpuKeys()) {
                gpus.add(key + "/" + local);
            }
            for (String local : hostAssets.cpuKeys()) {
                cpus.add(key + "/" + local);
            }
            for (String local : hostAssets.storageKeys()) {
                storages.add(key + "/" + local);
            }
        }
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("hosts", hosts);
        pool.put("nics", nics);
        // 三类资产按 {@code host/key} 限定：框架的 get_resource_config 对带主机的资源
        // 只认「host-key/device-key」形式，只写裸键会判 PermissionError。
        pool.put("gpus", gpus);
        pool.put("cpus", cpus);
        pool.put("vms", List.of());
        pool.put("ovss", List.of());
        pool.put("storages", storages);
        pool.put("switches", List.of());

        // selected_targets 是「本次授权清单」，不是「本次点名的网口」。
        // 框架 runtime 的判定顺序是：先在 resource_pool 里找到资源，再看它的 selector
        // 是否在 selected_targets 里——只把 nic 写进 targets、pool 里却给了 gpus，
        // 结果就是 GPU 用例在 fixture 里抛 UsageError「不在本次 selected_targets 范围」，
        // 而资源明明是平台自己放进去的。所以 pool 里有哪几类，targets 就要有哪几类。
        targets.addAll(kindTargets("nic", nics));
        targets.addAll(kindTargets("gpu", gpus));
        targets.addAll(kindTargets("cpu", cpus));
        targets.addAll(kindTargets("storage", storages));

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("inventory_file", "inventory.yaml");
        String fakePlan = PytestProjectSettings.value(config, SETTING_FAKE_PLAN);
        if ("fake".equalsIgnoreCase(mode) && fakePlan != null) {
            // 绝对路径：run_env 渲染在 runDir，相对路径会基于 runDir 解析而落空。
            env.put("fake_plan_file", fakePlan);
        }
        env.put("run_id", runId);
        env.put("resource_pool", pool);
        env.put("selected_targets", targets);
        env.put("mode", mode);
        return env;
    }

    /** 把 {@code host/local} 资源键批量转成框架认的 {@code kind:host/local} selector。 */
    private static List<String> kindTargets(String kind, List<String> refs) {
        List<String> selectors = new ArrayList<>(refs.size());
        for (String ref : refs) {
            selectors.add(kind + ":" + ref);
        }
        return selectors;
    }

    private static List<String> hostKeys(List<Device> selected, String keyRule) {
        List<String> keys = new ArrayList<>();
        for (Device device : selected) {
            keys.add(device.inventoryKey(keyRule));
        }
        return List.copyOf(keys);
    }

    private static List<String> nicKeys(List<Device> selected,
                                        Map<Long, List<DevicePort>> portsByDevice,
                                        String keyRule) {
        List<String> keys = new ArrayList<>();
        for (Device device : selected) {
            String host = device.inventoryKey(keyRule);
            for (DevicePort port : portsByDevice.getOrDefault(device.id(), List.of())) {
                if (!port.netdev().isEmpty()) {
                    keys.add(host + "/" + port.netdev());
                }
            }
        }
        return List.copyOf(keys);
    }

    /** JSON 字符串数组 → List；非法或空串返回空列表（不抛，渲染要能出半可用产物）。 */
    static List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** JSON 对象 → Map；非法或空串返回空 Map。 */
    static Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String dump(Map<String, Object> data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setWidth(120);
        options.setAllowUnicode(true);
        return new Yaml(options).dump(data);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("JVM 缺少 SHA-256", e);
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
