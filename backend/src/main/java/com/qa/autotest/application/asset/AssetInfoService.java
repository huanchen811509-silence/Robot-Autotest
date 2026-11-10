package com.qa.autotest.application.asset;

import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.LinkRepository;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.cache.TtlCache;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.asset.Link;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 连接与拓扑派生（v4.3.0 / FR-v4.3-03、FR-v4.3-04、FR-v4.3-05）。
 *
 * <p><b>数据只从库内派生</b>：设备 + 端口（含网卡归属）+ 链路，不调远端、不合并
 * {@code topology_node} 手工拓扑。可见口径统一走仓储里的 {@code AssetScopeSql}，
 * 因此「本项目私有 + 已引用平台共享」自动生效，不会读到别的项目私有资产。
 *
 * <p><b>两级缓存</b>：片段级按 {@code link:{id}:{contentHash}}，项目级按
 * {@code project:{projectId}:{资产集指纹}}。指纹只由资产内容（设备/端口/链路字段）算，
 * <b>别名不进缓存键</b>——别名只在组装时套用，A 项目的 {@code alias-b} 不会串到 B 项目。
 * 资产一改，指纹随之变化，旧键自然失效；写事件还会主动清掉该项目的项目级缓存。
 *
 * <p><b>失效不降级</b>：缓存关闭时全部直算，结果与开启时一致；并发按项目键单飞，
 * 同一项目同一指纹在途时只算一次，其余请求复用同一结果。
 */
@Service
public class AssetInfoService {

    /** 数据来源标识：本期只有内部派生一种。 */
    public static final String SOURCE_INTERNAL = "internal";

    /** 端口未填网卡归属时的分组名（PRD FR-v4.3-03 R3）。 */
    static final String UNASSIGNED_NIC = "未归属";

    private static final int FRAGMENT_CACHE_MAX = 4096;

    private final DeviceRepository devices;
    private final LinkRepository links;
    private final ProjectAssetRepository projectAssets;
    private final AssetInfoProperties props;
    private final TtlCache<String, AssetInfoResponse> projectCache;
    private final TtlCache<String, AssetInfoResponse.ConnectionItem> linkCache;
    private final ConcurrentHashMap<String, CompletableFuture<AssetInfoResponse>> inflight =
            new ConcurrentHashMap<>();

    @Autowired
    public AssetInfoService(DeviceRepository devices, LinkRepository links,
                            ProjectAssetRepository projectAssets, AssetInfoProperties props) {
        this(devices, links, projectAssets, props, System::nanoTime);
    }

    /** 测试入口：注入时钟，避免 sleep 等真实时间。 */
    AssetInfoService(DeviceRepository devices, LinkRepository links,
                     ProjectAssetRepository projectAssets, AssetInfoProperties props,
                     LongSupplier clock) {
        this.devices = devices;
        this.links = links;
        this.projectAssets = projectAssets;
        this.props = props;
        Duration ttl = Duration.ofSeconds(Math.max(1, props.getTtlSeconds()));
        this.projectCache = new TtlCache<>(Math.max(1, props.getMaxProjectEntries()), ttl,
                props.isEnabled(), clock);
        this.linkCache = new TtlCache<>(FRAGMENT_CACHE_MAX, ttl, props.isEnabled(), clock);
    }

    /**
     * 读取本项目连接与拓扑。
     *
     * @param force 手工刷新时跳过复用窗口（PRD FR-v4.3-06）
     */
    public AssetInfoResponse get(long projectId, boolean force) {
        Snapshot snap = load(projectId);
        String key = "project:" + projectId + ":" + fingerprint(snap);
        if (!force) {
            Optional<AssetInfoResponse> hit = projectCache.getIfPresent(key);
            if (hit.isPresent()) {
                return withMeta(hit.get(), true);
            }
        }
        // 单飞体内的二次检查：前一批请求刚完成时缓存已写好，后来者直接复用，
        // 不会因为「赶在 inflight 移除之后」再算一遍（PRD FR-v4.3-05 R3）。
        AssetInfoResponse built = singleFlight(key, () -> {
            if (!force) {
                Optional<AssetInfoResponse> again = projectCache.getIfPresent(key);
                if (again.isPresent()) {
                    return again.get();
                }
            }
            AssetInfoResponse fresh = build(snap);
            projectCache.put(key, fresh);
            return fresh;
        });
        return withMeta(built, false);
    }

