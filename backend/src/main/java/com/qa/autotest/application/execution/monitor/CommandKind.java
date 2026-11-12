package com.qa.autotest.application.execution.monitor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 命令类别（FR-v5.3-02 / R2）。
 *
 * <p>取值固定五个 + {@code other}。<b>无法判定一律记 {@code other}，不猜</b>——
 * 猜错的类别会让「按 kind 过滤」这个入口变成误导，比没有分类更糟。
 *
 * <p>判定方式是按**可执行文件名**查表，不是对命令行做语义理解：
 * {@code tc qdisc add} 归 configure，{@code tc qdisc show} 归 verify，
 * 二者靠子命令区分，属于确定性规则而非猜测。
 */
public enum CommandKind {

    /** 采集：读设备状态，不改配置。 */
    COLLECT,
    /** 配置：改设备/网络状态。 */
    CONFIGURE,
    /** 打流：产生流量的命令。 */
    TRAFFIC,
    /** 校验：读度量做比对（含 ping 这类连通性探测）。 */
    VERIFY,
    /** 清理：撤销前序改动。 */
    CLEANUP,
    /** 判不出来。 */
    OTHER;

    /** 只读工具：默认 COLLECT。 */
    private static final Set<String> COLLECT_TOOLS = Set.of(
            "lspci", "lsusb", "dmidecode", "uname", "hostname", "free", "df", "uptime",
            "lsmod", "modinfo", "nvidia-smi", "ibv_devinfo", "ibstat", "ethtool", "ip", "ss",
            "netstat", "lshw", "cat", "head", "tail", "grep", "journalctl", "dmesg", "ls");

    /** 打流工具。 */
    private static final Set<String> TRAFFIC_TOOLS = Set.of(
            "iperf3", "iperf", "netperf", "qperf", "ib_write_bw", "ib_read_bw",
            "ib_send_bw", "ib_atomic_bw", "perftest", "trex_stl", "pktgen");

    /** 校验工具：读度量、探测连通性。 */
    private static final Set<String> VERIFY_TOOLS = Set.of(
            "ping", "ping6", "arping", "traceroute", "tracepath", "curl", "mtr");

    /** 清理工具。 */
    private static final Set<String> CLEANUP_TOOLS = Set.of(
            "kill", "pkill", "killall", "rm", "rmdir", "unlink", "truncate");

    /**
     * 判定一条命令的类别。
     *
     * @param argv 已分词的命令（argv[0] 为可执行文件名，允许带绝对路径）
     */
    public static CommandKind classify(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return OTHER;
        }
        String tool = baseName(argv.get(0));
        if (tool.isEmpty()) {
            return OTHER;
        }

        if (TRAFFIC_TOOLS.contains(tool)) {
            return TRAFFIC;
        }
        if (VERIFY_TOOLS.contains(tool)) {
            return VERIFY;
        }
        if (CLEANUP_TOOLS.contains(tool)) {
            return CLEANUP;
        }

        // tc / ip / ethtool / devlink 同一个工具既能改也能查，靠子命令区分
        if ("tc".equals(tool) || "ip".equals(tool) || "ethtool".equals(tool)
                || "devlink".equals(tool) || "nmcli".equals(tool)) {
            return mutating(tool, argv) ? CONFIGURE : VERIFY;
        }
        if (COLLECT_TOOLS.contains(tool)) {
            return COLLECT;
        }
        return OTHER;
    }

    /** 改状态的动词表。只认确定的动作词，认不出就当只读。 */
    private static final Set<String> MUTATING_VERBS = Set.of(
            "add", "del", "delete", "set", "change", "replace", "flush",
            "up", "down", "modify", "reset", "restore", "attach", "detach");

    /** 该工具的这一条命令是否在改状态。 */
    private static boolean mutating(String tool, List<String> argv) {
        if ("ethtool".equals(tool)) {
            // ethtool 唯一的写动作是 -s/--change，其余（-i/-S/-k/-m）都是读
            for (String arg : argv) {
                if ("-s".equals(arg) || "--change".equals(arg)) {
                    return true;
                }
            }
            return false;
        }
        for (int i = 1; i < argv.size(); i++) {
            if (MUTATING_VERBS.contains(argv.get(i).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 取可执行文件名：{@code /usr/bin/iperf3} → {@code iperf3}。 */
    private static String baseName(String command) {
        if (command == null) {
            return "";
        }
        String value = command.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
