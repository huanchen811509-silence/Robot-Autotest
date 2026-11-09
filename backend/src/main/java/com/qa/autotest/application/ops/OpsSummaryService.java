package com.qa.autotest.application.ops;

import com.qa.autotest.application.metrics.RequestMetrics;
import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.domain.ops.AlertChannel;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.AlertRule;
import com.qa.autotest.domain.ops.ReleaseRecord;
import com.qa.autotest.domain.ops.SloSnapshot;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 运维中心聚合（FR-3.3-09..11）：SLO、告警、备份、发布一次取齐，页面不用拼 6 个请求。 */
@Service
public class OpsSummaryService {

    public record OpsSummary(SloSnapshot slo, RequestMetrics.Snapshot live,
                             BackupStatusService.BackupStatus backup, List<AlertRule> rules,
                             List<AlertChannel> channels, List<AlertEvent> events,
                             List<ReleaseRecord> releases, String generatedAt) {
    }

    private final SloService slo;
    private final OpsRepository repo;
    private final BackupStatusService backups;

    public OpsSummaryService(SloService slo, OpsRepository repo, BackupStatusService backups) {
        this.slo = slo;
        this.repo = repo;
        this.backups = backups;
    }

    public OpsSummary summary(long projectId) {
        return new OpsSummary(slo.snapshot(projectId, 30), slo.live(), backups.latest(),
                repo.rules(projectId), repo.channels(projectId), repo.events(projectId, 20),
                repo.releases(5), Instant.now().toString());
    }
}
