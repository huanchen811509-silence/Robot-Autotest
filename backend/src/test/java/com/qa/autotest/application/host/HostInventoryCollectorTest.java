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
 * FR-v3.11-01 的离线解析验收（判据① ② ③）。
 *
 * <p>**为什么用夹具而不是连真机**：真机只能证明「这台机器今天长这样」，证明不了
 * 「输出换一种写法还认不认得」——而现场的输出一定会有别的写法（不同发行版、无 GPU、
 * 无 RoCE、`lsblk` 版本差异）。夹具能把这些分支一个个摆出来。
 *
 * <p>夹具内容取自真实命令输出（Ubuntu 24.04 / 无 GPU / 无 HCA 的普通机器），
 * 各分支在此基础上做最小改动。
 */
class HostInventoryCollectorTest {

    private static final String OS = "PRETTY_NAME=\"Ubuntu 24.04.3 LTS\"\nNAME=\"Ubuntu\"\n";
    private static final String KERNEL = "6.6.87.2-microsoft-standard-WSL2\n";
    private static final String CPU = "16\nModel name:            Intel(R) Core(TM) i7-13700H\n"
            + "CPU(s):                   16\n";
    private static final String MEMORY = "               total        used        free      shared  "
            + "buff/cache   available\nMem:           15784        5241        1200         123        "
            + "9543       10123\nSwap:           4096           0        4096\n";
    private static final String DISK = "/dev/sdc        1007G  466G  491G  49% /\n---\n"
            + "NAME   SIZE ROTA\nsdc   1007G    0\nsda    476G    1\n";
    private static final String NIC = "lo               UNKNOWN        00:00:00:00:00:00 <LOOPBACK,UP>\n"
            + "eth0             UP             00:15:5d:11:22:33 <BROADCAST,MULTICAST,UP>\n"
            + "lo 0\neth0 10000\n";
    private static final String IP = "lo               UNKNOWN        127.0.0.1/8 ::1/128 \n"
            + "eth0             UP             10.202.54.106/23 fe80::215:5dff:fe11:2233/64 \n";
    private static final String ROCE = "/sys/class/infiniband/mlx5_0/ports/1|4: ACTIVE|200 Gb/sec\n"
            + "/sys/class/infiniband/mlx5_0/ports/2|4: DOWN|200 Gb/sec\n";
    private static final String ROUTE = "default via 10.202.54.1 dev eth0 proto kernel\n"
            + "10.202.54.0/23 dev eth0 proto kernel scope link src 10.202.54.106\n";

    /** 按命令回放夹具；`override` 可以让某类命令失败。 */
    private static HostInventoryCollector collector(Map<String, HostCommandRunner.Outcome> override) {
        Map<String, String> outputs = new HashMap<>();
        outputs.put("OS", OS);
        outputs.put("KERNEL", KERNEL);
        outputs.put("CPU", CPU);
        outputs.put("MEMORY", MEMORY);
        outputs.put("DISK", DISK);
        outputs.put("NIC", NIC);
        outputs.put("IP", IP);
        outputs.put("ROCE", ROCE);
        outputs.put("ROUTE", ROUTE);
        HostCommandRunner runner = (host, command, timeout) -> {
            if (override.containsKey(command)) {
                return override.get(command);
            }
            for (Map.Entry<String, String> entry : outputs.entrySet()) {
                if (HostInventoryCollector.COMMANDS.get(entry.getKey()).equals(command)) {
                    return HostCommandRunner.Outcome.ok(entry.getValue());
                }
            }
            return HostCommandRunner.Outcome.fail("夹具未覆盖该命令: " + command);
        };
        return new HostInventoryCollector(runner);
    }

