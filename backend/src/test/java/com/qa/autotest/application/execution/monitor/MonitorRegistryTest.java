package com.qa.autotest.application.execution.monitor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监视器接入层（W53-01 / R4）。
 *
 * <p>本类只守一条规则，但它是最重要的一条：<b>监视器的任何状态都不能影响执行</b>。
 * 三面夹击来验：开关关闭时不出数据也不报错、非法输入不抛、收尾落盘失败只降级。
 */
class MonitorRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"),
            ZoneOffset.UTC);

    private static MonitorRegistry registry(Path dir, boolean enabled) {
        return new MonitorRegistry(new CommandSetService(dir.toString()), CLOCK, enabled);
    }

    @Test
    void 开关关闭时不出数据也不抛异常(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, false);
        assertNull(monitors.of("RUN-1", "robot"));
        assertDoesNotThrow(() -> monitors.feed("RUN-1", "robot", "任何内容"));
        assertFalse(monitors.monitored("RUN-1"));
        assertEquals(0, monitors.finish("RUN-1"), "关掉监视后收尾应为空操作");
        assertTrue(monitors.commands("RUN-1").isEmpty());
    }

    @Test
    void 开启后能收到命令并在收尾时落盘(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, true);
        monitors.feed("RUN-1", "pytest", "$ iperf3 -c 10.0.0.2");
        assertTrue(monitors.monitored("RUN-1"));

        List<CommandRecord> live = monitors.commands("RUN-1");
        assertEquals(1, live.size(), "收尾前从内存取");

        assertEquals(1, monitors.finish("RUN-1"));
        assertFalse(monitors.monitored("RUN-1"), "收尾后监视器应从注册表移除，不占堆");
        assertEquals(1, monitors.commands("RUN-1").size(), "收尾后从文件读");
        assertNotNull(new CommandSetService(dir.toString()).read("RUN-1").get(0));
    }

    @Test
    void 不同内核互不干扰(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, true);
        monitors.feed("RUN-1", "robot", "登录验证                                                          | PASS |");
        monitors.feed("RUN-1", "pytest", "tests/a.py::t PASSED");

        // 两个内核各一份监视器，事件类型各自解析，不能互相污染
        assertEquals(0, monitors.of("RUN-1", "robot").snapshot().commands().size());
        assertEquals(0, monitors.of("RUN-1", "pytest").snapshot().commands().size());
    }

    @Test
    void 喂空值与未知内核都不抛(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, true);
        assertDoesNotThrow(() -> {
            monitors.feed(null, "robot", "x");
            monitors.feed("RUN-1", null, "x");
            monitors.feed("RUN-1", "robot", null);
            monitors.feedError("RUN-1", "robot", null);
            monitors.event("RUN-1", "robot", null, null);
            monitors.event(null, "robot", "lease", "x");
        });
    }

    @Test
    void 非法_runId_收尾不抛(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, true);
        monitors.feed("../etc", "robot", "x");
        // CommandSetService 会拒绝非法 runId，注册表必须吞掉它而不是让执行链炸掉
        assertDoesNotThrow(() -> monitors.finish("../etc"));
    }

    @Test
    void 收尾只清掉目标_run(@TempDir Path dir) {
        MonitorRegistry monitors = registry(dir, true);
        monitors.feed("RUN-A", "robot", "$ ls");
        monitors.feed("RUN-B", "robot", "$ ls");
        monitors.finish("RUN-A");
        assertFalse(monitors.monitored("RUN-A"));
        assertTrue(monitors.monitored("RUN-B"), "别的 run 的监视器不能被误删");
    }
}
