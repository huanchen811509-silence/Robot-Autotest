package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.LinkRepository;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.asset.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连接/拓扑缓存单测（v4.3.0 / FR-v4.3-05）。
 *
 * <p>三条硬约束：别名不进缓存键、资产变更即失效、同一键并发只算一次。
 */
class AssetInfoCacheTest {

    private static final long PROJECT = 21L;

    private DeviceRepository devices;
    private LinkRepository links;
    private ProjectAssetRepository projectAssets;
    private AssetInfoProperties props;
    private AtomicLong clock;
    private AssetInfoService service;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        devices = mock(DeviceRepository.class);
        links = mock(LinkRepository.class);
        projectAssets = mock(ProjectAssetRepository.class);
        when(projectAssets.aliases(anyLong(), anyString())).thenReturn(Map.of());
        props = new AssetInfoProperties();
        clock = new AtomicLong(0);
        service = new AssetInfoService(devices, links, projectAssets, props, clock::get);
        pool = Executors.newFixedThreadPool(4);
        stubBase();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private void stubBase() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", true), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "l1", 1, 10, 2, 20)));
    }

    @Test
    @DisplayName("第二次读取命中缓存，第一次未命中")
    void 命中缓存() {
        AssetInfoResponse first = service.get(PROJECT, false);
        AssetInfoResponse second = service.get(PROJECT, false);

        assertThat(first.meta().cacheHit()).isFalse();
        assertThat(second.meta().cacheHit()).isTrue();
        assertThat(second.meta().ttlSeconds()).isEqualTo(43200);
        assertThat(second.meta().source()).isEqualTo(AssetInfoService.SOURCE_INTERNAL);
        assertThat(second.connections().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("资产变更（端口改名）→ 指纹变化 → 不命中且返回新数据")
    void 资产变更立即失效() {
        AssetInfoResponse first = service.get(PROJECT, false);
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth9", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));

        AssetInfoResponse after = service.get(PROJECT, false);

        assertThat(after.meta().cacheHit()).isFalse();
        assertThat(after.connections().items().get(0).aPort()).isEqualTo("eth9");
        assertThat(first.connections().items().get(0).aPort()).isEqualTo("eth1");
    }

    @Test
    @DisplayName("显式失效后同一指纹也不再命中")
    void 手工失效() {
        service.get(PROJECT, false);

        service.invalidate(PROJECT);

        assertThat(service.get(PROJECT, false).meta().cacheHit()).isFalse();
    }

    @Test
    @DisplayName("12 小时窗口：未到期命中，到期后重算")
    void 窗口到期() {
        service.get(PROJECT, false);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(43199));
        assertThat(service.get(PROJECT, false).meta().cacheHit()).isTrue();

        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertThat(service.get(PROJECT, false).meta().cacheHit()).isFalse();
    }

    @Test
    @DisplayName("force=true 绕过窗口强制重算，false 遵循窗口")
    void 强制刷新() {
        service.get(PROJECT, false);

        assertThat(service.get(PROJECT, true).meta().cacheHit()).isFalse();
    }

    @Test
    @DisplayName("别名不进缓存键：同一资产不同别名指纹相同")
    void 别名不进缓存键() {
        AssetInfoService.Snapshot snap = new AssetInfoService.Snapshot();
        snap.devices = List.of(dev(1, "sw-01", true));
        snap.ports = List.of(port(10, 1, "eth1", "nic0", 0));
        snap.links = List.of(link(1, "l1", 1, 10, 1, 10));
        snap.deviceAliases = Map.of();

        String withOriginal = AssetInfoService.fingerprint(snap);
        snap.deviceAliases = Map.of(1L, "alias-b");

        assertThat(AssetInfoService.fingerprint(snap)).isEqualTo(withOriginal);
    }

    @Test
    @DisplayName("同一项目同一键并发只算一次：全部拿到同一个 generatedAt")
    void 并发单飞() throws Exception {
        when(devices.portsOfVisibleDevices(PROJECT)).thenAnswer(invocation -> {
            Thread.sleep(80);
            return List.of(port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0));
        });
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssetInfoResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.get(PROJECT, false);
            }));
        }
        start.countDown();

        AssetInfoResponse first = futures.get(0).get(10, TimeUnit.SECONDS);
        for (Future<AssetInfoResponse> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS).generatedAt()).isEqualTo(first.generatedAt());
        }
    }

    @Test
    @DisplayName("缓存关闭：结果与开启时一致，仅不再命中")
    void 关闭缓存等价() {
        AssetInfoProperties offProps = new AssetInfoProperties();
        offProps.setEnabled(false);
        AssetInfoService off = new AssetInfoService(devices, links, projectAssets, offProps,
                clock::get);
        AssetInfoResponse first = off.get(PROJECT, false);
        AssetInfoResponse second = off.get(PROJECT, false);

        assertThat(second.meta().cacheHit()).isFalse();
        assertThat(second.connections().items()).isEqualTo(first.connections().items());
        assertThat(second.topology().nodes()).isEqualTo(first.topology().nodes());
    }

    private static Device dev(long id, String name, boolean enabled) {
        return new Device(id, PROJECT, name, "SWITCH", "S6850", "H3C", "SN-" + id, "10.0.0." + id,
                "A 机房", "R01", "core", enabled, null, null);
    }

    private static DevicePort port(long id, long deviceId, String portName, String nicName,
                                   Integer nicIndex) {
        return new DevicePort(id, deviceId, portName, "SFP+", "ETHERNET", "00:11:22:33:44:5" + id,
                25, 1500, "10.0.0." + deviceId, "UP", nicName, nicIndex);
    }

    private static Link link(long id, String name, long aDevice, long aPort, long bDevice, long bPort) {
        return new Link(id, PROJECT, name, "eth", "ROCEE", 100, 1500,
                aDevice, "dev-" + aDevice, aPort, "port-" + aPort,
                bDevice, "dev-" + bDevice, bPort, "port-" + bPort, "", true);
    }
}
