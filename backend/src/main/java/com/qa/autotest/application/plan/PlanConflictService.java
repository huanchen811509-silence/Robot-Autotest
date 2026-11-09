package com.qa.autotest.application.plan;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.notification.NotificationService;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计划编排助手：冲突检测（W5/v3.0.1）。
 *
 * <p>只报四类可以确定判定的问题，不做「可能冲突」的模糊推断：
 * <ol>
 *   <li>{@code INVALID_CRON}：启用中的计划 cron 解析不了 —— 它永远不触发，是硬故障；</li>
 *   <li>{@code DUPLICATE_TARGET}：多个启用计划跑完全相同的 testSet + 套件 + 标签，
 *       等于同一批用例被重复执行；</li>
 *   <li>{@code SAME_SLOT}：多个启用计划在未来 3 个触发点里撞到同一秒，会同时压执行队列；</li>
 *   <li>{@code DISABLED_DEPENDENCY}：计划依赖的上游被暂停 —— 该计划会永远等不到触发。</li>
 * </ol>
 *
 * <p>检测是只读的：不自动改计划、不写库。批量启停由 {@code PlanService.batchStatus} 单独做，
 * 两者拆开，用户能先看清冲突再决定改哪几条。
 */
@Service
public class PlanConflictService {

    /** 上限保护：冲突检测要拉全量计划并逐条算 cron，500 条已足够覆盖本项目的编排规模。 */
    private static final int MAX_PLANS = 500;
    private static final int PAGE_SIZE = 100;
    private static final int SLOT_COUNT = 3;

    private final PlanRepository plans;
    private final AccessControlService access;
    private final Clock clock;
    private final NotificationService notifications;

    /** 兼容既有单测：不传通知服务时保持纯只读。 */
    public PlanConflictService(PlanRepository plans, AccessControlService access, Clock clock) {
        this(plans, access, clock, null);
    }

    @Autowired
    public PlanConflictService(PlanRepository plans, AccessControlService access, Clock clock,
                               NotificationService notifications) {
        this.plans = plans;
        this.access = access;
        this.clock = clock;
        this.notifications = notifications;
    }

    /**
     * @param type     INVALID_CRON / DUPLICATE_TARGET / SAME_SLOT / DISABLED_DEPENDENCY
     * @param severity FAIL（永远不触发/等不到）或 WARN（重复执行/撞点）
     * @param planIds  涉及的计划 id，升序
     * @param detail   给用户看的一句话，带计划名与具体值
     */
    public record PlanConflict(String type, String severity, List<Long> planIds, String detail) {
    }

    public List<PlanConflict> detect(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);

        List<PlanRecord> all = loadAll(projectId);
        Map<Long, PlanRecord> byId = new HashMap<>();
        all.forEach(plan -> byId.put(plan.id(), plan));
        List<PlanRecord> enabled = all.stream()
                .filter(plan -> plan.status() == PlanStatus.ENABLED)
                .toList();

        List<PlanConflict> conflicts = new ArrayList<>();
        Map<Long, List<Instant>> slots = new LinkedHashMap<>();
        for (PlanRecord plan : enabled) {
            try {
                slots.put(plan.id(), CronSchedule.parse(plan.cron(), plan.timezone())
                        .nextTimes(clock.instant(), SLOT_COUNT));
            } catch (IllegalArgumentException ex) {
                conflicts.add(new PlanConflict("INVALID_CRON", "FAIL", List.of(plan.id()),
                        "计划「" + plan.name() + "」cron 无法解析：" + ex.getMessage()));
            }
        }

        conflicts.addAll(duplicateTargets(enabled));
        conflicts.addAll(sameSlots(enabled, slots));
        conflicts.addAll(disabledDependencies(enabled, byId));

