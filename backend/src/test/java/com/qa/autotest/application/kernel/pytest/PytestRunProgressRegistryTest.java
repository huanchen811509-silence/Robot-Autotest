package com.qa.autotest.application.kernel.pytest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进度登记表（v5.6.0 / FR-v5.6-12、13 / W56-12）。
 *
 * <p>这张表最容易出的错不是算错数，而是**数字往回跳**：页面进度条倒退，
 * 用户会以为执行重跑了；或者执行结束时快照被清掉，刚跑完刷新一下就 404。
 * 断言因此集中在三处：单调性、终态保留、取消标记。
 */
class PytestRunProgressRegistryTest {

    /** 可推进的假时钟，让 elapsedMs 的断言不依赖真实时间。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void countsOnlyIncreaseAcrossBatches() {
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry();
        registry.begin("R1", 10);

        registry.batchDone("R1", 3, 1, 4);
        registry.batchDone("R1", 2, 0, 2);

        PytestRunProgressRegistry.Snapshot snapshot = registry.snapshot("R1").orElseThrow();
        assertEquals(6, snapshot.finished());
        assertEquals(5, snapshot.passed());
        assertEquals(1, snapshot.failed());
    }

    @Test
    void totalGrowsWhenActualExceedsPlan() {
        // 计划 2 条、实际产出 5 条（参数化展开）。总数必须跟着涨，
        // 否则页面会出现「完成 5 / 总数 2」这种自相矛盾的显示。
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry();
        registry.begin("R1", 2);

        registry.batchDone("R1", 4, 1, 5);

        assertEquals(5, registry.snapshot("R1").orElseThrow().total());
    }

    @Test
    void terminalSnapshotSurvivesUntilEvicted() {
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry();
        registry.begin("R1", 3);
        registry.complete("R1", PytestRunProgressRegistry.Stage.FINISHED);

        PytestRunProgressRegistry.Snapshot snapshot = registry.snapshot("R1").orElseThrow();
        assertEquals("FINISHED", snapshot.stage());
        assertTrue(snapshot.terminal());
        assertFalse(registry.tracking("R1"), "终态后不再算「运行中」，但快照仍可读");
    }

    @Test
    void elapsedTimeAdvancesWithClock() {
        MutableClock clock = new MutableClock();
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry(clock);
        registry.begin("R1", 1);
        clock.advance(Duration.ofSeconds(7));

        assertEquals(7000L, registry.snapshot("R1").orElseThrow().elapsedMs());
    }

    @Test
    void cancelIsStickyAndNotAcceptedAfterTerminal() {
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry();
        registry.begin("R1", 1);

        assertTrue(registry.requestCancel("R1"));
        assertTrue(registry.cancelRequested("R1"));
        assertTrue(registry.snapshot("R1").orElseThrow().cancelRequested());

        registry.complete("R1", PytestRunProgressRegistry.Stage.CANCELED);
        assertFalse(registry.requestCancel("R1"), "已终态的运行再取消应返回 false，不重复改状态");
    }

    @Test
    void unknownRunHasNoSnapshot() {
        PytestRunProgressRegistry registry = new PytestRunProgressRegistry();
        assertTrue(registry.snapshot("NOPE").isEmpty());
        assertFalse(registry.cancelRequested("NOPE"));
    }
}
