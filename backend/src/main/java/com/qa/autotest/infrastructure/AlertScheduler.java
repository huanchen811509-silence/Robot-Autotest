package com.qa.autotest.infrastructure;

import com.qa.autotest.application.ops.AlertService;
import com.qa.autotest.application.repository.OpsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 告警巡检（FR-3.3-09）。
 *
 * <p>默认每 60 秒扫描一次有规则的启项目：MTTD 最坏情况 60 秒，满足「≤5 分钟」。
 * 测试 profile 不装配；没有规则时不产生任何库操作。
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "ops.alerts", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AlertScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertService alerts;
    private final OpsRepository repo;

    public AlertScheduler(AlertService alerts, OpsRepository repo) {
        this.alerts = alerts;
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "${ops.alerts.interval-ms:60000}")
    public void tick() {
        for (Long projectId : repo.projectsWithRules()) {
            try {
                alerts.evaluate(projectId);
            } catch (RuntimeException failure) {
                LOG.warn("告警巡检失败 project={}", projectId, failure);
            }
        }
    }
}
