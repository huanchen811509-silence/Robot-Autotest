package com.qa.autotest.application.execution.monitor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令集合落地与检索（FR-v5.3-02 / W53-02）。
 *
 * <p>判据围绕「可查、可过滤、可比」三件事：
 * <ol>
 *   <li>写盘 → 读回内容不变（含脱敏结果与敏感标记）；</li>
 *   <li>四个过滤维度都真的生效（尤其 kind 与失败命令）；</li>
 *   <li>两次 run diff 按「用例 + 组内序号」对齐，不能退化成「全删全增」。</li>
 * </ol>
 */
class CommandSetServiceTest {

    private static CommandRecord cmd(String runId, long seq, String caseCode, String endpoint,
                                     List<String> argv, Integer exitCode) {
        return CommandRecord.of(runId, "node-1", caseCode, seq, endpoint, argv,
                Instant.parse("2026-11-12T10:00:00Z"), 120L, exitCode, "stdout-" + seq);
    }

    @Test
    void 写盘后读回内容一致(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        List<CommandRecord> input = List.of(
                cmd("RUN-1", 1, "a.py::t1", "10.0.0.1", List.of("ethtool", "-S", "eth0"), 0),
                cmd("RUN-1", 2, "a.py::t1", "10.0.0.1", List.of("iperf3", "-c", "10.0.0.2"), 0));
        service.write("RUN-1", input);

        List<CommandRecord> output = service.read("RUN-1");
        assertEquals(2, output.size());
        assertEquals(input.get(0).argv(), output.get(0).argv());
        assertEquals(CommandKind.TRAFFIC, output.get(1).kind());
        assertEquals(0, output.get(1).exitCode().intValue());
        assertEquals("a.py::t1", output.get(0).caseCode());
    }

    @Test
    void 文件缺失返回空列表而不是异常(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        assertTrue(service.read("RUN-NOT-EXIST").isEmpty(),
                "老 run 本来就没采集过命令，这不是错误");
    }

    @Test
    void 写空集合也会产生文件(@TempDir Path dir) throws Exception {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-EMPTY", List.of());
        assertTrue(Files.isRegularFile(service.fileOf("RUN-EMPTY")),
                "「采集到但没命令」与「没采集」必须能区分");
        assertTrue(service.read("RUN-EMPTY").isEmpty());
    }

    @Test
    void 非法_runId_被拒(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> service.fileOf("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> service.fileOf("a/b"));
        assertThrows(IllegalArgumentException.class, () -> service.fileOf(" "));
    }

    @Test
    void 按用例_endpoint_kind_退出码过滤(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-2", List.of(
                cmd("RUN-2", 1, "a.py::t1", "10.0.0.1", List.of("ethtool", "-S", "eth0"), 0),
                cmd("RUN-2", 2, "a.py::t1", "10.0.0.1", List.of("iperf3", "-c", "10.0.0.2"), 0),
                cmd("RUN-2", 3, "b.py::t2", "10.0.0.2", List.of("iperf3", "-c", "10.0.0.3"), 1)));

