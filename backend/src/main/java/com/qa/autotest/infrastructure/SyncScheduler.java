package com.qa.autotest.infrastructure;

import com.qa.autotest.application.caseasset.ProjectSyncService;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存量同步每日预览（v4.9.0 / FR-v4.9-06）。
 *
 * <p><b>默认关闭</b>：{@code sync.auto.enabled=false} 时只登记日志不写库。开启后按
 * {@code sync.preview.cron}（默认 02:00）为每个启用项目生成一次只读预览作业，
 * 不自动执行覆盖——执行仍需人工在同步页确认。
 *
 * <p>以项目负责人身份运行：预览链路要求真实用户上下文，系统线程（userId=null）会被租户守卫拒绝。
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final JdbcTemplate jdbc;
    private final ProjectSyncService sync;

    @Value("${sync.auto.enabled:false}")
    private boolean enabled;

    public SyncScheduler(JdbcTemplate jdbc, ProjectSyncService sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    @Scheduled(cron = "${sync.preview.cron:0 0 2 * * *}")
    public void nightlyPreview() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> projects = jdbc.queryForList("""
                SELECT id, owner_id FROM project
                 WHERE status = 1 AND kind <> 'platform' AND owner_id IS NOT NULL
                 ORDER BY id
                """);
        int done = 0;
        for (Map<String, Object> row : projects) {
            long projectId = ((Number) row.get("id")).longValue();
            long ownerId = ((Number) row.get("owner_id")).longValue();
            TenantContext.runAs(ownerId, "sync-scheduler", projectId, () -> {
                try {
                    sync.preview(projectId);
                } catch (RuntimeException ex) {
                    log.warn("项目 {} 自动同步预览失败：{}", projectId, ex.getMessage());
                }
            });
            done++;
        }
        if (done > 0) {
            log.info("存量同步预览完成：{} 个项目（仅预览，未覆盖）", done);
        }
    }
}