    /** 项目级缓存失效；{@code projectId} 为空表示平台资产变更，影响所有项目。 */
    public void invalidate(Long projectId) {
        if (projectId == null) {
            projectCache.clear();
        } else {
            projectCache.invalidateIf(k -> k.startsWith("project:" + projectId + ":"));
        }
    }

    /** 当前缓存读数（命中/未命中/条目数），供日志与观测使用。 */
    public TtlCache.Stats cacheStats() {
        return projectCache.stats();
    }

    private AssetInfoResponse singleFlight(String key, Supplier<AssetInfoResponse> loader) {
        CompletableFuture<AssetInfoResponse> mine = new CompletableFuture<>();
        CompletableFuture<AssetInfoResponse> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            AssetInfoResponse built = loader.get();
            mine.complete(built);
            return built;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private AssetInfoResponse withMeta(AssetInfoResponse base, boolean cacheHit) {
        return new AssetInfoResponse(base.generatedAt(),
                new AssetInfoResponse.Meta(cacheHit, SOURCE_INTERNAL, false,
                        Math.max(1, props.getTtlSeconds())),
                base.connections(), base.topology(), base.empty());
    }

    private Snapshot load(long projectId) {
        Snapshot snap = new Snapshot();
        snap.devices = devices.listAll(projectId);
        snap.ports = devices.portsOfVisibleDevices(projectId);
        snap.links = links.listAll(projectId);
        snap.deviceAliases = projectAssets.aliases(projectId, "DEVICE");
        return snap;
    }

    private AssetInfoResponse build(Snapshot snap) {
        Map<Long, Device> deviceById = new HashMap<>();
        for (Device d : snap.devices) {
            deviceById.put(d.id(), d);
        }
        Map<Long, List<DevicePort>> portsByDevice = new LinkedHashMap<>();
        for (DevicePort p : snap.ports) {
            portsByDevice.computeIfAbsent(p.deviceId(), k -> new ArrayList<>()).add(p);
        }
        Map<Long, DevicePort> portById = new HashMap<>();
        for (DevicePort p : snap.ports) {
            portById.put(p.id(), p);
        }
        List<AssetInfoResponse.ConnectionItem> items = new ArrayList<>();
        List<Link> sortedLinks = new ArrayList<>(snap.links);
        sortedLinks.sort(Comparator.comparing(Link::id));
        for (Link link : sortedLinks) {
            items.add(connectionOf(link, deviceById, portById, snap.deviceAliases));
        }
        int ttl = Math.max(1, props.getTtlSeconds());
        AssetInfoResponse.Connections connections =
                new AssetInfoResponse.Connections(List.copyOf(items), items.size());
        AssetInfoResponse.Topology topology = topologyOf(snap, portsByDevice, portById);
        return new AssetInfoResponse(Instant.now(),
                new AssetInfoResponse.Meta(false, SOURCE_INTERNAL, false, ttl),
                connections, topology, snap.devices.isEmpty() && snap.links.isEmpty());
    }

    /**
     * 一条连接 = 一条链路的两端。片段缓存键含链路内容指纹，不含项目别名：
     * 同一平台资产被多个项目引用时，片段结果可跨项目复用。
     */
    private AssetInfoResponse.ConnectionItem connectionOf(
            Link link, Map<Long, Device> deviceById, Map<Long, DevicePort> portById,
            Map<Long, String> aliases) {
        String fragmentKey = "link:" + link.id() + ":" + hash(linkFragment(link, portById));
        return linkCache.get(fragmentKey,
                k -> newConnection(link, deviceById, portById, aliases));
    }

    private static AssetInfoResponse.ConnectionItem newConnection(
            Link link, Map<Long, Device> deviceById, Map<Long, DevicePort> portById,
            Map<Long, String> aliases) {
        Device aDevice = deviceById.get(link.aDeviceId());
        Device bDevice = deviceById.get(link.bDeviceId());
        DevicePort aPort = portById.get(link.aPortId());
        DevicePort bPort = portById.get(link.bPortId());
        boolean enabled = link.enabled()
                && (aDevice == null || aDevice.enabled())
                && (bDevice == null || bDevice.enabled());
        return new AssetInfoResponse.ConnectionItem(
                link.id(), link.name(), link.linkType(),
                deviceName(aDevice, link.aDeviceName(), aliases),
                nicName(aPort),
                aPort != null ? aPort.portName() : link.aPortName(),
                deviceName(bDevice, link.bDeviceName(), aliases),
                nicName(bPort),
                bPort != null ? bPort.portName() : link.bPortName(),
                link.protocol(), link.speedGbps(), link.mtu(),
                aPort != null ? aPort.macAddress() : null,
                aPort != null ? aPort.ipAddress() : null,
                aPort != null ? aPort.portStatus() : null,
                enabled);
    }

    private static String deviceName(Device device, String fallback, Map<Long, String> aliases) {
        if (device == null) {
            return fallback;
        }
        String alias = aliases.get(device.id());
        return alias != null && !alias.isBlank() ? alias : device.name();
    }

    private static String nicName(DevicePort port) {
        return port == null || port.nicName() == null || port.nicName().isBlank()
                ? null : port.nicName();
    }

    /**
     * 拓扑节点：设备（根）→ 网卡 → 接口；边为链路两端接口。
     *
     * <p>排序固定：设备按 id，网卡按 {@code nic_index} 升序（空值最后）再按名称字典序，
     * 接口按名称。同数据两次请求顺序完全一致（PRD FR-v4.3-03 R4）。
     */
    private AssetInfoResponse.Topology topologyOf(
            Snapshot snap, Map<Long, List<DevicePort>> portsByDevice,
            Map<Long, DevicePort> portById) {
        List<AssetInfoResponse.TopologyNode> nodes = new ArrayList<>();
        List<Link> sortedLinks = new ArrayList<>(snap.links);
        sortedLinks.sort(Comparator.comparing(Link::id));

        List<Device> sortedDevices = new ArrayList<>(snap.devices);
        sortedDevices.sort(Comparator.comparing(Device::id));
        int nicCount = 0;
        int portCount = 0;
        for (Device device : sortedDevices) {
            String deviceNodeId = "dev:" + device.id();
            nodes.add(new AssetInfoResponse.TopologyNode(deviceNodeId,
                    deviceName(device, device.name(), snap.deviceAliases),
                    "device", null, device.enabled()));
            List<DevicePort> ports = portsByDevice.getOrDefault(device.id(), List.of());
            Map<String, List<DevicePort>> byNic = new LinkedHashMap<>();
            for (DevicePort port : ports) {
                byNic.computeIfAbsent(nicKey(port), k -> new ArrayList<>()).add(port);
            }
            byNic.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator
                            .comparingInt((String k) -> nicIndex(byNic.get(k)))
                            .thenComparing(Comparator.naturalOrder())))
                    .forEach(entry -> {
                        String nicNodeId = deviceNodeId + "/nic:" + entry.getKey();
                        nodes.add(new AssetInfoResponse.TopologyNode(nicNodeId, entry.getKey(),
                                "nic", deviceNodeId, device.enabled()));
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(DevicePort::portName,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                .forEach(port -> nodes.add(new AssetInfoResponse.TopologyNode(
                                        nicNodeId + "/port:" + port.portName(),
                                        port.portName(), "port", nicNodeId, device.enabled())));
                    });
            nicCount += byNic.size();
            portCount += ports.size();
        }

        List<AssetInfoResponse.TopologyEdge> edges = new ArrayList<>();
        for (Link link : sortedLinks) {
            String from = portNodeId(portById.get(link.aPortId()));
            String to = portNodeId(portById.get(link.bPortId()));
            if (from != null && to != null) {
                edges.add(new AssetInfoResponse.TopologyEdge(from, to, link.id(),
                        link.linkType(), link.speedGbps()));
            }
        }
        AssetInfoResponse.TopologyStats stats = new AssetInfoResponse.TopologyStats(
                sortedDevices.size(), nicCount, portCount, edges.size());
        return new AssetInfoResponse.Topology(List.copyOf(nodes), List.copyOf(edges), stats);
    }

