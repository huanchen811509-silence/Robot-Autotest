package com.qa.autotest.infrastructure;

import com.qa.autotest.application.metrics.RequestMetrics;
import com.qa.autotest.application.repository.OpsRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLO 日快照采集（FR-3.3-10）。
 *
 * <p>每分钟把内存里的请求采样写进 {@code slo_daily}；跨天自动清零内存计数。
 * 默认开启，测试 profile 永不装配，避免单测写库。
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "ops.slo", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SloScheduler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final RequestMetrics metrics;
    private final OpsRepository repo;
    private LocalDate day = LocalDate.now(ZONE);

    @org.springframework.beans.factory.annotation.Value("${ops.slo.target-pct:99.5}")
    private double targetPct = 99.5;

    public SloScheduler(RequestMetrics metrics, OpsRepository repo) {
        this.metrics = metrics;
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "${ops.slo.interval-ms:60000}")
    public void tick() {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(day)) {
            day = today;
            metrics.reset();
        }
        long elapsedMinutes = Duration.between(today.atStartOfDay(), LocalDateTime.now(ZONE))
                .toMinutes();
        double budget = elapsedMinutes * (100 - targetPct) / 100;
        for (Long projectId : metrics.projects()) {
            RequestMetrics.Snapshot snapshot = metrics.snapshot(projectId);
            if (snapshot.total() == 0) {
                continue;
            }
            int usedMinutes = (int) Math.round(budget * (100 - snapshot.availabilityPct()) / 100);
            repo.upsertSloDay(projectId, today, snapshot.availabilityPct(), snapshot.p95Ms(),
                    (int) Math.min(Integer.MAX_VALUE, snapshot.total()), usedMinutes);
        }
    }
}
