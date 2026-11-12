package com.qa.autotest.application.execution.monitor;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令类别判定（FR-v5.3-02 / R2）。
 *
 * <p>核心判据只有一条：<b>判不出来就记 {@code other}，不猜</b>。
 * 猜错的类别会让「按 kind 过滤」变成误导，比没有分类更糟。
 */
class CommandKindTest {

    @Test
    void 打流工具归_traffic() {
        assertEquals(CommandKind.TRAFFIC, CommandKind.classify(List.of("iperf3", "-c", "10.0.0.1")));
        assertEquals(CommandKind.TRAFFIC, CommandKind.classify(List.of("ib_write_bw", "-d", "mlx5_0")));
    }

    @Test
    void 只读工具归_collect() {
        assertEquals(CommandKind.COLLECT, CommandKind.classify(List.of("lspci", "-vv")));
        assertEquals(CommandKind.COLLECT, CommandKind.classify(List.of("nvidia-smi")));
        assertEquals(CommandKind.COLLECT, CommandKind.classify(List.of("/usr/bin/dmidecode", "-t")));
    }

    @Test
    void 同一个工具按子命令区分读与写() {
        assertEquals(CommandKind.CONFIGURE,
                CommandKind.classify(List.of("tc", "qdisc", "add", "dev", "eth0", "root")));
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("tc", "qdisc", "show")));
        assertEquals(CommandKind.CONFIGURE, CommandKind.classify(List.of("ip", "link", "set", "eth0", "up")));
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("ip", "addr", "show")));
    }

    @Test
    void ethtool_只有_s_是写动作() {
        assertEquals(CommandKind.CONFIGURE, CommandKind.classify(List.of("ethtool", "-s", "eth0", "speed", "1000")));
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("ethtool", "-S", "eth0")));
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("ethtool", "-i", "eth0")));
    }

    @Test
    void 探活与断言工具归_verify() {
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("ping", "-c", "4", "10.0.0.1")));
        assertEquals(CommandKind.VERIFY, CommandKind.classify(List.of("curl", "-s", "http://x/health")));
    }

    @Test
    void 清理工具归_cleanup() {
        assertEquals(CommandKind.CLEANUP, CommandKind.classify(List.of("pkill", "-f", "iperf3")));
        assertEquals(CommandKind.CLEANUP, CommandKind.classify(List.of("rm", "-f", "/tmp/x")));
    }

    @Test
    void 判不出来记_other_不猜() {
        assertEquals(CommandKind.OTHER, CommandKind.classify(List.of("my-custom-tool", "--flag")));
        assertEquals(CommandKind.OTHER, CommandKind.classify(List.of()));
        assertEquals(CommandKind.OTHER, CommandKind.classify(null));
        assertEquals(CommandKind.OTHER, CommandKind.classify(List.of("   ")));
    }
}
