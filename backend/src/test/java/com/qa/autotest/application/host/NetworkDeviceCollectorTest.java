package com.qa.autotest.application.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.host.HostInventory;
import com.qa.autotest.domain.host.HostInventoryItem;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网络设备采集器离线验收（v4.12.0 / FR-v4.12-07）。
 *
 * <p><b>夹具是现场真实输出</b>（2026-11-11 从 10.202.54.91 / .92 / .93 / .100 取回），
 * 不是手写的理想格式。这台设备上的两个坑都是这么发现的：
 * <ul>
 *   <li>centecOS <b>没有</b> {@code show interface brief}（返回
 *       {@code % Line has invalid autocommand}），要用 {@code show interface status}；</li>
 *   <li>Ruijie 有 {@code show interface brief}，但输出里混着 VLAN / loopback / Mgmt 接口，
 *       不能一律当物理端口计数。</li>
 * </ul>
 */
class NetworkDeviceCollectorTest {

    /** 10.202.54.91（E780 / 32DE）：centecOS 7.0.5.70。 */
    private static final String CENTEC_VERSION = """
            centecOS Software, E780, Version 7.0.5.70
            Copyright (C) 2005-2026 Centec Communications.  All Rights Reserved.
            The current running image is flash:/boot/centecOS-e780-v7.0.5.70.sp01.r.bin

            L1-12472F-s-rTQFuU-91 uptime is 8 days, 3 hours, 12 minutes
            Hardware Type is 32DE
            Hardware Version is 2.2
            SDRAM size 4096M
            Flash size 8192M
            EPLD Version is 1.5
            BootRom Version is A.1.28
            System serial number is E928GD264002
            Current Web Version is 7.5.6.alpha.4
            """;

    /** 10.202.54.91 `show interface status`（截取代表性行）。 */
    private static final String CENTEC_STATUS = """
            Port             Status     Duplex  Speed    Mode    Type                    Description
            ---------------------------------------------------------------------------------------------------------
            eth-0-1/1        down       auto    auto     ACCESS  800GBASE-VR8
            eth-0-1/2        down       auto    auto     ACCESS  800GBASE-VR8
            eth-0-2/1        up         a-full  a-400G   ACCESS  800GBASE-VR8
            eth-0-2/2        up         a-full  a-400G   TRUNK   800GBASE-VR8
            """;

    /** 10.202.54.93（S6510-48VS8CQ）：Ruijie RGOS。 */
    private static final String RUIJIE_VERSION = """


            System description      : Ruijie Full 25G Routing Switch(S6510-48VS8CQ) By Ruijie Networks
            System start time       : 2026-10-07 10:31:57
            System uptime           : 35:11:43:01
            System hardware version : 2.34
            System software version : S6500_RGOS 11.0(5)B9P108
            System patch number     : NA
            System serial number    : G1S92H5000388
            System boot version     : 1.3.11
            """;

    /** 10.202.54.93 `show interface brief`（含 VLAN / loopback / Mgmt）。 */
    private static final String RUIJIE_BRIEF = """


            down: link down
            *down: administratively down
            disabled: err-disabled(Please reference to command [show interfaces status err-disabled] for detail.)
            Interface     Link Stat     Protocol Stat  Input Usage       Output Usage      inErrors      outErrors
            ------------  ------------  -------------  ----------------  ----------------  ------------  ------------
            TF0/1         up            up             0.01%             0.01%             0             0
            TF0/2         up            up             0.01%             0.01%             0             0
            TF0/3         down          down           0.00%             0.00%             0             0
            Lo1           up            up             --                --                0             0
            Vl1000        up            down           --                --                0             0
            Mg0           up            up             --                --                0             0
            """;

    private static HostCommandRunner runner(Map<String, String> outputs,
                                            Map<String, String> failures) {
        return new HostCommandRunner() {
            @Override
            public Outcome run(String host, String command, Duration timeout) {
                if (failures != null && failures.containsKey("*")) {
                    return Outcome.fail(failures.get("*"));
                }
                if (failures != null && failures.containsKey(command)) {
                    return Outcome.fail(failures.get(command));
                }
                String stdout = outputs.get(command);
                return stdout == null ? Outcome.fail("unexpected command: " + command) : Outcome.ok(stdout);
            }
        };
    }

    private static HostInventory collect(Map<String, String> outputs) {
        return new NetworkDeviceCollector(runner(outputs, null))
                .collect("10.202.54.91", 7L, access());
    }

    private static HostAccess access() {
        return new HostAccess(1L, "ehtadmin", 9L, "secret", "network",
                "KexAlgorithms=+diffie-hellman-group14-sha1");
    }

    private static Object valueOf(HostInventory inventory, String category) {
        return inventory.items().stream()
                .filter(item -> category.equals(item.category()))
                .filter(HostInventoryItem::collected)
                .map(HostInventoryItem::value)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("centecOS：型号 / 版本 / 序列号 / 运行时长全部取自 show version")
    void centecFields() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, CENTEC_VERSION);
        outputs.put(NetworkDeviceCollector.CMD_INTERFACE_CENTEC, CENTEC_STATUS);

        HostInventory inventory = collect(outputs);

        assertThat(valueOf(inventory, "NET_VENDOR")).isEqualTo("centec");
        assertThat(valueOf(inventory, "NET_MODEL")).isEqualTo("E780");
        assertThat(valueOf(inventory, "NET_VERSION")).isEqualTo("7.0.5.70");
        assertThat(valueOf(inventory, "NET_SERIAL")).isEqualTo("E928GD264002");
        assertThat(valueOf(inventory, "NET_UPTIME")).isEqualTo("8 days, 3 hours, 12 minutes");
        assertThat(inventory.status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("centecOS 端口表：物理口 4 个、up 2 个，速率取第 4 列")
    void centecPorts() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, CENTEC_VERSION);
        outputs.put(NetworkDeviceCollector.CMD_INTERFACE_CENTEC, CENTEC_STATUS);

