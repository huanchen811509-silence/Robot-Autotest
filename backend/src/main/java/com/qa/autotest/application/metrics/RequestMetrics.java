package com.qa.autotest.application.metrics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 请求级成功率与 p95（FR-3.3-10 SLO 数据源）。
 *
 * <p>按项目分别累计：一次请求算一个样本，5xx 视为错误；p95 用固定 1024 槽的环形蓄水池，
 * 不保存明细、内存占用恒定。数据每天由 {@code SloScheduler} 落成日快照，
 * 重启后当天计数从 0 重新累计（日快照行本身不会丢）。
 */
@Component
public class RequestMetrics {

    public record Snapshot(long total, long errors, double availabilityPct, int p95Ms) {
    }

    private static final int SLOTS = 1024;

    private static final class Stats {
        private final LongAdder total = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final AtomicLongArray samples = new AtomicLongArray(SLOTS);
        private final AtomicInteger cursor = new AtomicInteger();

        private void record(boolean error, long millis) {
            total.increment();
            if (error) {
                errors.increment();
            }
            samples.set(cursor.getAndUpdate(value -> (value + 1) % SLOTS), millis);
        }

        private Snapshot snapshot() {
            long count = total.sum();
            double availability = count == 0 ? 100.0
                    : Math.round((1 - (double) errors.sum() / count) * 1000) / 10.0;
            int used = (int) Math.min(count, SLOTS);
            long[] values = new long[used];
            for (int i = 0; i < used; i++) {
                values[i] = samples.get(i);
            }
            java.util.Arrays.sort(values);
            int p95 = used == 0 ? 0
                    : (int) values[Math.min(used - 1, (int) Math.ceil(used * 0.95) - 1)];
            return new Snapshot(count, errors.sum(), availability, p95);
        }

        private void reset() {
            total.reset();
            errors.reset();
            cursor.set(0);
            for (int i = 0; i < SLOTS; i++) {
                samples.set(i, 0L);
            }
        }
    }

    private final Stats platform = new Stats();
    private final Map<Long, Stats> projects = new ConcurrentHashMap<>();

    public void record(long projectId, boolean error, long millis) {
        platform.record(error, millis);
        if (projectId > 0) {
            projects.computeIfAbsent(projectId, id -> new Stats()).record(error, millis);
        }
    }

    public Snapshot snapshot() {
        return platform.snapshot();
    }

    /** 项目维度快照；未采样时返回 0 样本，不冒充 100% 可用。 */
    public Snapshot snapshot(long projectId) {
        Stats stats = projects.get(projectId);
        return stats == null ? new Snapshot(0, 0, 100.0, 0) : stats.snapshot();
    }

    public Set<Long> projects() {
        return Set.copyOf(projects.keySet());
    }

    /** 跨天清零：只清内存计数，已落库的日快照不动。 */
    public void reset() {
        platform.reset();
        projects.values().forEach(Stats::reset);
    }
}
