package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.domain.execution.RunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 孤儿进程周期清扫（FR-3.2-05）。
 *
 * <p>运行正常结束会自行 {@link ProcessRegistry#unregister}; 只有执行线程异常退出、
 * 取消/超时路径漏杀时才留下登记项。清扫器按 DB 活跃运行（preparing/running）对账：
 * 登记项不在活跃集合，且登记时间已超过 {@code orphanMinAgeSeconds}，就杀进程树并注销。
 *
 * <p>最小存活时间用于避开「查询活跃集合之后 run 才转入 running」的竞态：
 * 刚登记的进程在窗口内一律不动，宁可下一轮再清。临时目录（运行产物目录）由
 * {@code RetentionScheduler} 定时清理，二者共同保证「无孤儿累积」。
 */
@Component
public class ExecutionOrphanSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionOrphanSweeper.class);
    private static final Duration KILL_GRACE = Duration.ofSeconds(5);

    private final ProcessRegistry registry;
    private final RunRepository runs;
    private final ProcessTreeKiller killer;
    private final ExecutionProperties props;
    private final Clock clock;

    public ExecutionOrphanSweeper(ProcessRegistry registry, RunRepository runs,
                                  ProcessTreeKiller killer, ExecutionProperties props,
                                  Clock executionClock) {
        this.registry = registry;
        this.runs = runs;
        this.killer = killer;
        this.props = props;
        this.clock = executionClock == null ? Clock.systemUTC() : executionClock;
    }

    @Scheduled(fixedDelayString = "${execution.orphan-sweep-interval-seconds:60}000",
               initialDelayString = "${execution.orphan-sweep-initial-delay-seconds:60}000")
    public void scheduledSweep() {
        if (props.getOrphanSweepIntervalSeconds() <= 0) {
            return;
        }
        SweepReport report = sweep();
        if (report.swept() > 0) {
            LOG.warn("孤儿清扫：登记 {} 条，清理 {} 条（跳过 {} 条未满最小存活时间）",
                    report.registered(), report.swept(), report.skippedYoung());
        }
    }

    /** 按 DB 活跃运行对账后清扫。 */
    public SweepReport sweep() {
        Set<String> active = runs.findActive().stream()
                .map(RunRecord::runId)
                .collect(Collectors.toUnmodifiableSet());
        return sweep(active);
    }

    /**
     * 对账并终止孤儿进程树。
     *
     * @param activeRunIds 当前活跃（preparing/running）的运行 ID；不在其中且已过最小存活时间的登记项会被清理
     */
    public SweepReport sweep(Set<String> activeRunIds) {
        Set<String> active = activeRunIds == null ? Set.of() : activeRunIds;
        int registered = 0;
        int swept = 0;
        int skippedYoung = 0;
        Instant now = clock.instant();
        int minAge = Math.max(0, props.getOrphanMinAgeSeconds());
        for (String runId : registry.activeRunIds()) {
            registered++;
            if (active.contains(runId)) {
                continue;
            }
            Instant registeredAt = registry.registeredAt(runId).orElse(null);
            if (registeredAt != null && !now.isAfter(registeredAt.plusSeconds(minAge))) {
                skippedYoung++;
                continue;
            }
            List<Process> processes = registry.processes(runId);
            for (Process process : processes) {
                killer.kill(process, KILL_GRACE);
            }
            registry.unregister(runId);
            swept++;
            LOG.warn("孤儿进程已清理 runId={} processes={}", runId, processes.size());
        }
        return new SweepReport(registered, swept, skippedYoung);
    }

    public record SweepReport(int registered, int swept, int skippedYoung) {
    }
}
