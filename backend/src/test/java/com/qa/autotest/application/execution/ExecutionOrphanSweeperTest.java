package com.qa.autotest.application.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-3.2-07：孤儿进程周期清扫（对账活跃运行 + 最小存活时间）。 */
class ExecutionOrphanSweeperTest {

    private static final Instant T0 = Instant.parse("2026-11-08T08:00:00Z");

    @Test
    @DisplayName("登记项不在活跃集合且已过最小存活时间：杀进程树并注销")
    void 清理孤儿() {
        ProcessRegistry registry = registryAt(T0);
        FakeProcess process = new FakeProcess();
        registry.register("RUN-ORPHAN", process);

        ExecutionOrphanSweeper sweeper = sweeper(registry, T0.plusSeconds(31), 30);
        ExecutionOrphanSweeper.SweepReport report = sweeper.sweep(Set.of());

        assertEquals(1, report.registered());
        assertEquals(1, report.swept());
        assertFalse(process.isAlive());
        assertTrue(registry.processes("RUN-ORPHAN").isEmpty());
    }

    @Test
    @DisplayName("活跃运行不清理，进程继续存活")
    void 活跃不清理() {
        ProcessRegistry registry = registryAt(T0);
        FakeProcess process = new FakeProcess();
        registry.register("RUN-ACTIVE", process);

        ExecutionOrphanSweeper sweeper = sweeper(registry, T0.plusSeconds(31), 30);
        ExecutionOrphanSweeper.SweepReport report = sweeper.sweep(Set.of("RUN-ACTIVE"));

        assertEquals(0, report.swept());
        assertTrue(process.isAlive());
        assertEquals(1, registry.processes("RUN-ACTIVE").size());
    }

    @Test
    @DisplayName("刚登记的进程未满最小存活时间：本轮跳过，留给下一轮")
    void 未满最小存活时间跳过() {
        ProcessRegistry registry = registryAt(T0);
        FakeProcess process = new FakeProcess();
        registry.register("RUN-YOUNG", process);

        ExecutionOrphanSweeper sweeper = sweeper(registry, T0.plusSeconds(5), 30);
        ExecutionOrphanSweeper.SweepReport report = sweeper.sweep(Set.of());

        assertEquals(0, report.swept());
        assertEquals(1, report.skippedYoung());
        assertTrue(process.isAlive());
    }

    private static ProcessRegistry registryAt(Instant instant) {
        return new ProcessRegistry(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static ExecutionOrphanSweeper sweeper(ProcessRegistry registry, Instant now,
                                                  int minAgeSeconds) {
        ExecutionProperties props = new ExecutionProperties();
        props.setOrphanMinAgeSeconds(minAgeSeconds);
        return new ExecutionOrphanSweeper(registry, new InMemoryRunRepository(),
                new ProcessTreeKiller(), props, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 最小 Process 替身：destroy() 即退出，descendants() 为空。 */
    static final class FakeProcess extends Process {

        private volatile boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}
