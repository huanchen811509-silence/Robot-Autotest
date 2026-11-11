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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 网络设备采集器（v4.12.0 / FR-v4.12-07）。
 *
 * <p><b>这不是 Linux 采集器的子集，是另一套语言</b>：现场 4 台交换机实测
 * （`10.202.54.91/92/93/100`）里 `uname`/`lscpu`/`ip` 全部不存在，而 `$(hostname)`
 * 被原样回显——设备上根本没有 shell 展开。所以命令集、解析器、分类名都另起一套，
 * 只共用 {@link HostCommandRunner} 与 {@link HostInventory} 这两层外壳。
 *
 * <p><b>两条命令按厂商分叉</b>（实测结论，不是猜测）：
 * <ul>
 *   <li>centecOS（E780/E530）：接口命令是 {@code show interface status}；
 *       它的 {@code show interface brief} 返回 {@code % Line has invalid autocommand}。</li>
 *   <li>Ruijie（RGOS）：接口命令是 {@code show interface brief}，
 *       输出带 {@code Interface / Link Stat / Protocol Stat} 表头。</li>
 * </ul>
 * 先跑 {@code show version} 拿到厂商，再决定第二条命令——比「两条都试一遍」少一次 SSH，
 * 也避免在设备上留下一次失败命令。
 */
@Component
public class NetworkDeviceCollector {

    /** 单条命令超时：交换机 CLI 首包较慢，给到 15s。 */
    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static final String CMD_VERSION = "show version";
    public static final String CMD_INTERFACE_CENTEC = "show interface status";
    public static final String CMD_INTERFACE_RUIJIE = "show interface brief";

    /** 分类名：与 Linux 的 10 类不同，前缀 `NET_` 便于在报告里一眼区分来源。 */
    public static final Map<String, String> LABELS = Map.of(
            "NET_MODEL", "设备型号",
            "NET_VENDOR", "厂商",
            "NET_VERSION", "软件版本",
            "NET_SERIAL", "序列号",
            "NET_UPTIME", "运行时长",
            "NET_PORTS", "端口");

    private static final Pattern CENTEC_VERSION =
            Pattern.compile("(?m)^centecOS Software,\\s*([A-Za-z0-9-]+),\\s*Version\\s+(\\S+)");
    private static final Pattern CENTEC_HW_TYPE =
            Pattern.compile("(?m)^Hardware Type is\\s+(\\S+)");
    private static final Pattern CENTEC_SERIAL =
            Pattern.compile("(?m)^System serial number is\\s+(\\S+)");
    private static final Pattern CENTEC_UPTIME =
            Pattern.compile("(?m)^(\\S+) uptime is\\s+(.+?)\\s*$");

    private static final Pattern RUIJIE_DESC =
            Pattern.compile("(?m)^System description\\s*:\\s*(.+?)\\s*$");
    private static final Pattern RUIJIE_SOFT =
            Pattern.compile("(?m)^System software version\\s*:\\s*(.+?)\\s*$");
    private static final Pattern RUIJIE_SERIAL =
            Pattern.compile("(?m)^System serial number\\s*:\\s*(\\S+)");
    private static final Pattern RUIJIE_UPTIME =
            Pattern.compile("(?m)^System uptime\\s*:\\s*(\\S+)");
    private static final Pattern RUIJIE_MODEL_IN_PARENS = Pattern.compile("\\(([A-Za-z0-9-]+)\\)");

    /** 逻辑接口：VLAN / loopback / 管理口，不计入物理端口数。 */
    private static final Pattern LOGICAL_IFACE =
            Pattern.compile("(?i)^(vl|vlan|lo|loopback|mg|mgmt|mg0|nu|null)");

    private final HostCommandRunner runner;

    public NetworkDeviceCollector(HostCommandRunner runner) {
        this.runner = runner;
    }

    public HostInventory collect(String host, Long deviceId, HostAccess access) {
        Instant started = Instant.now();
        List<HostInventoryItem> items = new ArrayList<>();

        HostCommandRunner.Outcome versionOut = runner.run(host, CMD_VERSION, TIMEOUT, access);
        String vendor = parseVendor(versionOut.stdout());
        boolean centec = "centec".equals(vendor);

        items.add(item("NET_VENDOR", CMD_VERSION, versionOut, vendor));
        items.add(item("NET_MODEL", CMD_VERSION, versionOut,
                centec ? parseCentecModel(versionOut.stdout()) : parseRuijieModel(versionOut.stdout())));
        items.add(item("NET_VERSION", CMD_VERSION, versionOut,
                centec ? parseCentecVersion(versionOut.stdout()) : parseRuijieVersion(versionOut.stdout())));
        items.add(item("NET_SERIAL", CMD_VERSION, versionOut,
                centec ? firstGroup(CENTEC_SERIAL, versionOut.stdout())
                        : firstGroup(RUIJIE_SERIAL, versionOut.stdout())));
        items.add(item("NET_UPTIME", CMD_VERSION, versionOut,
                centec ? parseCentecUptime(versionOut.stdout()) : firstGroup(RUIJIE_UPTIME, versionOut.stdout())));

        String ifaceCmd = centec ? CMD_INTERFACE_CENTEC : CMD_INTERFACE_RUIJIE;
        HostCommandRunner.Outcome ifaceOut = runner.run(host, ifaceCmd, TIMEOUT, access);
        items.add(item("NET_PORTS", ifaceCmd, ifaceOut, parsePorts(ifaceOut.stdout())));

        return new HostInventory(host, deviceId, started, HostInventory.statusOf(items), items);
    }

