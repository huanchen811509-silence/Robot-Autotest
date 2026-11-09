package com.qa.autotest.domain.ops;

import java.time.LocalDate;
import java.util.List;

/**
 * SLO 快照（FR-3.3-10）。
 *
 * <p>可用率目标按月计算；错误预算按「目标可用率 → 每月允许停机分钟数」换算。
 * MTTD / MTTR 来自告警事件的 detected_at / fired_at / resolved_at，不用人工估算。
 */
public record SloSnapshot(String window, double targetPct, double availabilityPct,
                          double errorBudgetMinutes, double errorBudgetUsedMinutes,
                          int p95Ms, int samples, long mttdSeconds, long mttrSeconds,
                          List<Daily> days) {

    /** 单日快照。 */
    public record Daily(LocalDate day, double availabilityPct, int p95Ms, int samples,
                        int errorBudgetMinutes) {
    }

    public double errorBudgetRemainingMinutes() {
        return Math.max(0.0, errorBudgetMinutes - errorBudgetUsedMinutes);
    }

    public boolean budgetExhausted() {
        return errorBudgetUsedMinutes >= errorBudgetMinutes;
    }
}