    private static String portNodeId(DevicePort port) {
        if (port == null) {
            return null;
        }
        return "dev:" + port.deviceId() + "/nic:" + nicKey(port) + "/port:" + port.portName();
    }

    private static String nicKey(DevicePort port) {
        return port.nicName() == null || port.nicName().isBlank()
                ? UNASSIGNED_NIC : port.nicName();
    }

    private static int nicIndex(List<DevicePort> ports) {
        return ports.stream().map(DevicePort::nicIndex).filter(java.util.Objects::nonNull)
                .min(Integer::compareTo).orElse(Integer.MAX_VALUE);
    }

    private static String linkFragment(Link link, Map<Long, DevicePort> portById) {
        DevicePort a = portById.get(link.aPortId());
        DevicePort b = portById.get(link.bPortId());
        return String.join("|",
                String.valueOf(link.id()), nullSafe(link.name()), nullSafe(link.linkType()),
                nullSafe(link.protocol()), nullSafe(link.speedGbps()), nullSafe(link.mtu()),
                String.valueOf(link.enabled()), nullSafe(link.aDeviceId()),
                nullSafe(link.bDeviceId()), portPart(a), portPart(b));
    }

    private static String portPart(DevicePort port) {
        if (port == null) {
            return "-";
        }
        return String.join(":",
                String.valueOf(port.id()), nullSafe(port.portName()), nullSafe(port.nicName()),
                nullSafe(port.nicIndex()), nullSafe(port.portStatus()), nullSafe(port.macAddress()),
                nullSafe(port.ipAddress()));
    }