    private HostInventoryItem item(String category, String command,
                                   HostCommandRunner.Outcome out, Object parsed) {
        if (parsed == null) {
            String reason = out.ok()
                    ? "输出无法解析（命令已执行，退出码 0）"
                    : CollectFailure.describe(out);
            return HostInventoryItem.missing(category, LABELS.get(category), command,
                    Instant.now(), reason);
        }
        return HostInventoryItem.ok(category, LABELS.get(category), command, Instant.now(), parsed);
    }

    // ---------------------------------------------------------------- 解析（可离线单测）

    /**
     * 厂商识别：centec / ruijie / generic。
     *
     * <p>**空输出返回 null 而不是 generic**：命令都没跑通时「厂商 = generic」会变成一条
     * 采集成功项，整台设备的结论就从 FAILED 降级成 PARTIAL——把「连不上」报成「采到了」。
     * 非空但认不出的输出才给 generic（那是真的采到了，只是不认识这家）。
     */
    public static String parseVendor(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String text = stdout.toLowerCase(Locale.ROOT);
        if (text.contains("centecos")) {
            return "centec";
        }
        if (text.contains("ruijie") || text.contains("rnos") || text.contains("rgos")) {
            return "ruijie";
        }
        return "generic";
    }

    /** `centecOS Software, E780, Version 7.0.5.70` → `E780`。 */
    public static String parseCentecModel(String stdout) {
        Matcher m = CENTEC_VERSION.matcher(stdout == null ? "" : stdout);
        return m.find() ? m.group(1) : firstGroup(CENTEC_HW_TYPE, stdout);
    }

    /** `centecOS Software, E780, Version 7.0.5.70` → `7.0.5.70`。 */
    public static String parseCentecVersion(String stdout) {
        Matcher m = CENTEC_VERSION.matcher(stdout == null ? "" : stdout);
        return m.find() ? m.group(2) : null;
    }

    /** 运行时长取整句（`8 days, 3 hours, 12 minutes`），不换算成秒——报告里读起来更直观。 */
    public static String parseCentecUptime(String stdout) {
        Matcher m = CENTEC_UPTIME.matcher(stdout == null ? "" : stdout);
        return m.find() ? m.group(2).trim() : null;
    }

    /** 从 `System description : Ruijie Full 25G Routing Switch(S6510-48VS8CQ) By Ruijie` 取括号内型号。 */
    public static String parseRuijieModel(String stdout) {
        String desc = firstGroup(RUIJIE_DESC, stdout);
        if (desc == null) {
            return null;
        }
        Matcher m = RUIJIE_MODEL_IN_PARENS.matcher(desc);
        return m.find() ? m.group(1) : desc;
    }

    /** `System software version : S6500_RGOS 11.0(5)B9P108` → 原样整串（含大版本前缀，便于比对）。 */
    public static String parseRuijieVersion(String stdout) {
        return firstGroup(RUIJIE_SOFT, stdout);
    }

    /**
     * 端口表解析（两种表头适配）。
     *
     * <p>两家的共同点：表头之后每行第一个 token 是接口名、第二个是链路状态。
     * 差异只在表头文字（`Port Status` vs `Interface Link Stat`）与列数，所以按
     * 「找到含 Status 的表头行 → 逐行取前两列」这一条规则统一处理，不做两份解析器。
     *
     * <p>逻辑接口（VLAN / loopback / Mgmt）单列计数但不计入物理端口总数：
     * 一台 48 口交换机若把 `Vl1000` 也算成端口，报出来的数字会永远对不上面板。
     */
    public static PortSummary parsePorts(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String[] lines = stdout.split("\\R");
        int header = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean looksHeader = (line.contains("Status") || line.contains("Link"))
                    && (line.startsWith("Port") || line.startsWith("Interface"));
            if (looksHeader) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return null;
        }
        int physical = 0;
        int up = 0;
        int logical = 0;
        List<Map<String, Object>> ports = new ArrayList<>();
        for (int i = header + 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("--")) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length < 2) {
                continue;
            }
            String name = cols[0];
            String state = cols[1];
            if (!isInterfaceName(name)) {
                continue;
            }
            Map<String, Object> port = new LinkedHashMap<>();
            port.put("name", name);
            port.put("state", state);
            if (cols.length >= 4 && !LOGICAL_IFACE.matcher(name).find()) {
                // centec 的 `show interface status` 第 4 列是速率（auto / a-400G）
                port.put("speed", cols[3]);
            }
            ports.add(port);
            if (LOGICAL_IFACE.matcher(name).find()) {
                logical++;
            } else {
                physical++;
                if ("up".equalsIgnoreCase(state)) {
                    up++;
                }
            }
        }
        if (ports.isEmpty()) {
            return null;
        }
        return new PortSummary(physical, up, logical, ports);
    }

    /** 接口名形态：字母开头，含数字，允许 `-` `/` `.` `:`（eth-0-1/1、TF0/1、Vl1000、Gi0/1）。 */
    private static boolean isInterfaceName(String token) {
        return token != null && token.length() <= 40
                && token.matches("[A-Za-z][A-Za-z0-9/.:_-]*")
                && token.matches(".*\\d.*");
    }

    private static String firstGroup(Pattern pattern, String stdout) {
        if (stdout == null) {
            return null;
        }
        Matcher m = pattern.matcher(stdout);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 端口摘要。
     *
     * @param physical 物理端口数（不含 VLAN / loopback / Mgmt）
     * @param up       其中链路 up 的数量
     * @param logical  逻辑接口数（单列，不参与上面两个数）
     */
    public record PortSummary(int physical, int up, int logical, List<Map<String, Object>> ports) {
    }
}