    private static HostInventoryItem item(HostInventory inventory, String category) {
        return inventory.items().stream()
                .filter(it -> category.equals(it.category()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("10 类齐全、顺序固定、每项都带命令与采集时间（判据①②）")
    void 十类齐全且每项带命令与时间() {
        HostInventory inventory = collector(Map.of()).collect("localhost", 7L);

        assertThat(inventory.items()).extracting(HostInventoryItem::category)
                .containsExactlyElementsOf(HostInventory.CATEGORIES);
        assertThat(inventory.items()).hasSize(10);
        assertThat(inventory.items()).allSatisfy(it -> {
            assertThat(it.command()).as("每项必须记下实际执行的命令").isNotBlank();
            assertThat(it.collectedAt()).as("每项必须记下采集时刻").isNotNull();
        });
        assertThat(inventory.host()).isEqualTo("localhost");
        assertThat(inventory.deviceId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("OS / 内核 / CPU / 内存 / 磁盘 / 路由 解析出真值")
    void 基础六类解析() {
        HostInventory inventory = collector(Map.of()).collect("localhost", null);

        assertThat(item(inventory, "OS").value()).isEqualTo("Ubuntu 24.04.3 LTS");
        assertThat(item(inventory, "KERNEL").value()).isEqualTo("6.6.87.2-microsoft-standard-WSL2");

        @SuppressWarnings("unchecked")
        Map<String, Object> cpu = (Map<String, Object>) item(inventory, "CPU").value();
        assertThat(cpu).containsEntry("cores", 16);
        assertThat((String) cpu.get("model")).contains("i7-13700H");

        @SuppressWarnings("unchecked")
        Map<String, Object> mem = (Map<String, Object>) item(inventory, "MEMORY").value();
        assertThat(mem).containsEntry("totalMb", 15784L).containsEntry("availableMb", 10123L);

        @SuppressWarnings("unchecked")
        Map<String, Object> disk = (Map<String, Object>) item(inventory, "DISK").value();
        assertThat(disk).containsKey("root");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) disk.get("disks");
        assertThat(disks).hasSize(2);
        assertThat(disks.get(0)).containsEntry("name", "sdc").containsEntry("type", "SSD");
        assertThat(disks.get(1)).containsEntry("type", "HDD");

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) item(inventory, "ROUTE").value();
        assertThat((String) route.get("defaultRoute")).contains("10.202.54.1");
    }

    @Test
    @DisplayName("网卡合并速度表、IP 分出接口与地址、RoCE 拆设备/端口/速率")
    void 网络三类解析() {
        HostInventory inventory = collector(Map.of()).collect("localhost", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nics = (List<Map<String, Object>>) item(inventory, "NIC").value();
        assertThat(nics).hasSize(2);
        Map<String, Object> eth0 = nics.stream()
                .filter(n -> "eth0".equals(n.get("name"))).findFirst().orElseThrow();
        assertThat(eth0).containsEntry("state", "UP").containsEntry("speedMbps", 10000L);
        // lo 的速度读到 0，保留 0 而不是当没采到——0 是这台机器的真实事实
        Map<String, Object> lo = nics.stream()
                .filter(n -> "lo".equals(n.get("name"))).findFirst().orElseThrow();
        assertThat(lo).containsEntry("speedMbps", 0L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ips = (List<Map<String, Object>>) item(inventory, "IP").value();
        Map<String, Object> ipEth0 = ips.stream()
                .filter(n -> "eth0".equals(n.get("interface"))).findFirst().orElseThrow();
        assertThat((List<Object>) ipEth0.get("addresses")).contains("10.202.54.106/23");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ports = (List<Map<String, Object>>) item(inventory, "ROCE").value();
        assertThat(ports).hasSize(2);
        assertThat(ports.get(0)).containsEntry("device", "mlx5_0").containsEntry("port", "1")
                .containsEntry("state", "ACTIVE").containsEntry("rateGbps", 200);
        assertThat(ports.get(1)).containsEntry("state", "DOWN");
    }

    @Test
    @DisplayName("没 GPU：写「未采集到 + 原因」，不填 0、不让整份清单失败")
    void 无GPU时记原因而不是假值() {
        String gpuCommand = HostInventoryCollector.COMMANDS.get("GPU");
        HostInventory inventory = collector(Map.of(gpuCommand,
                new HostCommandRunner.Outcome(127, "",
                        "sh: 1: nvidia-smi: not found"))).collect("localhost", null);

        HostInventoryItem gpu = item(inventory, "GPU");
        assertThat(gpu.collected()).isFalse();
        assertThat(gpu.value()).isNull();
        assertThat(gpu.reason()).contains("nvidia-smi").contains("未采集到 GPU");
        // 一项缺失不能把整份清单打成 FAILED
        assertThat(inventory.status()).isEqualTo(HostInventory.STATUS_PARTIAL);
        assertThat(inventory.items().stream().filter(HostInventoryItem::collected).count())
                .isEqualTo(9);
    }

    @Test
    @DisplayName("没 RoCE 设备：给出「本机无 HCA」的原因")
    void 无RoCE时记原因() {
        String roceCommand = HostInventoryCollector.COMMANDS.get("ROCE");
        HostInventory inventory = collector(Map.of(roceCommand,
                HostCommandRunner.Outcome.ok(""))).collect("localhost", null);

        HostInventoryItem roce = item(inventory, "ROCE");
        assertThat(roce.collected()).isFalse();
        assertThat(roce.reason()).contains("RoCE");
    }

    @Test
    @DisplayName("命令全失败：状态 FAILED，且每项都有原因")
    void 全部失败时状态为FAILED() {
        HostCommandRunner runner = (host, command, timeout) ->
                HostCommandRunner.Outcome.fail("ssh: connect to host localhost port 22: refused");
        HostInventory inventory = new HostInventoryCollector(runner).collect("localhost", null);

        assertThat(inventory.status()).isEqualTo(HostInventory.STATUS_FAILED);
        assertThat(inventory.collectedCount()).isZero();
        assertThat(inventory.items()).allSatisfy(it -> assertThat(it.reason()).isNotBlank());
    }

    @Test
    @DisplayName("解析器边界：残缺输出返回 null（交给调用方记原因），不抛异常")
    void 解析器对残缺输出返回null() {
        assertThat(HostInventoryCollector.parseOs(null)).isNull();
        assertThat(HostInventoryCollector.parseOs("NAME=Ubuntu\n")).isNull();
        assertThat(HostInventoryCollector.parseKernel("   ")).isNull();
        assertThat(HostInventoryCollector.parseCpu("")).isNull();
        assertThat(HostInventoryCollector.parseMemory("Swap: 0 0 0\n")).isNull();
        assertThat(HostInventoryCollector.parseDisk("")).isNull();
        assertThat(HostInventoryCollector.parseGpu(null)).isNull();
        assertThat(HostInventoryCollector.parseNic(null)).isNull();
        assertThat(HostInventoryCollector.parseIp("")).isNull();
        assertThat(HostInventoryCollector.parseRoce("")).isNull();
        assertThat(HostInventoryCollector.parseRoute("   \n")).isNull();
    }

    @Test
    @DisplayName("速率换算：Gb/sec 直取、Mb/sec 折成 Gb、无法识别返回 null（不写 0）")
    void 速率换算() {
        assertThat(HostInventoryCollector.rateGbps("200 Gb/sec")).isEqualTo(200);
        assertThat(HostInventoryCollector.rateGbps("40 Gb/sec")).isEqualTo(40);
        assertThat(HostInventoryCollector.rateGbps("1000 Mb/sec")).isEqualTo(1);
        assertThat(HostInventoryCollector.rateGbps("")).isNull();
        assertThat(HostInventoryCollector.rateGbps("unknown")).isNull();
    }

    @Test
    @DisplayName("命令表是只读白名单：不含任何写操作关键字")
    void 命令表只读() {
        List<String> forbidden = List.of(" rm ", " mv ", " mkfs", " dd ", " tee ",
                "chmod", "chown", "reboot", "shutdown", "systemctl", "kill", "mount");
        for (Map.Entry<String, String> entry : HostInventoryCollector.COMMANDS.entrySet()) {
            String command = entry.getValue();
            assertThat(command).as(entry.getKey() + " 的命令为空").isNotBlank();
            for (String token : forbidden) {
                assertThat(command).as(entry.getKey() + " 含禁用关键字 " + token)
                        .doesNotContain(token);
            }
            // 重定向只允许丢弃 stderr（2>/dev/null）；把输出写进文件就可能是写操作
            String withoutNull = command.replace("2>/dev/null", "").replace(">/dev/null", "");
            assertThat(withoutNull).as(entry.getKey() + " 含输出重定向（可能写文件）")
                    .doesNotContain(">");
        }
        assertThat(HostInventoryCollector.COMMANDS).hasSize(10);
    }

    /**
     * 回归自现场事故（v4.12.0 W-D）：5 台浪潮机器内存整类判「输出无法解析」。
     *
     * <p>根因是远端默认 locale 为 zh_CN，`free -m` 把表头输出成「内存：」，
     * 而内存解析靠的是 `Mem:` 这一行。ssh 用 `-F /dev/null` 绕开了系统
     * ssh_config，`SendEnv LC_ALL` 也就不生效，所以语言必须由采集方自己钉住。
     */
    @Test
    @DisplayName("每条远端命令都自带 locale 固定前缀（否则远端中文输出会把解析带偏）")
    void commandsForceRemoteLocale() {
        for (Map.Entry<String, String> entry : HostInventoryCollector.COMMANDS.entrySet()) {
            assertThat(entry.getValue())
                    .as(entry.getKey() + " 的命令没有固定远端 locale")
                    .startsWith("export LC_ALL=C; ");
        }
    }

    @Test
    @DisplayName("中文 free 输出解析不出内存（这就是必须固定 locale 的原因）")
    void localizedFreeOutputIsUnparsable() {
        assertThat(HostInventoryCollector.parseMemory(
                "               total        used        free\n内存：       1031735       13250      998485\n"))
                .isNull();
        assertThat(HostInventoryCollector.COMMANDS.get("MEMORY")).startsWith("export LC_ALL=C; ");
    }

    @Test
    @DisplayName("超时 Duration 是常量，不接受调用方传入（避免某处传 0 变成无限等）")
    void 超时是常量() {
        assertThat(HostInventoryCollector.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }
}
