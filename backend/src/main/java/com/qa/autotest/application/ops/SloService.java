package com.qa.autotest.application.ops;

import com.qa.autotest.application.metrics.RequestMetrics;
import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.SloSnapshot;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * SLO 计算（FR-3.3-10）。
 *
 * <p>可用率来自真实请求采样（{@link RequestMetrics} 落成的日快照）；错误预算按
 * 「目标可用率 × 覆盖天数」换算；MTTD/MTTR 来自告警事件的检测/触发/恢复时间。
 * 没有样本时明确返回 {@code samples=0}，不用 100% 冒充「一直可用」。
 */
@Service
public class SloService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final OpsRepository repo;
    private final RequestMetrics metrics;

    @Value("${ops.slo.target-pct:99.5}")
    private double targetPct = 99.5;

    public SloService(OpsRepository repo, RequestMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    public SloSnapshot snapshot(long projectId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDate from = LocalDate.now(ZONE).minusDays(window - 1L);
        List<OpsRepository.SloDay> rows = repo.sloDays(projectId, from);
        double weightedAvailability = 0;
        int totalSamples = 0;
        int p95 = 0;
        int budgetUsed = 0;
        List<SloSnapshot.Daily> list = new ArrayList<>();
        for (OpsRepository.SloDay row : rows) {
            list.add(new SloSnapshot.Daily(row.day(), row.availabilityPct(), row.p95Ms(),
                    row.samples(), row.errorBudgetMinutes()));
            weightedAvailability += row.availabilityPct() * row.samples();
            totalSamples += row.samples();
            p95 = Math.max(p95, row.p95Ms());
            budgetUsed += row.errorBudgetMinutes();
        }
        double availability = totalSamples == 0 ? 100.0
                : Math.round(weightedAvailability / totalSamples * 10) / 10.0;
        List<AlertEvent> events = repo.events(projectId, 200);
        long mttd = average(events.stream().filter(event -> event.detectedAt() != null
                        && event.firedAt() != null)
                .map(event -> Math.max(0, event.firedAt().getEpochSecond()
                        - event.detectedAt().getEpochSecond())).toList());
        long mttr = average(events.stream().filter(event -> event.resolvedAt() != null
                        && event.firedAt() != null)
                .map(event -> Math.max(0, event.resolvedAt().getEpochSecond()
                        - event.firedAt().getEpochSecond())).toList());
        double budget = rows.size() * 24 * 60 * (100 - targetPct) / 100;
        return new SloSnapshot(window + " 天", targetPct, availability,
                Math.round(budget * 10) / 10.0, budgetUsed, p95, totalSamples, mttd, mttr,
                List.copyOf(list));
    }

    /** 当前实时采样（未落库的今日累计），页面用于「最近一小时」旁证。 */
    public RequestMetrics.Snapshot live() {
        return metrics.snapshot();
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (Long value : values) {
            sum += value;
        }
        return sum / values.size();
    }
}
