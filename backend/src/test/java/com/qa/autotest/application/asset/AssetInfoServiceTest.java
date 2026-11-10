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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连接/拓扑派生单测（v4.3.0 / FR-v4.3-03、FR-v4.3-04）。
 *
 * <p>覆盖：字段齐全、未归属网卡、稳定排序、禁用标记、别名套用、拓扑统计自洽。
 */
class AssetInfoServiceTest {

    private static final long PROJECT = 11L;

    private DeviceRepository devices;
    private LinkRepository links;
    private ProjectAssetRepository projectAssets;
    private AssetInfoService service;

    @BeforeEach
    void setUp() {
        devices = mock(DeviceRepository.class);
        links = mock(LinkRepository.class);
        projectAssets = mock(ProjectAssetRepository.class);
        when(projectAssets.aliases(anyLong(), anyString())).thenReturn(Map.of());
        service = new AssetInfoService(devices, links, projectAssets, new AssetInfoProperties());
    }

    @Test
    @DisplayName("一条链路 → 一条连接：链路与 A/B 端设备/网卡/接口字段齐全")
    void 连接字段齐全() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", true), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "sw-01--srv-01", 1, 10, 2, 20, true)));

        AssetInfoResponse response = service.get(PROJECT, false);

        assertThat(response.connections().count()).isEqualTo(1);
        AssetInfoResponse.ConnectionItem item = response.connections().items().get(0);
        assertThat(item.linkId()).isEqualTo(1L);
        assertThat(item.linkName()).isEqualTo("sw-01--srv-01");
        assertThat(item.aDevice()).isEqualTo("sw-01");
        assertThat(item.aNic()).isEqualTo("nic0");
        assertThat(item.aPort()).isEqualTo("eth1");
        assertThat(item.bDevice()).isEqualTo("srv-01");
        assertThat(item.bNic()).isEqualTo("nic0");
        assertThat(item.bPort()).isEqualTo("eth0");
        assertThat(item.protocol()).isEqualTo("ROCEE");
        assertThat(item.speedGbps()).isEqualTo(100);
        assertThat(item.mtu()).isEqualTo(1500);
        assertThat(item.portStatus()).isEqualTo("UP");
        assertThat(item.enabled()).isTrue();
    }

    @Test
    @DisplayName("端口未填网卡归属：归入「未归属」、不报错、不丢数据")
    void 未归属网卡() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", true), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", null, null), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "l1", 1, 10, 2, 20, true)));

        AssetInfoResponse response = service.get(PROJECT, false);

        assertThat(response.connections().count()).isEqualTo(1);
        assertThat(response.connections().items().get(0).aNic()).isNull();
        assertThat(response.topology().nodes())
                .anyMatch(n -> n.type().equals("nic") && n.name().equals("未归属"));
        assertThat(response.topology().stats().portCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("拓扑：设备→网卡→接口三级、边为端口级 id、统计与集合自洽")
    void 拓扑结构与统计() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", true), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "l1", 1, 10, 2, 20, true)));

        AssetInfoResponse.Topology topology = service.get(PROJECT, false).topology();

        assertThat(topology.nodes()).anyMatch(n -> n.id().equals("dev:1") && n.parent() == null);
        assertThat(topology.nodes()).anyMatch(n -> n.id().equals("dev:1/nic:nic0")
                && n.parent().equals("dev:1"));
        assertThat(topology.nodes()).anyMatch(n -> n.id().equals("dev:1/nic:nic0/port:eth1")
                && n.parent().equals("dev:1/nic:nic0"));
        assertThat(topology.edges()).hasSize(1);
        assertThat(topology.edges().get(0).from()).isEqualTo("dev:1/nic:nic0/port:eth1");
        assertThat(topology.edges().get(0).to()).isEqualTo("dev:2/nic:nic0/port:eth0");
        assertThat(topology.stats().deviceCount()).isEqualTo(2);
        assertThat(topology.stats().nicCount()).isEqualTo(2);
        assertThat(topology.stats().portCount()).isEqualTo(2);
        assertThat(topology.stats().linkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同数据连续两次顺序完全一致（nic_index 升序、名称字典序）")
    void 排序稳定() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(
                dev(2, "srv-02", true), dev(1, "sw-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(12, 1, "eth2", "nic1", 1), port(11, 1, "eth1", "nic0", 0),
                port(21, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(
                link(2, "l2", 1, 12, 2, 21, true), link(1, "l1", 1, 11, 2, 21, true)));

        AssetInfoResponse first = service.get(PROJECT, true);
        AssetInfoResponse second = service.get(PROJECT, true);

        assertThat(first.connections().items()).extracting(AssetInfoResponse.ConnectionItem::linkId)
                .containsExactly(1L, 2L);
        assertThat(first.topology().nodes()).extracting(AssetInfoResponse.TopologyNode::id)
                .containsExactly("dev:1", "dev:1/nic:nic0", "dev:1/nic:nic0/port:eth1",
                        "dev:1/nic:nic1", "dev:1/nic:nic1/port:eth2", "dev:2", "dev:2/nic:nic0",
                        "dev:2/nic:nic0/port:eth0");
        assertThat(second.connections().items()).isEqualTo(first.connections().items());
        assertThat(second.topology().nodes()).isEqualTo(first.topology().nodes());
    }

    @Test
    @DisplayName("禁用资产：仍返回并带 enabled=false 标记")
    void 禁用资产标记() {
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", false), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "l1", 1, 10, 2, 20, true)));

        AssetInfoResponse response = service.get(PROJECT, false);

        assertThat(response.connections().items().get(0).enabled()).isFalse();
        assertThat(response.topology().nodes()).anyMatch(n -> n.id().equals("dev:1") && !n.enabled());
    }

    @Test
    @DisplayName("项目别名在组装阶段套用：设备显示别名，接口名不变")
    void 别名套用() {
        when(projectAssets.aliases(PROJECT, "DEVICE")).thenReturn(Map.of(1L, "alias-a"));
        when(devices.listAll(PROJECT)).thenReturn(List.of(dev(1, "sw-01", true), dev(2, "srv-01", true)));
        when(devices.portsOfVisibleDevices(PROJECT)).thenReturn(List.of(
                port(10, 1, "eth1", "nic0", 0), port(20, 2, "eth0", "nic0", 0)));
        when(links.listAll(PROJECT)).thenReturn(List.of(link(1, "l1", 1, 10, 2, 20, true)));

        AssetInfoResponse response = service.get(PROJECT, false);

        assertThat(response.connections().items().get(0).aDevice()).isEqualTo("alias-a");
        assertThat(response.topology().nodes()).anyMatch(n -> n.id().equals("dev:1")
                && n.name().equals("alias-a"));
    }

    @Test
    @DisplayName("无资产项目：empty=true，连接与拓扑为空结构")
    void 空项目() {
        AssetInfoResponse response = service.get(PROJECT, false);

        assertThat(response.empty()).isTrue();
        assertThat(response.connections().count()).isZero();
        assertThat(response.topology().nodes()).isEmpty();
        assertThat(response.topology().edges()).isEmpty();
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

    private static Link link(long id, String name, long aDevice, long aPort, long bDevice, long bPort,
                             boolean enabled) {
        return new Link(id, PROJECT, name, "eth", "ROCEE", 100, 1500,
                aDevice, "dev-" + aDevice, aPort, "port-" + aPort,
                bDevice, "dev-" + bDevice, bPort, "port-" + bPort, "", enabled);
    }
}
