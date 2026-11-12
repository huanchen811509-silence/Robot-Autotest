package com.qa.autotest.application.execution.monitor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行监视器（FR-v5.3-01 / W53-01）。
 *
 * <p>六组判据，每组对应 PRD 的一条规则：
 * <ol>
 *   <li>R1 四输入都能产出数据；</li>
 *   <li>R3 双内核同 schema（同一事实在两边的 {@code kind} 完全一致）；</li>
 *   <li>R4 垃圾输入不外抛、只降级；</li>
 *   <li>R5 三类上限都会丢弃并计数（不静默）；</li>
 *   <li>日志切片必须脱敏；</li>
 *   <li>RF 套件行不能当用例（实测踩过的坑）。</li>
 * </ol>
 */
class ExecutionMonitorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"),
            ZoneOffset.UTC);

    private static ExecutionMonitor robot() {
        return ExecutionMonitor.forKernel("RUN-20261112000000-abc", "robot", CLOCK);
    }

    private static ExecutionMonitor pytest() {
        return ExecutionMonitor.forKernel("RUN-20261112000000-abc", "pytest", CLOCK);
    }

    // ---------------------------------------------------------------- R1 四输入

    @Test
    void 标准输出能产出日志切片与事件() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout("tests/a.py::test_x PASSED   [ 50%]");

        ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(1, snapshot.logSlice().size());
        assertEquals(1, snapshot.events().size());
        MonitorEvent event = snapshot.events().get(0);
        assertEquals(MonitorEvent.Kind.CASE_END, event.kind());
        assertEquals("a.py::test_x", event.caseCode());
        assertTrue(event.passed());
    }

    @Test
    void 进度文件与平台事件也能进同一条事件流() {
        ExecutionMonitor monitor = pytest();
        // 一行 progress 带 phase + case 会产两个事件：阶段切换 + 用例开始
        monitor.progress("{\"phase\":\"running\",\"case\":\"a.py::test_y\"}");
        monitor.executionEvent("lease-acquired", "node-2");
        monitor.evidenceChanged("output.xml");

        List<MonitorEvent> events = monitor.snapshot().events();
        assertEquals(4, events.size());
        assertEquals(MonitorEvent.Kind.PHASE, events.get(0).kind());
        assertEquals(MonitorEvent.Kind.CASE_START, events.get(1).kind());
        assertEquals("a.py::test_y", events.get(1).caseCode());
        assertEquals("lease-acquired", events.get(2).name());
        assertEquals(MonitorEvent.Kind.WARNING, events.get(3).kind());
    }

    // ---------------------------------------------------------------- R3 同 schema

    @Test
    void 双内核的用例结束事件结构完全一致() {
        ExecutionMonitor rf = robot();
        rf.stdout("登录验证                                                          "
                + "  | PASS |");
        ExecutionMonitor py = pytest();
        py.stdout("tests/test_login.py::test_login PASSED  [ 10%]");

        MonitorEvent rfEvent = rf.snapshot().events().get(0);
        MonitorEvent pyEvent = py.snapshot().events().get(0);
        assertEquals(rfEvent.kind(), pyEvent.kind(), "同一事实在两边必须是同一个 kind");
        assertEquals("robot", rfEvent.kernel());
        assertEquals("pytest", pyEvent.kernel());
        assertNotNull(rfEvent.at());
        assertNotNull(pyEvent.at());
    }

    @Test
    void RF_套件行不当用例() {
        ExecutionMonitor monitor = robot();
        // 套件级汇总行：名称以 run- 开头
        monitor.stdout("run-RUN-20261112000000-abc.Demo :: 冒烟套件                          | PASS |");
        monitor.stdout("run-RUN-20261112000000-abc                                        | PASS |");
        assertEquals(0, monitor.snapshot().events().size(), "套件行不是用例，不该产事件");
    }

    // ---------------------------------------------------------------- R4 不外抛

    @Test
    void 垃圾输入只降级不外抛() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout(null);
        monitor.stderr("");
        monitor.progress("这不是 JSON");
        monitor.progress("{\"phase\":");
        monitor.evidenceChanged(null);
        monitor.executionEvent(null, null);

        ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
        assertTrue(snapshot.degraded(), "解析失败必须被标成降级");
        assertTrue(snapshot.degradedReasons().containsKey("progress-parse"));
        assertFalse(snapshot.complete());
    }

    @Test
    void 未知内核只记日志不产事件() {
        ExecutionMonitor monitor = ExecutionMonitor.forKernel("RUN-x", "junit5", CLOCK);
        monitor.stdout("任何内容");
        ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(1, snapshot.logSlice().size());
        assertEquals(0, snapshot.events().size());
        assertEquals(0, snapshot.commands().size());
    }

    // ---------------------------------------------------------------- R5 上限

    @Test
    void 日志行超上限会丢弃并计数() {
        ExecutionMonitor monitor = new ExecutionMonitor("RUN-x", new PytestKernelAdapter(), CLOCK,
                3, 100, 100, 4096, 10_000);
        for (int i = 0; i < 5; i++) {
            monitor.stdout("line-" + i);
        }
        ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(3, snapshot.logSlice().size());
        assertEquals(2, snapshot.droppedLines());
        assertFalse(snapshot.complete(), "丢过行就不能算完整");
    }

    @Test
    void 超长行被截断且带标记() {
        ExecutionMonitor monitor = new ExecutionMonitor("RUN-x", new PytestKernelAdapter(), CLOCK,
                100, 100, 100, 64, 10_000);
        monitor.stdout("x".repeat(200));
        String line = monitor.snapshot().logSlice().get(0);
        assertTrue(line.endsWith("chars]"), line);
        assertTrue(line.contains("truncated"));
        assertTrue(line.length() < 200);
    }

    @Test
    void 事件数与命令数超限也计数() {
        ExecutionMonitor monitor = new ExecutionMonitor("RUN-x", new PytestKernelAdapter(), CLOCK,
                1000, 2, 1, 4096, 10_000);
        monitor.stdout("tests/a.py::t1 PASSED");
        monitor.stdout("tests/a.py::t2 PASSED");
        monitor.stdout("tests/a.py::t3 PASSED");
        assertEquals(2, monitor.snapshot().events().size());
        assertTrue(monitor.snapshot().droppedEvents() >= 1);

        monitor.stdout("$ iperf3 -c 10.0.0.1");
        monitor.stdout("$ iperf3 -c 10.0.0.2");
        assertEquals(1, monitor.snapshot().commands().size());
        assertEquals(1, monitor.snapshot().droppedCommands());
    }

    @Test
    void 速率超限只丢不报错() {
        ExecutionMonitor monitor = new ExecutionMonitor("RUN-x", new PytestKernelAdapter(), CLOCK,
                10_000, 10_000, 10_000, 4096, 3);
        for (int i = 0; i < 10; i++) {
            monitor.stdout("line-" + i);
        }
        ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(3, snapshot.logSlice().size(), "限速 3 行/秒，只留前 3 行");
        assertEquals(7, snapshot.droppedByRate(), "其余 7 行被限速丢弃");
        assertEquals(0, snapshot.droppedLines(), "限速丢弃与容量丢弃要分开计数");
        assertEquals(7, snapshot.totalDropped());
        assertTrue(snapshot.degradedReasons().containsKey("rate"));
    }

    // ---------------------------------------------------------------- 脱敏

    @Test
    void 日志切片脱敏口令() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout("$ mysql -u root -pSup3rSecret -e 'select 1'");
        String line = monitor.snapshot().logSlice().get(0);
        assertFalse(line.contains("Sup3rSecret"), "日志切片里不能出现明文口令：" + line);
    }

    @Test
    void 命令集合里敏感参数只存掩码() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout("$ sshpass -p Sup3rSecret ssh admin@10.0.0.9 'ethtool -S eth0'");
        List<CommandRecord> commands = monitor.snapshot().commands();
        assertEquals(1, commands.size());
        CommandRecord record = commands.get(0);
        assertTrue(record.sensitive(), "带口令的命令必须标敏感");
        assertFalse(record.commandLine().contains("Sup3rSecret"));
    }

    // ---------------------------------------------------------------- 命令采集

    @Test
    void ssh_命令的_endpoint_取远端主机且不带用户() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout("$ ssh -p 2222 admin@10.202.54.9 'iperf3 -c 10.202.54.10'");
        CommandRecord record = monitor.snapshot().commands().get(0);
        assertEquals("10.202.54.9", record.endpoint());
        assertEquals("iperf3", record.argv().get(0), "ssh 要剥一层，argv 取远端命令");
        assertEquals(CommandKind.TRAFFIC, record.kind());
    }

    @Test
    void 命令归属到当前用例() {
        ExecutionMonitor monitor = pytest();
        monitor.stdout("tests/a.py::test_tc PASSED");
        monitor.stdout("$ ethtool -S eth0");
        CommandRecord record = monitor.snapshot().commands().get(0);
        assertEquals("a.py::test_tc", record.caseCode(),
                "命令行本身不带用例标识，必须靠当前用例补上");
    }
}
