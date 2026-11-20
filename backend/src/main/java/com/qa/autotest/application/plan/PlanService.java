package com.qa.autotest.application.plan;

import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.Audited;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.plan.OverlapPolicy;
import com.qa.autotest.domain.plan.PlanCycleException;
import com.qa.autotest.domain.plan.PlanDependencyGraph;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 执行计划管理（PLN-01 + PLN-02）：CRUD、暂停/恢复、立即触发、依赖保存。
 *
 * <p>权限口径（按现有 {@link PermissionAction} 枚举，库内<b>没有 MANAGE</b>）：
 * <ul>
 *   <li>读：{@code EXECUTION:READ}（VIEWER 及以上）；</li>
 *   <li>写（创建/修改/删除/暂停/恢复）：{@code EXECUTION:UPDATE}。该动作在
 *       {@code PermissionMatrix} 中授予 PM / ENGINEER / ADMIN，相当于「计划维护者」；
 *       不用 DELETE 是因为 ENGINEER 没有 EXECUTION:DELETE，用它会把日常维护者排除在外。</li>
 *   <li>立即触发：{@code EXECUTION:EXECUTE}（VIEWER 无）。</li>
 * </ul>
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final TestSetRepository testSets;
    private final AccessControlService access;
    private final AuditService audit;
    private final PlanScheduleService schedules;
    private final ExecutionProperties executionProps;
    private final Clock clock;

    public PlanService(PlanRepository plans,
                       TestSetRepository testSets,
                       AccessControlService access,
                       AuditService audit,
                       PlanScheduleService schedules,
                       ExecutionProperties executionProps,
                       Clock clock) {
        this.plans = plans;
        this.testSets = testSets;
        this.access = access;
        this.audit = audit;
        this.schedules = schedules;
        this.executionProps = executionProps;
        this.clock = clock;
    }

    public PageResult<PlanView> page(long projectId, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分页参数越界");
        }
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        PageResult<PlanRecord> records = plans.page(projectId, page, size);
        List<PlanView> views = new ArrayList<>(records.items().size());
        for (PlanRecord plan : records.items()) {
            views.add(view(plan, plans.dependencies(projectId, plan.id())));
        }
        return new PageResult<>(views, records.total(), records.page(), records.size());
    }

    @Transactional
    public PlanView create(PlanCommand command) {
        if (command == null || command.projectId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        access.bindProject(command.projectId());
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        validate(command, null);
        assertNoCycle(command.projectId(), 0L, command.dependencyIds());

        Instant now = clock.instant();
        CronSchedule schedule = CronSchedule.parse(command.cron(), command.timezone());
        PlanRecord draft = new PlanRecord(0L, command.projectId(), command.name().trim(),
                command.testSetId(), command.environmentId(), command.suitePaths(),
                command.includeTag(), command.concurrency(), command.timeoutSeconds(),
                command.cron().trim(), command.timezone(), PlanStatus.ENABLED,
                overlap(command.overlapPolicy()), null, schedule.nextAfter(now),
                TenantContext.userId(), now);
        long id = plans.insert(draft, TenantContext.userId());
        plans.replaceDependencies(command.projectId(), id, command.dependencyIds(),
                TenantContext.userId());
        audit.record("PLAN_CREATE", "execution_plan", String.valueOf(id),
                "cron=" + draft.cron() + ", timezone=" + draft.timezone());
        return view(load(command.projectId(), id), plans.dependencies(command.projectId(), id));
    }

    /**
     * 修改计划。暂停状态下修改不会自动恢复；启用状态下 next_fire_at 从「当前之后的下一个点」
     * 重算，避免把已过去的时间点当成待触发。
     */
    @Audited(action = "schedule:update", resource = "execution_plan")
    @Transactional
    public PlanView update(long id, PlanCommand command) {
        if (command == null || command.projectId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        access.bindProject(command.projectId());
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        PlanRecord existing = load(command.projectId(), id);
        validate(command, existing);
        assertNoCycle(command.projectId(), id, command.dependencyIds());

        Instant now = clock.instant();
        CronSchedule schedule = CronSchedule.parse(command.cron(), command.timezone());
        Instant nextFire = existing.status() == PlanStatus.ENABLED ? schedule.nextAfter(now) : null;
        PlanRecord updated = new PlanRecord(id, command.projectId(), command.name().trim(),
                command.testSetId(), command.environmentId(), command.suitePaths(),
                command.includeTag(), command.concurrency(), command.timeoutSeconds(),
                command.cron().trim(), command.timezone(), existing.status(),
                overlap(command.overlapPolicy()), existing.lastFireAt(), nextFire,
                existing.createdBy(), existing.createdAt());
        plans.update(updated, TenantContext.userId());
        plans.replaceDependencies(command.projectId(), id, command.dependencyIds(),
                TenantContext.userId());
        audit.recordChange("schedule:update", "execution_plan", String.valueOf(id),
                "name=" + existing.name() + "; cron=" + existing.cron(),
                "name=" + updated.name() + "; cron=" + updated.cron());
        return view(updated, plans.dependencies(command.projectId(), id));
    }

    @Audited(action = "schedule:delete", resource = "execution_plan")
    @Transactional
    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        PlanRecord existing = load(projectId, id);
        plans.deleteAllEdges(projectId, id);
        plans.delete(projectId, id);
        audit.recordChange("schedule:delete", "execution_plan", String.valueOf(id),
                "name=" + existing.name() + "; cron=" + existing.cron(), null);
    }

    /** 暂停：后续 tick 不再命中；next_fire_at 置空，恢复前不保留待触发点。 */
    @Transactional
    public PlanView pause(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        PlanRecord existing = load(projectId, id);
        if (existing.status() == PlanStatus.PAUSED) {
            return view(existing, plans.dependencies(projectId, id));
        }
        plans.updateStatus(projectId, id, PlanStatus.PAUSED);
        plans.updateSchedule(projectId, id, existing.lastFireAt(), null);
        audit.record("PLAN_PAUSE", "execution_plan", String.valueOf(id), "");
        return view(new PlanRecord(existing.id(), existing.projectId(), existing.name(),
                existing.testSetId(), existing.environmentId(), existing.suitePaths(),
                existing.includeTag(), existing.concurrency(), existing.timeoutSeconds(),
                existing.cron(), existing.timezone(), PlanStatus.PAUSED, existing.overlapPolicy(),
                existing.lastFireAt(), null, existing.createdBy(), existing.createdAt()),
                plans.dependencies(projectId, id));
    }

    /** 批量启停（W5）：对多条计划应用同一状态，逐条返回结果，单条失败不影响其余。 */
    @Audited(action = "schedule:batch", resource = "execution_plan")
    @Transactional
    public List<BatchPlanResult> batchStatus(long projectId, List<Long> ids, String status) {
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "planIds 数量必须在 1..100");
        }
        PlanStatus target;
        try {
            target = PlanStatus.valueOf(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "status 只支持 ENABLED / PAUSED");
        }
        if (target != PlanStatus.ENABLED && target != PlanStatus.PAUSED) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "status 只支持 ENABLED / PAUSED");
        }

        Instant now = clock.instant();
        List<BatchPlanResult> results = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null) {
                continue;
            }
            PlanRecord existing;
            try {
                existing = load(projectId, id);
            } catch (BusinessException ex) {
                results.add(new BatchPlanResult(id, false, status, "计划不存在或不属于当前项目"));
                continue;
            }
            if (existing.status() == target) {
                results.add(new BatchPlanResult(id, false, target.name(), "已处于目标状态"));
                continue;
            }
            Instant nextFire = null;
            Instant lastFire = existing.lastFireAt();
            if (target == PlanStatus.ENABLED) {
                nextFire = CronSchedule.parse(existing.cron(), existing.timezone()).nextAfter(now);
                lastFire = now;
            }
            plans.updateStatus(projectId, id, target);
            plans.updateSchedule(projectId, id, lastFire, nextFire);
            audit.record(target == PlanStatus.PAUSED ? "PLAN_BATCH_PAUSE" : "PLAN_BATCH_ENABLE",
                    "execution_plan", String.valueOf(id), "batch");
            results.add(new BatchPlanResult(id, true, target.name(), "已更新"));
        }
        return results;
    }

    /**
     * 批量启停的单条结果。
     *
     * @param changed 是否真的改了库（已处于目标状态时为 false，便于前端区分）
     * @param message 中文原因；单条失败不抛异常，避免一条脏 id 让整批回滚
     */
    public record BatchPlanResult(long planId, boolean changed, String status, String message) {
    }

    /**
     * 恢复：从「下一次触发点」重新计时，不补跑暂停期间错过的历史点。
     * 因此把 last_fire_at 重置为 now，让 missed 判定只看恢复之后。
     */
    @Transactional
    public PlanView resume(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        PlanRecord existing = load(projectId, id);
        // 存量异常配置不许「恢复」：恢复后它就是真的每 60 秒以内的调度，
        // 原先只是被暂停着才没出事（FR-v5.33-04 判据 4）。
        String resumeIssue = CronSchedule.issue(existing.cron(), existing.timezone(), clock.instant());
        if (resumeIssue != null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "该计划配置异常，请先修正 cron 再恢复：" + resumeIssue);
        }
        Instant now = clock.instant();
        CronSchedule schedule = CronSchedule.parse(existing.cron(), existing.timezone());
        Instant nextFire = schedule.nextAfter(now);
        if (existing.status() == PlanStatus.ENABLED) {
            return view(existing, plans.dependencies(projectId, id));
        }
        plans.updateStatus(projectId, id, PlanStatus.ENABLED);
        plans.updateSchedule(projectId, id, now, nextFire);
        audit.record("PLAN_RESUME", "execution_plan", String.valueOf(id),
                "next=" + nextFire + ", no_backfill=true");
        return view(new PlanRecord(existing.id(), existing.projectId(), existing.name(),
                existing.testSetId(), existing.environmentId(), existing.suitePaths(),
                existing.includeTag(), existing.concurrency(), existing.timeoutSeconds(),
                existing.cron(), existing.timezone(), PlanStatus.ENABLED, existing.overlapPolicy(),
                now, nextFire, existing.createdBy(), existing.createdAt()),
                plans.dependencies(projectId, id));
    }

    /**
     * 立即触发：忽略依赖与项目并发上限，但权限仍需 EXECUTION:EXECUTE，
     * 且 trigger_type 仍写 manual（便于与定时触发区分）。
     */
    public RunRecord triggerNow(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.EXECUTE);
        PlanRecord plan = load(projectId, id);
        return schedules.triggerManual(plan);
    }

    /** cron 预览：未来 3 次触发点；非法表达式直接 400。 */
    public List<Instant> preview(long projectId, String cron, String timezone) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        try {
            return CronSchedule.parse(cron, timezone).nextTimes(clock.instant(), 3);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
        }
    }

    private PlanView view(PlanRecord plan, List<Long> dependencyIds) {
        // nextFireTimes 是「若现在启用，未来 3 次会在何时」的预览，暂停也照样计算；
        // 真正的待触发点看 nextFireAt（暂停时为 null）。
        List<Instant> nextFireTimes = List.of();
        try {
            nextFireTimes = CronSchedule.parse(plan.cron(), plan.timezone())
                    .nextTimes(clock.instant(), 3);
        } catch (IllegalArgumentException ignored) {
            // 历史脏数据（cron 非法）不阻断列表展示，调度 tick 会记录 PLAN_CRON_INVALID。
        }
        return PlanView.of(plan, nextFireTimes, dependencyIds,
                plans.latestRun(plan.projectId(), plan.id()).orElse(null));
    }

    private PlanRecord load(long projectId, long id) {
        return plans.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "执行计划不存在: " + id));
    }

    private void validate(PlanCommand command, PlanRecord existing) {
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请求为空");
        }
        if (command.projectId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "计划名称不能为空");
        }
        if (name.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "计划名称不能超过 128 字符");
        }
        List<String> suites = command.suitePaths().stream()
                .map(path -> path == null ? "" : path.trim())
                .filter(path -> !path.isEmpty())
                .toList();
        if (suites.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "suitePaths 不能为空");
        }
        int maxParallel = Math.max(1, executionProps.getMaxParallel());
        if (command.concurrency() < 1 || command.concurrency() > maxParallel) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "concurrency 必须在 1~" + maxParallel);
        }
        if (command.timeoutSeconds() <= 0 || command.timeoutSeconds() > 24 * 3600) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "timeoutSeconds 必须在 1~86400");
        }
        if (command.includeTag() != null && command.includeTag().length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "includeTag 不能超过 64 字符");
        }
        try {
            CronSchedule.parse(command.cron(), command.timezone());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
        }
        // v5.33.0 / FR-v5.33-04：能解析不等于能调。间隔 < 60 秒等价于「每秒跑一次」，
        // 存量里就有 `0 * * * * *` 这种（每分钟，合法）与 `* * * * * *`（每秒，事故）混着。
        String cronIssue = CronSchedule.issue(command.cron(), command.timezone(), clock.instant());
        if (cronIssue != null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, cronIssue);
        }
        if (command.testSetId() != null && testSets.find(command.projectId(), command.testSetId()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "用例集不存在或不属于当前项目: " + command.testSetId());
        }
        for (Long dependency : command.dependencyIds()) {
            if (dependency == null || dependency <= 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "依赖计划 id 非法");
            }
            if (existing != null && dependency == existing.id()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "计划不能依赖自己");
            }
            if (plans.find(command.projectId(), dependency).isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "依赖的计划不存在或不属于当前项目: " + dependency);
            }
        }
    }

    /**
     * 保存前环检测：把要保存的依赖代入整张图，成环则抛 400，不写库。
     */
    private void assertNoCycle(long projectId, long planId, List<Long> dependencyIds) {
        Map<Long, List<Long>> edges = plans.allDependencies(projectId);
        PlanDependencyGraph graph = PlanDependencyGraph.of(edges);
        List<Long> normalized = new ArrayList<>(new LinkedHashSet<>(dependencyIds));
        try {
            graph.assertNoCycle(planId, normalized);
        } catch (PlanCycleException cycle) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, cycle.getMessage());
        }
    }

    private static OverlapPolicy overlap(String value) {
        try {
            return OverlapPolicy.fromDb(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不支持的 overlapPolicy: " + value);
        }
    }
}
