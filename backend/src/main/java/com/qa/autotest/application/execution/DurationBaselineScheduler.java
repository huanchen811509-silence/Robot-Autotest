package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.CaseDurationBaselineRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 时长基线定时重算（FR-v5.3-03 / W53-03）。
 *
 * <p>每日凌晨重算「近 30 天有 run 的项目」。不在每次 run 结束后重算：
 * 一次 run 结束就重算 469 个用例的分位数，开销与收益完全不成比例，
 * 而基线本身是按天稳定的统计量。
 *
 * <p>单项目失败不影响其它项目：一个项目的基线算不出来不该拖住全部，
 * 而且异常必须留日志——静默失败会让「基线一直是上周的」这件事无人发现。
 */
@Component
public class DurationBaselineScheduler {

    private static final Logger log = LoggerFactory.getLogger(DurationBaselineScheduler.class);

    private final CaseDurationBaselineRepository repository;
    private DurationBaselineService service;

    public DurationBaselineScheduler(CaseDurationBaselineRepository repository) {
        this.repository = repository;
    }

    /** 延迟注入：避免 scheduler 与 service 形成构造期循环（service 也依赖同一仓储）。 */
    @Autowired(required = false)
    void setService(DurationBaselineService service) {
        this.service = service;
    }

    /**
     * 每日 03:30 重算（cron 可配：`execution.baseline-recalc-cron`）。
     *
     * <p>放在凌晨是因为它是全表聚合：与夜间执行窗口错开，避免抢台架与数据库。
     */
    @Scheduled(cron = "${execution.baseline-recalc-cron:0 30 3 * * *}")
    public void dailyRecalc() {
        recalcAll();
    }

    /** 抽出来供测试与运维手工触发；返回处理的批次数。 */
    public int recalcAll() {
        if (service == null) {
            log.warn("时长基线服务未装配，跳过定时重算");
            return 0;
        }
        Instant since = Instant.now().minus(Duration.ofDays(DurationBaselineService.WINDOW_DAYS));
        List<Long> projectIds;
        try {
            projectIds = repository.projectsWithRecentRuns(since);
        } catch (RuntimeException e) {
            log.warn("查询待重算项目失败，跳过本轮：{}", e.toString());
            return 0;
        }
        int done = 0;
        for (Long projectId : projectIds) {
            try {
                DurationBaselineService.RecalcResult result = service.recompute(projectId);
                done++;
                log.info("时长基线重算 project={} 分组={} 写入={} 样本不足={} 样本={} 剔除={} 跳过={}",
                        projectId, result.groups(), result.computed(), result.insufficient(),
                        result.samples(), result.excluded(), result.skippedGroups());
            } catch (RuntimeException e) {
                log.warn("时长基线重算失败 project={}：{}", projectId, e.toString());
            }
        }
        return done;
    }
}
