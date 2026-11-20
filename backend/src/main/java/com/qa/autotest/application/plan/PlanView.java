package com.qa.autotest.application.plan;

import com.qa.autotest.domain.plan.PlanLastRun;
import com.qa.autotest.domain.plan.PlanRecord;

import java.time.Instant;
import java.util.List;

/**
 * 计划列表/详情视图（PLN-01）。nextFireTimes 为「未来 3 个」触发点，供前端预览；
 * 暂停的计划也会计算（只是 nextFireAt 为 null，表示当前不会被调度）。
 * lastRun* 支撑列表「最近结果」列。
 */
public record PlanView(long id,
                       long projectId,
                       String name,
                       Long testSetId,
                       Long environmentId,
                       List<String> suitePaths,
                       String includeTag,
                       int concurrency,
                       int timeoutSeconds,
                       String cron,
                       String timezone,
                       String status,
                       String overlapPolicy,
                       Instant lastFireAt,
                       Instant nextFireAt,
                       List<Instant> nextFireTimes,
                       List<Long> dependencyIds,
                       String lastRunId,
                       String lastRunState,
                       Instant lastRunFinishedAt,
                       Long createdBy,
                       Instant createdAt,
                       /** 配置异常原因（v5.33.0 / FR-v5.33-04）；{@code null} = 正常。 */
                       String cronIssue) {

    public static PlanView of(PlanRecord plan, List<Instant> nextFireTimes, List<Long> dependencyIds,
                              PlanLastRun lastRun) {
        return new PlanView(plan.id(), plan.projectId(), plan.name(), plan.testSetId(),
                plan.environmentId(), plan.suitePaths(), plan.includeTag(), plan.concurrency(),
                plan.timeoutSeconds(), plan.cron(), plan.timezone(), plan.status().dbValue(),
                plan.overlapPolicy().dbValue(), plan.lastFireAt(), plan.nextFireAt(),
                nextFireTimes == null ? List.of() : nextFireTimes,
                dependencyIds == null ? List.of() : dependencyIds,
                lastRun == null ? null : lastRun.runId(),
                lastRun == null ? null : lastRun.state(),
                lastRun == null ? null : lastRun.finishedAt(),
                plan.createdBy(), plan.createdAt(),
                // 列表要能直接标出「这条计划配置有问题」，而不是等用户点恢复才报错
                CronSchedule.issue(plan.cron(), plan.timezone()));
    }
}