    /** 资产集指纹：设备/端口/链路内容参与，别名不参与。 */
    static String fingerprint(Snapshot snap) {
        StringBuilder sb = new StringBuilder();
        snap.devices.stream().sorted(Comparator.comparing(Device::id)).forEach(d -> sb
                .append("D").append(d.id()).append(':').append(nullSafe(d.name())).append(':')
                .append(d.enabled()).append(':').append(nullSafe(d.deviceType())).append(':')
                .append(nullSafe(d.model())).append(':').append(nullSafe(d.vendor())).append(':')
                .append(nullSafe(d.managementIp())).append(':').append(nullSafe(d.location()))
                .append(':').append(nullSafe(d.rackNo())).append(';'));
        snap.ports.stream().sorted(Comparator.comparing(DevicePort::id))
                .forEach(p -> sb.append("P").append(portPart(p)).append(';'));
        snap.links.stream().sorted(Comparator.comparing(Link::id))
                .forEach(l -> sb.append("L").append(nullSafe(l.id())).append(':')
                        .append(nullSafe(l.name())).append(':').append(nullSafe(l.linkType()))
                        .append(':').append(nullSafe(l.protocol())).append(':')
                        .append(nullSafe(l.speedGbps())).append(':').append(nullSafe(l.mtu()))
                        .append(':').append(l.enabled()).append(':')
                        .append(nullSafe(l.aPortId())).append(':').append(nullSafe(l.bPortId()))
                        .append(';'));
        return hash(sb.toString());
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** 一次读取的资产快照，避免在多次派生里重复查库。 */
    static final class Snapshot {
        List<Device> devices = List.of();
        List<DevicePort> ports = List.of();
        List<Link> links = List.of();
        Map<Long, String> deviceAliases = Map.of();
    }
}
