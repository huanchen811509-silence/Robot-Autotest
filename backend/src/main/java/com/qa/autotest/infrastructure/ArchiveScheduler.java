package com.qa.autotest.infrastructure;

import com.qa.autotest.application.archive.ArchiveService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时归档（FR-3.4-01）。
 *
 * <p>只在项目策略 {@code enabled=1} 时才会搬数据；默认每小时巡检一次，测试 profile 不装配。
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "archive.scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ArchiveScheduler {

    private final ArchiveService archive;

    public ArchiveScheduler(ArchiveService archive) {
        this.archive = archive;
    }

    @Scheduled(fixedDelayString = "${archive.scheduler.interval-ms:3600000}",
            initialDelayString = "${archive.scheduler.initial-delay-ms:300000}")
    public void tick() {
        archive.executeAllProjects();
    }
}
