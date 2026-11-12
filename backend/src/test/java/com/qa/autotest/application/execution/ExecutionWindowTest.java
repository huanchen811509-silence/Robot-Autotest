package com.qa.autotest.application.execution;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行窗口（FR-v5.2-03 / 验收 B7 / W52-04）。
 *
 * <p>核心判据是<b>跨零点</b>：真实窗口基本都是 {@code 23:00–06:00}。
 * 把它当普通区间会得到「永远关闭」，而这种错误在白天测试时完全看不出来，
 * 只有到第一个夜班才暴露——所以这里把 23:30 / 00:30 / 06:00 三个点都钉住。
 */
class ExecutionWindowTest {

    @Test
    void 未配置窗口时全天可跑() {
        ExecutionWindow window = ExecutionWindow.of(null, null, null);
        assertFalse(window.timeBounded());
        assertTrue(window.openAt(LocalTime.of(3, 0)));
        assertTrue(window.openAt(LocalTime.of(14, 0)));
        assertEquals("不限时间", window.describe());
    }

    @Test
    void 日间窗口左闭右开() {
        ExecutionWindow window = ExecutionWindow.of("09:00", "18:00", "false");
        assertFalse(window.openAt(LocalTime.of(8, 59)));
        assertTrue(window.openAt(LocalTime.of(9, 0)));
        assertTrue(window.openAt(LocalTime.of(17, 59, 59)));
        assertFalse(window.openAt(LocalTime.of(18, 0)),
                "右端闭会让 18:00 整与 17:59:59 同时成立，两个 run 争同一台机器");
    }

    @Test
    void 跨零点窗口在两侧都算开启() {
        ExecutionWindow window = ExecutionWindow.of("23:00", "06:00", "false");
        assertTrue(window.openAt(LocalTime.of(23, 30)));
        assertTrue(window.openAt(LocalTime.of(0, 30)));
        assertTrue(window.openAt(LocalTime.of(23, 0)));
        assertFalse(window.openAt(LocalTime.of(6, 0)));
        assertFalse(window.openAt(LocalTime.of(12, 0)));
        assertTrue(window.describe().contains("跨零点"), window.describe());
    }

    @Test
    void 只配一端直接报错而不是当成全天() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionWindow.of("23:00", null, null));
        assertTrue(error.getMessage().contains("window_end"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionWindow.of(null, "06:00", null));
    }

    @Test
    void 起止相同报错而不是零长度窗口() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionWindow.of("08:00", "08:00", null));
        assertTrue(error.getMessage().contains("相同"), error.getMessage());
    }

    @Test
    void 格式错误报错并点出字段名() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionWindow.of("8点", "18:00", null));
        assertTrue(error.getMessage().contains("window_start"), error.getMessage());
        assertTrue(error.getMessage().contains("HH:mm"), error.getMessage());
    }

    @Test
    void 独占开关宽容解析() {
        assertTrue(ExecutionWindow.of(null, null, "true").exclusive());
        assertTrue(ExecutionWindow.of(null, null, "1").exclusive());
        assertTrue(ExecutionWindow.of(null, null, "YES").exclusive());
        assertTrue(ExecutionWindow.of(null, null, "on").exclusive());
        // 无法识别一律按 false：独占是更强约束，猜错方向会让本该并行的批次互相阻塞
        assertFalse(ExecutionWindow.of(null, null, "???").exclusive());
        assertFalse(ExecutionWindow.of(null, null, null).exclusive());
    }

    @Test
    void 拒绝提示给出下一次开启时刻() {
        ExecutionWindow window = ExecutionWindow.of("23:00", "06:00", null);
        assertTrue(window.nextOpening().contains("23:00"), window.nextOpening());
    }
}
