package com.qa.autotest.application.execution;

import com.qa.autotest.domain.execution.RunState;

import java.time.Duration;
import java.time.Instant;

/**
 * 进度 ETA / 卡死判定（FR-3.1-05），纯读侧计算。
 *
 * <p>ETA 用「已完成条目的平均速度」外推剩余条目；没有完成样本或已完成到总数时
 * 不硬造数字：前者返回 null（前端显示 -），后者返回 0。
 */
public final class RunEtaCalculator {

    private RunEtaCalculator() {
    }

    public static Long etaSeconds(int total, int completed, long elapsedSeconds, boolean finished) {
        if (finished) {
            return 0L;
        }
        if (total <= 0 || completed <= 0 || elapsedSeconds <= 0) {
            return null;
        }
        if (completed >= total) {
            return 0L;
        }
        long remaining = total - completed;
        return (long) Math.ceil((double) elapsedSeconds * remaining / completed);
    }

    public static Long heartbeatAgeSeconds(Instant lastActivityAt, Instant now) {
        if (lastActivityAt == null || now == null) {
            return null;
        }
        return Math.max(0, Duration.between(lastActivityAt, now).toSeconds());
    }

    /** 只有运行中且心跳超过阈值才算卡死；终态不报卡死。 */
    public static boolean stalled(RunState state, Long heartbeatAgeSeconds, int thresholdSeconds) {
        return state == RunState.RUNNING && heartbeatAgeSeconds != null
                && heartbeatAgeSeconds > Math.max(1, thresholdSeconds);
    }
}
