package com.qa.autotest.application.kernel.pytest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 执行进度登记表（v5.6.0 / FR-v5.6-12、13）。
 *
 * <p>为什么需要一张内存表：pytest 一次批量执行要 5 分钟，同步 HTTP 请求在这期间只能挂着，
 * 页面既不知道「跑到哪一步」也取消不了。进度端点要有东西可答，就必须有人在执行过程中记账。
 *
 * <p><b>为什么不落库</b>：进度是秒级变化的易失数据，落库会带来每秒一次写库；
 * 而进程一重启，进度本来就该作废（执行进程也没了）。真正的执行事实由 {@code run} 表承担，
 * 这张表只服务「此刻」。
 *
 * <p><b>单调性</b>：{@code finished/passed/failed} 只增不减。轮询顺序错乱、
 * 并发读到中间态，都不会让数字往回跳——回跳会让页面上的进度条倒退，用户会以为执行重跑了。
 *
 * <p><b>终态保留</b>：执行结束后快照移进 {@link #recent}（有界 MAP），
 * 这样「刚跑完再点一次刷新」仍然拿得到 FINISHED，而不是 404。
 */
@Service
public class PytestRunProgressRegistry {

    /** 执行阶段。页面上按这个顺序点亮，不自己推断。 */
    public enum Stage {
        PREPARING,
        RENDERING,
        LAUNCHING,
        RUNNING,
        FINALIZING,
        FINISHED,
        FAILED,
        CANCELED;

        public boolean terminal() {
            return this == FINISHED || this == FAILED || this == CANCELED;
        }
    }

    /** 进度快照。字段与 FR-v5.6-12 的判据一一对应，少一个前端就要自己算。 */
    public record Snapshot(String runId, String stage, int total, int finished,
                           int passed, int failed, long elapsedMs,
                           boolean cancelRequested, boolean terminal) {
    }

    private static final int KEEP_TERMINAL = 50;

    private static final class Progress {
        private final Instant startedAt;
        private int total;
        private int finished;
        private int passed;
        private int failed;
        private volatile Stage stage = Stage.PREPARING;
        private volatile boolean cancelRequested;

        private Progress(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    private final Map<String, Progress> active = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> recent = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > KEEP_TERMINAL;
                }
            });

    private final Clock clock;

    @Autowired
    public PytestRunProgressRegistry() {
        this(Clock.systemUTC());
    }

    public PytestRunProgressRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 开始记账。重复 begin 覆盖旧记录（同一 runId 重跑必须有全新进度，不能继承上次的）。 */
    public void begin(String runId, int total) {
        Progress progress = new Progress(clock.instant());
        progress.total = Math.max(total, 0);
        active.put(runId, progress);
        recent.remove(runId);
    }

    public void stage(String runId, Stage stage) {
        Progress progress = active.get(runId);
        if (progress == null || progress.stage.terminal()) {
            return;
        }
        progress.stage = stage;
    }

    /**
     * 记一批结果。{@code finished} 只在给定的三项上累加，绝不被覆盖成更小的值。
     *
     * @param batchPassed  本批通过数
     * @param batchFailed  本批失败数（含 error 与 skip 之外的非通过）
     * @param batchFinished 本批总条数
     */
    public void batchDone(String runId, int batchPassed, int batchFailed, int batchFinished) {
        Progress progress = active.get(runId);
        if (progress == null) {
            return;
        }
        synchronized (progress) {
            progress.passed += Math.max(batchPassed, 0);
            progress.failed += Math.max(batchFailed, 0);
            progress.finished += Math.max(batchFinished, 0);
            // 总数可能来自计划、完成数来自实际产物：实际比计划多时以实际为准，
            // 否则页面会出现「完成 61 / 总数 59」这种自相矛盾的显示。
            if (progress.finished > progress.total) {
                progress.total = progress.finished;
            }
        }
    }

    /** 落终态。快照转入 recent，active 里删掉。 */
    public void complete(String runId, Stage terminal) {
        Progress progress = active.remove(runId);
        if (progress == null) {
            return;
        }
        progress.stage = terminal;
        recent.put(runId, snapshotOf(runId, progress));
    }

    /** 请求取消。只置标记，真正的 kill 由调用方做（这里不持有进程句柄）。 */
    public boolean requestCancel(String runId) {
        Progress progress = active.get(runId);
        if (progress == null || progress.stage.terminal()) {
            return false;
        }
        progress.cancelRequested = true;
        return true;
    }

    public boolean cancelRequested(String runId) {
        Progress progress = active.get(runId);
        return progress != null && progress.cancelRequested;
    }

    public boolean tracking(String runId) {
        return active.containsKey(runId);
    }

    /** 该 run 是否已请求取消且尚未落终态（用于「取消中」这个中间态）。 */
    public Optional<Snapshot> snapshot(String runId) {
        Progress progress = active.get(runId);
        if (progress != null) {
            return Optional.of(snapshotOf(runId, progress));
        }
        return Optional.ofNullable(recent.get(runId));
    }

    private Snapshot snapshotOf(String runId, Progress progress) {
        long elapsed = Duration.between(progress.startedAt, clock.instant()).toMillis();
        synchronized (progress) {
            return new Snapshot(runId, progress.stage.name(), progress.total,
                    progress.finished, progress.passed, progress.failed,
                    Math.max(elapsed, 0), progress.cancelRequested, progress.stage.terminal());
        }
    }
}
