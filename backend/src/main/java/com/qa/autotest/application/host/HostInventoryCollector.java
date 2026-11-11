package com.qa.autotest.application.host;

import com.qa.autotest.domain.host.HostInventory;
import com.qa.autotest.domain.host.HostInventoryItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 主机清单采集器（FR-v3.11-01）。
 *
 * <p>**10 类固定顺序、命令是常量**：{@link #COMMANDS} 是唯一事实来源——采集执行它、
 * 机检脚本比它、证据里逐项记它。不允许任何一处另写一份命令（写两份必然漂移，
 * 而「报告里写的命令」和「真正跑的命令」不一致，等于证据失效）。
 *
 * <p>**失败不是异常**：某类命令跑不通（没 GPU、没 RoCE、没权限）是这个类的正常结果，
 * 采集继续往下走，该项记「未采集到 + 原因」。整批命令全失败才让 status 变成 FAILED。
 * 这条是判据③的落地：不填假值、不因为一个可选项缺失就让整份清单作废。
 *
 * <p>解析器全部是静态方法且不依赖真实主机：判据「离线夹具验证解析」靠的就是
 * 直接喂一段真机输出给 {@code parseXxx}，不需要连机。
 */
@Component
public class HostInventoryCollector {

    /** 单条命令超时：清单是交互式触发的，10 秒足够；超时按「未采集到」处理。 */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 10 类采集命令（只读白名单，顺序即 {@link HostInventory#CATEGORIES}）。
     *
     * <p>用 `sh -c` 执行，所以允许管道与 `for` 遍历——这些都是只读读取系统信息的必要写法；
     * 命令是常量，不接受外部拼接。禁止出现任何写操作（见 {@code tools/check_host_inventory.py}）。
     */
    public static final Map<String, String> COMMANDS = orderedCommands();

    /**
     * 10 类只读命令白名单。每条都以 `export LC_ALL=C;` 开头（v4.12.0 W-D 现场缺陷回归）。
     *
     * <p><b>为什么必须采集方自己钉住语言</b>：ssh 只有客户端 ssh_config 里配了
     * `SendEnv LC_ALL` 才会把本地 locale 送过去，而本通道用 `-F /dev/null` 绕开
     * 系统 ssh_config（防环境串味），于是远端用它**自己的**默认 locale。
     * 现场 5 台浪潮机器（gpu01~04、storage01）默认 zh_CN：
     * <ul>
     *   <li>{@code free -m} 表头输出成「内存：」，取不到 {@code Mem:} 行 →
     *       内存整类判「输出无法解析」（命令退出码还是 0，看上去像解析器坏了）；</li>
     *   <li>{@code bash: 行 1: nvidia-smi: 未找到命令} 里的「行」而不是「line」，
     *       让失败分类匹配不到 command-not-found，被兜底成 unreachable——
     *       把「没装驱动」误导成「网络不通」。</li>
     * </ul>
     * 输出语言不是被采集方的自由变量，必须由采集方固定。{@code export} 写在最前面
     * 而不是给单个命令加前缀，是为了让 `df … | tail` 这种管道里的每一段都生效。
     *
     * <p><b>为什么前缀要逐条内联而不是包一个 {@code cmd()} 辅助方法</b>：
     * {@code tools/check_host_inventory.py}（门禁第 29 步）是直接从本方法的**字符串字面量**
     * 里抽命令、再跟接口返回的 `items[].command` 逐字比对的。套一层方法调用后，
     * 机检只能抽到不含前缀的裸命令，于是 10 类全被判「接口命令与源码不一致（证据漂移）」。
     * 证据要能对着源码复核，就别让两者之间隔一层间接。
     */
    private static Map<String, String> orderedCommands() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("OS", "export LC_ALL=C; grep -m1 '^PRETTY_NAME=' /etc/os-release");
        map.put("KERNEL", "export LC_ALL=C; uname -r");
        map.put("CPU", "export LC_ALL=C; nproc; lscpu | grep -m1 'Model name'");
        map.put("MEMORY", "export LC_ALL=C; free -m | head -2");
        map.put("DISK", "export LC_ALL=C; df -P / | tail -1; echo ---; "
                + "lsblk -d -o NAME,SIZE,ROTA 2>/dev/null");
        map.put("GPU", "export LC_ALL=C; nvidia-smi --query-gpu=name,memory.total,driver_version "
                + "--format=csv,noheader");
        map.put("NIC", "export LC_ALL=C; ip -br link; for i in /sys/class/net/*; do "
                + "printf '%s %s\\n' \"$(basename $i)\" \"$(cat $i/speed 2>/dev/null)\"; done");
        map.put("IP", "export LC_ALL=C; ip -br addr");
        map.put("ROCE", "export LC_ALL=C; for p in /sys/class/infiniband/*/ports/*; do "
                + "printf '%s|%s|%s\\n' \"$p\" \"$(cat $p/state 2>/dev/null)\" "
                + "\"$(cat $p/rate 2>/dev/null)\"; done");
        map.put("ROUTE", "export LC_ALL=C; ip route show");
        // 用 unmodifiableMap 而不是 Map.copyOf：后者不保序，而这里「顺序即报告顺序」，
        // 机检脚本也会按这个顺序核对 10 类，散序会让证据文件里分类忽前忽后。
        return java.util.Collections.unmodifiableMap(map);
    }

    /** 分类中文名：直接进报告，不在前端再翻一遍（同一份清单在页面与导出件里必须同名）。 */
    public static final Map<String, String> LABELS = Map.of(
            "OS", "操作系统", "KERNEL", "内核", "CPU", "CPU", "MEMORY", "内存", "DISK", "磁盘",
            "GPU", "GPU", "NIC", "网卡", "IP", "IP 地址", "ROCE", "RoCE", "ROUTE", "路由");

    private final HostCommandRunner runner;

    public HostInventoryCollector(HostCommandRunner runner) {
        this.runner = runner;
    }

    public HostInventory collect(String host, Long deviceId) {
        return collect(host, deviceId, null);
    }

    /**
     * 采集清单（FR-v4.2-03）：{@code access} 为 null 表示平台本机（不需要凭据），
     * 非 null 表示远程主机（凭据已在服务层解密并校验过权限/二次确认）。
     */
    public HostInventory collect(String host, Long deviceId, HostAccess access) {
        Instant started = Instant.now();
        List<HostInventoryItem> items = new ArrayList<>();
        items.add(os(host, access));
        items.add(kernel(host, access));
        items.add(cpu(host, access));
        items.add(memory(host, access));
        items.add(disk(host, access));
        items.add(gpu(host, access));
        items.add(nic(host, access));
        items.add(ip(host, access));
        items.add(roce(host, access));
        items.add(route(host, access));
        return new HostInventory(host, deviceId, started, HostInventory.statusOf(items), items);
    }

    private HostCommandRunner.Outcome run(String host, String category, HostAccess access) {
        return runner.run(host, COMMANDS.get(category), TIMEOUT, access);
    }

    private HostInventoryItem item(String host, String category, HostCommandRunner.Outcome out,
                                   Object parsed) {
        if (!out.ok() && (out.stdout() == null || out.stdout().isBlank())) {
            // v4.12.0 / FR-v4.12-09：失败原因统一带分类前缀（unreachable / auth_failed /
            // algorithm_mismatch / command_unsupported），三条通道用同一套口径，
            // 否则同一句「连不上」在 Linux 与交换机的报告里会长成两种说法。
            return HostInventoryItem.missing(category, LABELS.get(category), COMMANDS.get(category),
                    Instant.now(), CollectFailure.describe(out));
        }
        if (parsed == null) {
            return HostInventoryItem.missing(category, LABELS.get(category), COMMANDS.get(category),
                    Instant.now(), CollectFailure.COMMAND + ": 输出无法解析（命令已执行，退出码 "
                            + out.exitCode() + "）");
        }
        return HostInventoryItem.ok(category, LABELS.get(category), COMMANDS.get(category),
                Instant.now(), parsed);
    }

    private HostInventoryItem os(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "OS", access);
        return item(host, "OS", out, parseOs(out.stdout()));
    }

    private HostInventoryItem kernel(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "KERNEL", access);
        return item(host, "KERNEL", out, parseKernel(out.stdout()));
    }

    private HostInventoryItem cpu(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "CPU", access);
        return item(host, "CPU", out, parseCpu(out.stdout()));
    }

    private HostInventoryItem memory(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "MEMORY", access);
        return item(host, "MEMORY", out, parseMemory(out.stdout()));
    }

    private HostInventoryItem disk(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "DISK", access);
        return item(host, "DISK", out, parseDisk(out.stdout()));
    }

    private HostInventoryItem gpu(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "GPU", access);
        Object parsed = parseGpu(out.stdout());
        if (parsed == null && out.ok()) {
            // nvidia-smi 退出 0 但没输出 = 机器上没有 GPU，这不是解析失败
            parsed = List.of();
        }
        HostInventoryItem result = item(host, "GPU", out, parsed);
        if (!result.collected() && !out.ok()) {
            return HostInventoryItem.missing("GPU", LABELS.get("GPU"), COMMANDS.get("GPU"),
                    Instant.now(), "未采集到 GPU 信息：" + CollectFailure.describe(out));
        }
        return result;
    }

    private HostInventoryItem nic(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "NIC", access);
        return item(host, "NIC", out, parseNic(out.stdout()));
    }

    private HostInventoryItem ip(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "IP", access);
        return item(host, "IP", out, parseIp(out.stdout()));
    }

    private HostInventoryItem roce(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "ROCE", access);
        Object parsed = parseRoce(out.stdout());
        HostInventoryItem result = item(host, "ROCE", out, parsed);
        if (!result.collected() && (parsed == null || ((List<?>) parsed).isEmpty())) {
            return HostInventoryItem.missing("ROCE", LABELS.get("ROCE"), COMMANDS.get("ROCE"),
                    Instant.now(), "未采集到 RoCE 设备（本机无 InfiniBand/RoCE HCA）");
        }
        return result;
    }

    private HostInventoryItem route(String host, HostAccess access) {
        HostCommandRunner.Outcome out = run(host, "ROUTE", access);
        return item(host, "ROUTE", out, parseRoute(out.stdout()));
    }

    // ------------------------------------------------------------------ 解析器（可离线单测）

    /** `PRETTY_NAME="Ubuntu 24.04.3 LTS"` → `Ubuntu 24.04.3 LTS`。 */
    public static String parseOs(String stdout) {
        if (stdout == null) {
            return null;
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("PRETTY_NAME=") && trimmed.length() > 12) {
                String value = trimmed.substring("PRETTY_NAME=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    public static String parseKernel(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String value = stdout.trim().lines().findFirst().orElse("").trim();
        return value.isEmpty() ? null : value;
    }

    /** `nproc` 的行 + `Model name: xxx` 的行。 */
    public static Map<String, Object> parseCpu(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("Model name:")) {
                value.put("model", trimmed.substring("Model name:".length()).trim());
            } else if (trimmed.matches("\\d+")) {
                value.put("cores", Integer.parseInt(trimmed));
            }
        }
        return value.isEmpty() ? null : value;
    }

    /** `free -m` 的 Mem 行：total used free shared buff/cache available。 */
    public static Map<String, Object> parseMemory(String stdout) {
        if (stdout == null) {
            return null;
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Mem:")) {
                continue;
            }
            String[] parts = trimmed.substring(4).trim().split("\\s+");
            if (parts.length < 2) {
                return null;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("totalMb", toLong(parts[0]));
            if (parts.length >= 6) {
                value.put("usedMb", toLong(parts[1]));
                value.put("availableMb", toLong(parts[parts.length - 1]));
            } else {
                value.put("freeMb", toLong(2 < parts.length ? parts[2] : parts[1]));
            }
            return value;
        }
        return null;
    }

    /** `df -P /` 一行 + `lsblk -d` 多行（分节符 `---`）。 */
    public static Map<String, Object> parseDisk(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String[] sections = stdout.split("(?m)^---\\s*$");
        Map<String, Object> value = new LinkedHashMap<>();
        String dfPart = sections[0].trim();
        String[] dfLines = dfPart.split("\\R");
        if (dfLines.length >= 1) {
            // 命令里已经 `| tail -1`，正常只有一行（设备 容量 已用 可用 使用率 挂载点）；
            // 不假设有表头——真机上表头早被 tail 掉了。
            String[] cols = dfLines[dfLines.length - 1].trim().split("\\s+");
            if (cols.length >= 4) {
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("size", cols[1]);
                root.put("used", cols[2]);
                root.put("available", cols[3]);
                root.put("usePercent", 4 < cols.length ? cols[4] : null);
                value.put("root", root);
            }
        }
        if (sections.length > 1) {
            List<Map<String, Object>> disks = new ArrayList<>();
            for (String line : sections[1].split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("name")) {
                    continue;
                }
                String[] cols = trimmed.split("\\s+");
                if (cols.length < 2) {
                    continue;
                }
                Map<String, Object> disk = new LinkedHashMap<>();
                disk.put("name", cols[0]);
                disk.put("size", cols[1]);
                // ROTA=1 是机械盘，0 是 SSD；拿不到就写 null，不猜
                disk.put("type", cols.length >= 3 ? ("1".equals(cols[2]) ? "HDD" : "SSD") : null);
                disks.add(disk);
            }
            value.put("disks", disks);
        }
        return value.isEmpty() ? null : value;
    }

    /** `name, memory, driver` 每行一张卡。 */
    public static List<Map<String, Object>> parseGpu(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cols = trimmed.split(",");
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", cols[0].trim());
            card.put("memoryTotal", cols.length > 1 ? cols[1].trim() : null);
            card.put("driverVersion", cols.length > 2 ? cols[2].trim() : null);
            cards.add(card);
        }
        return cards.isEmpty() ? null : cards;
    }

    /** `ip -br link` 的行 + 速度表（`iface speedMbps`），按接口名合并。 */
    public static List<Map<String, Object>> parseNic(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cols = trimmed.split("\\s+");
            if (cols.length >= 2 && !cols[1].matches("\\d+")) {
                Map<String, Object> nic = byName.computeIfAbsent(cols[0], name -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("name", name);
                    created.put("state", null);
                    created.put("mac", null);
                    created.put("speedMbps", null);
                    return created;
                });
                nic.put("state", cols[1]);
                if (cols.length >= 3 && cols[2].contains(":")) {
                    nic.put("mac", cols[2]);
                }
                continue;
            }
            // 速度表行：`eth0 1000` / `lo ` (空)
            if (cols.length >= 2 || trimmed.contains(" ")) {
                Map<String, Object> nic = byName.get(cols[0]);
                if (nic != null) {
                    String speed = cols.length > 1 ? cols[1] : "";
                    nic.put("speedMbps", speed.matches("\\d+") ? Long.parseLong(speed) : null);
                }
            }
        }
        return byName.isEmpty() ? null : new ArrayList<>(byName.values());
    }

    /** `ip -br addr`：`eth0 UP 10.0.0.1/24 fe80::1/64`。 */
    public static List<Map<String, Object>> parseIp(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cols = trimmed.split("\\s+");
            if (cols.length < 2) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("interface", cols[0]);
            entry.put("state", cols[1]);
            List<String> addresses = new ArrayList<>();
            for (int i = 2; i < cols.length; i++) {
                if (cols[i].contains("/") || cols[i].contains(":")) {
                    addresses.add(cols[i]);
                }
            }
            entry.put("addresses", addresses);
            result.add(entry);
        }
        return result.isEmpty() ? null : result;
    }

    /** sysfs 行：`/sys/class/infiniband/mlx5_0/ports/1|4: ACTIVE|200 Gb/sec`。 */
    public static List<Map<String, Object>> parseRoce(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        List<Map<String, Object>> ports = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("|")) {
                continue;
            }
            String[] cols = trimmed.split("\\|");
            String path = cols[0].trim();
            // /sys/class/infiniband/<device>/ports/<port>
            String[] segments = path.split("/");
            if (segments.length < 3) {
                continue;
            }
            Map<String, Object> port = new LinkedHashMap<>();
            port.put("device", segments[segments.length - 3]);
            port.put("port", segments[segments.length - 1]);
            String state = cols.length > 1 ? cols[1].trim() : "";
            port.put("state", state.matches("\\d+:.*") ? state.substring(state.indexOf(':') + 1).trim()
                    : state);
            String rate = cols.length > 2 ? cols[2].trim() : "";
            port.put("rate", rate.isEmpty() ? null : rate);
            port.put("rateGbps", rateGbps(rate));
            ports.add(port);
        }
        return ports.isEmpty() ? null : ports;
    }

    /** `200 Gb/sec` → 200；`40 Gb/sec` → 40；解析不出返回 null（不写 0）。 */
    public static Integer rateGbps(String rate) {
        if (rate == null || rate.isBlank()) {
            return null;
        }
        String digits = rate.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(digits);
            // 兼容 `1000 Mb/sec` 这类以 Mb 计的写法
            return rate.toLowerCase(Locale.ROOT).contains("mb/") ? value / 1000 : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 默认路由单独提出来：报告里最常被问的就是「默认网关是谁」。 */
    public static Map<String, Object> parseRoute(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        List<String> routes = new ArrayList<>();
        String defaultRoute = null;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            routes.add(trimmed);
            if (defaultRoute == null && trimmed.startsWith("default")) {
                defaultRoute = trimmed;
            }
        }
        if (routes.isEmpty()) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("defaultRoute", defaultRoute);
        value.put("routes", routes);
        return value;
    }

    private static Long toLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