        NetworkDeviceCollector.PortSummary ports =
                (NetworkDeviceCollector.PortSummary) valueOf(collect(outputs), "NET_PORTS");

        assertThat(ports.physical()).isEqualTo(4);
        assertThat(ports.up()).isEqualTo(2);
        assertThat(ports.logical()).isZero();
        assertThat(ports.ports()).hasSize(4);
        assertThat(ports.ports().get(2)).containsEntry("name", "eth-0-2/1")
                .containsEntry("state", "up").containsEntry("speed", "a-400G");
    }

    @Test
    @DisplayName("Ruijie：型号取括号内的 S6510-48VS8CQ，版本保留整串")
    void ruijieFields() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, RUIJIE_VERSION);
        outputs.put(NetworkDeviceCollector.CMD_INTERFACE_RUIJIE, RUIJIE_BRIEF);

        HostInventory inventory = collect(outputs);

        assertThat(valueOf(inventory, "NET_VENDOR")).isEqualTo("ruijie");
        assertThat(valueOf(inventory, "NET_MODEL")).isEqualTo("S6510-48VS8CQ");
        assertThat(valueOf(inventory, "NET_VERSION")).isEqualTo("S6500_RGOS 11.0(5)B9P108");
        assertThat(valueOf(inventory, "NET_SERIAL")).isEqualTo("G1S92H5000388");
        assertThat(valueOf(inventory, "NET_UPTIME")).isEqualTo("35:11:43:01");
    }

    @Test
    @DisplayName("Ruijie 端口表：VLAN / loopback / Mgmt 不计入物理口")
    void ruijiePorts() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, RUIJIE_VERSION);
        outputs.put(NetworkDeviceCollector.CMD_INTERFACE_RUIJIE, RUIJIE_BRIEF);

        NetworkDeviceCollector.PortSummary ports =
                (NetworkDeviceCollector.PortSummary) valueOf(collect(outputs), "NET_PORTS");

        // TF0/1 上、TF0/2 上、TF0/3 下 = 3 个物理口 / 2 个 up
        assertThat(ports.physical()).isEqualTo(3);
        assertThat(ports.up()).isEqualTo(2);
        // Lo1 / Vl1000 / Mg0
        assertThat(ports.logical()).isEqualTo(3);
    }

    @Test
    @DisplayName("接口命令失败：端口项缺失且原因归到 command_unsupported，其余项仍采到")
    void interfaceCommandUnsupported() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, CENTEC_VERSION);
        Map<String, String> failures = new HashMap<>();
        failures.put(NetworkDeviceCollector.CMD_INTERFACE_CENTEC,
                "% Line has invalid autocommand : show interface status");

        HostInventory inventory = new NetworkDeviceCollector(runner(outputs, failures))
                .collect("10.202.54.91", null, access());

        HostInventoryItem ports = inventory.items().stream()
                .filter(item -> "NET_PORTS".equals(item.category())).findFirst().orElseThrow();
        assertThat(ports.collected()).isFalse();
        assertThat(ports.reason()).startsWith(CollectFailure.COMMAND);
        assertThat(valueOf(inventory, "NET_MODEL")).isEqualTo("E780");
        assertThat(inventory.status()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("整台连不上：所有项都失败，状态 FAILED 且原因是 auth_failed")
    void authFailureMarksFailed() {
        Map<String, String> failures = new HashMap<>();
        failures.put("*", "Permission denied (password).");

        HostInventory inventory = new NetworkDeviceCollector(runner(Map.of(), failures))
                .collect("10.202.54.91", null, access());

        assertThat(inventory.status()).isEqualTo("FAILED");
        assertThat(inventory.items()).allSatisfy(item -> {
            assertThat(item.collected()).isFalse();
            assertThat(item.reason()).startsWith(CollectFailure.AUTH);
        });
    }

    @Test
    @DisplayName("解析器对空输入返回 null，不抛异常")
    void tolerantParsers() {
        assertThat(NetworkDeviceCollector.parsePorts(null)).isNull();
        assertThat(NetworkDeviceCollector.parsePorts("")).isNull();
        assertThat(NetworkDeviceCollector.parsePorts("no table here")).isNull();
        assertThat(NetworkDeviceCollector.parseCentecModel(null)).isNull();
        assertThat(NetworkDeviceCollector.parseRuijieVersion(null)).isNull();
        // 空输出 = 没采到，不能返回 generic（否则整台设备的结论会从 FAILED 降成 PARTIAL）
        assertThat(NetworkDeviceCollector.parseVendor(null)).isNull();
        assertThat(NetworkDeviceCollector.parseVendor("")).isNull();
        assertThat(NetworkDeviceCollector.parseVendor("Some Unknown Vendor OS")).isEqualTo("generic");
    }

    @Test
    @DisplayName("项顺序固定：报告里不能一会儿厂商在前一会儿端口在前")
    void itemOrderIsStable() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put(NetworkDeviceCollector.CMD_VERSION, CENTEC_VERSION);
        outputs.put(NetworkDeviceCollector.CMD_INTERFACE_CENTEC, CENTEC_STATUS);

        List<String> categories = collect(outputs).items().stream()
                .map(HostInventoryItem::category).toList();

        assertThat(categories).containsExactly(
                "NET_VENDOR", "NET_MODEL", "NET_VERSION", "NET_SERIAL", "NET_UPTIME", "NET_PORTS");
    }
}