        conflicts.sort((left, right) -> {
            int byType = left.type().compareTo(right.type());
            if (byType != 0) {
                return byType;
            }
            return Long.compare(left.planIds().get(0), right.planIds().get(0));
        });
        if (notifications != null && !conflicts.isEmpty()) {
            // 检测点本身就是「有冲突」的权威信号；按项目聚合一条，upsert 保证不刷屏。
            notifications.notifyPlanConflict(projectId, "plan-conflict:" + projectId,
                    summarize(conflicts));
        }
        return conflicts;
    }

    private static String summarize(List<PlanConflict> conflicts) {
        StringBuilder message = new StringBuilder("检测到 ")
                .append(conflicts.size()).append(" 条计划冲突：")
                .append(conflicts.get(0).detail());
        if (conflicts.size() > 1) {
            message.append(" 等");
        }
        return message.length() > 500 ? message.substring(0, 500) : message.toString();
    }

    private List<PlanRecord> loadAll(long projectId) {
        List<PlanRecord> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PLANS / PAGE_SIZE; page++) {
            PageResult<PlanRecord> result = plans.page(projectId, page, PAGE_SIZE);
            all.addAll(result.items());
            if (result.items().size() < PAGE_SIZE || all.size() >= MAX_PLANS) {
                break;
            }
        }
        return all.size() > MAX_PLANS ? all.subList(0, MAX_PLANS) : all;
    }

    private List<PlanConflict> duplicateTargets(List<PlanRecord> enabled) {
        Map<String, List<PlanRecord>> byTarget = new TreeMap<>();
        for (PlanRecord plan : enabled) {
            if (plan.suitePaths().isEmpty()) {
                continue; // 未配置套件的计划由调度器跳过，重复与否没有意义
            }
            String key = (plan.testSetId() == null ? "-" : plan.testSetId().toString())
                    + '|' + String.join(",", plan.suitePaths())
                    + '|' + (plan.includeTag() == null ? "" : plan.includeTag());
            byTarget.computeIfAbsent(key, ignored -> new ArrayList<>()).add(plan);
        }
        List<PlanConflict> conflicts = new ArrayList<>();
        for (List<PlanRecord> group : byTarget.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<Long> ids = group.stream().map(PlanRecord::id).sorted().toList();
            conflicts.add(new PlanConflict("DUPLICATE_TARGET", "WARN", ids,
                    "计划 " + ids + " 的目标完全相同（套件 " + String.join(",", group.get(0).suitePaths())
                            + "），会重复执行同一批用例"));
        }
        return conflicts;
    }

    private List<PlanConflict> sameSlots(List<PlanRecord> enabled, Map<Long, List<Instant>> slots) {
        Map<Instant, List<Long>> byInstant = new TreeMap<>();
        for (PlanRecord plan : enabled) {
            for (Instant slot : slots.getOrDefault(plan.id(), List.of())) {
                byInstant.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(plan.id());
            }
        }
        // 同一组计划往往会在未来 3 个触发点全部撞上，逐点报会把面板刷满：
        // 按「参与撞点的计划集合」聚合，只报最早的那一次，detail 里点明具体时刻。
        Map<List<Long>, Instant> firstSlot = new LinkedHashMap<>();
        for (Map.Entry<Instant, List<Long>> entry : byInstant.entrySet()) {
            List<Long> ids = entry.getValue().stream().distinct().sorted().toList();
            if (ids.size() < 2) {
                continue;
            }
            firstSlot.putIfAbsent(ids, entry.getKey());
        }
        List<PlanConflict> conflicts = new ArrayList<>();
        for (Map.Entry<List<Long>, Instant> entry : firstSlot.entrySet()) {
            conflicts.add(new PlanConflict("SAME_SLOT", "WARN", entry.getKey(),
                    "计划 " + entry.getKey() + " 会在 " + entry.getValue() + " 同时触发，可能挤占执行队列"));
        }
        return conflicts;
    }

    private List<PlanConflict> disabledDependencies(List<PlanRecord> enabled, Map<Long, PlanRecord> byId) {
        List<PlanConflict> conflicts = new ArrayList<>();
        for (PlanRecord plan : enabled) {
            List<Long> disabled = plans.dependencies(plan.projectId(), plan.id()).stream()
                    .filter(depId -> {
                        PlanRecord dep = byId.get(depId);
                        return dep != null && dep.status() != PlanStatus.ENABLED;
                    })
                    .sorted()
                    .toList();
            if (!disabled.isEmpty()) {
                conflicts.add(new PlanConflict("DISABLED_DEPENDENCY", "FAIL", List.of(plan.id()),
                        "计划「" + plan.name() + "」依赖的上游计划 " + disabled + " 已暂停，永远不会触发"));
            }
        }
        return conflicts;
    }
}