        assertEquals(2, service.query("RUN-2", CommandSetService.Query.ofCase("a.py::t1")).size());
        assertEquals(2, service.query("RUN-2",
                new CommandSetService.Query(null, "10.0.0.1", null, null, false, 100)).size());
        assertEquals(2, service.query("RUN-2",
                new CommandSetService.Query(null, null, CommandKind.TRAFFIC, null, false, 100)).size());
        assertEquals(1, service.query("RUN-2",
                new CommandSetService.Query(null, null, null, 1, false, 100)).size());
    }

    @Test
    void 只看失败命令(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-3", List.of(
                cmd("RUN-3", 1, "a.py::t1", "h", List.of("ping", "-c", "1", "10.0.0.1"), 0),
                cmd("RUN-3", 2, "a.py::t1", "h", List.of("ping", "-c", "1", "10.0.0.2"), 2)));

        List<CommandRecord> failed = service.query("RUN-3",
                new CommandSetService.Query(null, null, null, null, true, 100));
        assertEquals(1, failed.size());
        assertEquals(2, failed.get(0).exitCode().intValue());
    }

    @Test
    void limit_生效(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-4", List.of(
                cmd("RUN-4", 1, "a", "h", List.of("ls"), 0),
                cmd("RUN-4", 2, "a", "h", List.of("ls"), 0),
                cmd("RUN-4", 3, "a", "h", List.of("ls"), 0)));
        assertEquals(2, service.query("RUN-4",
                new CommandSetService.Query(null, null, null, null, false, 2)).size());
    }

    @Test
    void 两次_run_diff_按用例内序号对齐(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        // 左：t1 跑 ethtool；右：t1 先跑 ping 再跑 ethtool；右多一条 iperf3
        service.write("RUN-L", List.of(
                cmd("RUN-L", 1, "a.py::t1", "h", List.of("ethtool", "-S", "eth0"), 0),
                cmd("RUN-L", 2, "a.py::t1", "h", List.of("iperf3", "-c", "10.0.0.2"), 0)));
        service.write("RUN-R", List.of(
                cmd("RUN-R", 1, "a.py::t1", "h", List.of("ping", "-c", "1", "10.0.0.1"), 0),
                cmd("RUN-R", 2, "a.py::t1", "h", List.of("iperf3", "-c", "10.0.0.2"), 0),
                cmd("RUN-R", 3, "a.py::t1", "h", List.of("pkill", "-f", "iperf3"), 0)));

        CommandSetService.CommandDiff diff = service.diff("RUN-L", "RUN-R", "a.py::t1");
        assertFalse(diff.identical());
        assertEquals(1, diff.changed().size(), "第 1 条从 ethtool 变成 ping，应算 changed 而不是增删");
        assertEquals("ethtool", diff.changed().get(0).left().argv().get(0));
        assertEquals("ping", diff.changed().get(0).right().argv().get(0));
        assertEquals(1, diff.added().size(), "第 3 条 pkill 只在右侧");
        assertEquals(0, diff.removed().size());
        assertEquals(2, diff.total());
    }

    @Test
    void 两次_run_命令完全相同时_diff_为空(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-A", List.of(cmd("RUN-A", 1, "t", "h", List.of("ls", "-l"), 0)));
        service.write("RUN-B", List.of(cmd("RUN-B", 1, "t", "h", List.of("ls", "-l"), 0)));
        assertTrue(service.diff("RUN-A", "RUN-B", null).identical(),
                "cmd_id 里的 runId 不同不该算差异，比的是命令形态");
    }

    @Test
    void diff_能看出左侧独有的命令(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-OLD", List.of(
                cmd("RUN-OLD", 1, "t", "h", List.of("tc", "qdisc", "add", "dev", "eth0", "root"), 0),
                cmd("RUN-OLD", 2, "t", "h", List.of("ls"), 0)));
        service.write("RUN-NEW", List.of(cmd("RUN-NEW", 1, "t", "h", List.of("ls"), 0)));

        CommandSetService.CommandDiff diff = service.diff("RUN-OLD", "RUN-NEW", null);
        assertEquals(1, diff.removed().size());
        assertEquals(0, diff.added().size());
    }

    @Test
    void 落盘内容不含明文口令(@TempDir Path dir) throws Exception {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-S", List.of(cmd("RUN-S", 1, "t", "h",
                List.of("sshpass", "-p", "Sup3rSecret", "iperf3", "-c", "10.0.0.2"), 0)));

        String raw = Files.readString(service.fileOf("RUN-S"), StandardCharsets.UTF_8);
        assertFalse(raw.contains("Sup3rSecret"), "落盘文件里不能出现明文口令：" + raw);
        assertTrue(service.read("RUN-S").get(0).sensitive());
    }

    @Test
    void 坏行不影响整份读取(@TempDir Path dir) throws Exception {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-BAD", List.of(cmd("RUN-BAD", 1, "t", "h", List.of("ls"), 0)));
        // 模拟截断：追加一个半行
        Files.writeString(service.fileOf("RUN-BAD"), "{\"cmd_id\":\"x\",\"run_id\"",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, service.read("RUN-BAD").size(), "坏行跳过，好行照读");
    }

    @Test
    void json_带全部字段(@TempDir Path dir) {
        CommandSetService service = new CommandSetService(dir.toString());
        service.write("RUN-J", List.of(cmd("RUN-J", 7, "a.py::t9", "10.0.0.9",
                List.of("iperf3", "-c", "10.0.0.2"), 0)));
        String line;
        try {
            line = Files.readAllLines(service.fileOf("RUN-J")).get(0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        for (String field : List.of("cmd_id", "run_id", "node_id", "case_code", "seq",
                "endpoint", "kind", "argv", "started_at", "duration_ms", "exit_code",
                "stdout_sha", "truncated", "sensitive")) {
            assertTrue(line.contains("\"" + field + "\""), "缺字段 " + field + "：" + line);
        }
    }
}
