package com.qa.autotest.application.plan;

import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.RunDispatcher;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.PlanGroupRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.plan.OverlapPolicy;
import com.qa.autotest.domain.plan.PlanGroup;
import com.qa.autotest.domain.plan.PlanLastRun;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 计划域单测的共用伪实现：不连库、不启调度、不执行 pabot。 */
final class PlanTestSupport {

    static final long PROJECT = 7L;
    static final long OPERATOR = 1L;
    static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private PlanTestSupport() {
    }

    static PlanRecord plan(long id, PlanStatus status, Instant lastFire, Instant nextFire,
                           String cron, List<String> suites) {
        return new PlanRecord(id, PROJECT, "plan-" + id, null, null, suites, null,
                1, 3600, cron, "Asia/Shanghai", status, OverlapPolicy.SKIP,
                lastFire, nextFire, OPERATOR, Instant.parse("2026-11-01T00:00:00Z"));
    }

    static RunRecord run(long id, String runId, RunState state) {
        return new RunRecord(id, runId, PROJECT, null, state, 5, 1, 0,
                1, state == RunState.FINISHED ? 1 : 0, state == RunState.FAILED ? 1 : 0,
                null, null, null, "/tmp/" + runId,
                Instant.parse("2026-11-03T02:00:00Z"), null, null, null, null);
    }

    /** 内存计划仓储，模拟 JDBC 的关键语义（到期过滤、last/next 推进、依赖表）。 */
    static final class FakePlanRepository implements PlanRepository {

        final Map<Long, PlanRecord> store = new LinkedHashMap<>();
        final Map<Long, List<Long>> edges = new LinkedHashMap<>();
        final Map<Long, Long> medians = new LinkedHashMap<>();
        boolean activeRunForPlan;
        int activeScheduledRuns;
        int lockDueCalls;

        void put(PlanRecord plan) {
            store.put(plan.id(), plan);
        }

        /** 设置某计划的历史运行时长 P50（秒），用于依赖图的预计时长断言。 */
        void median(long planId, long seconds) {
            medians.put(planId, seconds);
        }

        void depend(long planId, Long... dependsOn) {
            edges.put(planId, List.of(dependsOn));
        }

        @Override
        public PageResult<PlanRecord> page(long projectId, int page, int size) {
            List<PlanRecord> items = store.values().stream().toList();
            return new PageResult<>(items, items.size(), page, size);
        }

        @Override
        public Optional<PlanRecord> find(long projectId, long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public long insert(PlanRecord draft, Long operatorId) {
            long id = store.keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1;
            store.put(id, new PlanRecord(id, draft.projectId(), draft.name(), draft.testSetId(),
                    draft.environmentId(), draft.suitePaths(), draft.includeTag(),
                    draft.concurrency(), draft.timeoutSeconds(), draft.cron(), draft.timezone(),
                    draft.status(), draft.overlapPolicy(), draft.lastFireAt(), draft.nextFireAt(),
                    draft.createdBy(), draft.createdAt()));
            return id;
        }

        @Override
        public void update(PlanRecord plan, Long operatorId) {
            store.put(plan.id(), plan);
        }

        @Override
        public void delete(long projectId, long id) {
            store.remove(id);
        }

        @Override
        public int updateStatus(long projectId, long id, PlanStatus status) {
            PlanRecord current = store.get(id);
            if (current == null) {
                return 0;
            }
            store.put(id, new PlanRecord(current.id(), current.projectId(), current.name(),
                    current.testSetId(), current.environmentId(), current.suitePaths(),
                    current.includeTag(), current.concurrency(), current.timeoutSeconds(),
                    current.cron(), current.timezone(), status, current.overlapPolicy(),
                    current.lastFireAt(), current.nextFireAt(), current.createdBy(),
                    current.createdAt()));
            return 1;
        }

        @Override
        public int updateSchedule(long projectId, long id, Instant lastFireAt, Instant nextFireAt) {
            PlanRecord current = store.get(id);
            if (current == null) {
                return 0;
            }
            store.put(id, new PlanRecord(current.id(), current.projectId(), current.name(),
                    current.testSetId(), current.environmentId(), current.suitePaths(),
                    current.includeTag(), current.concurrency(), current.timeoutSeconds(),
                    current.cron(), current.timezone(), current.status(), current.overlapPolicy(),
                    lastFireAt, nextFireAt, current.createdBy(), current.createdAt()));
            return 1;
        }

        /** 复刻 SQL：只领取 enabled 且 next_fire_at <= now 的行。 */
        @Override
        public List<PlanRecord> lockDue(Instant now, int limit) {
            lockDueCalls++;
            return store.values().stream()
                    .filter(plan -> plan.status() == PlanStatus.ENABLED)
                    .filter(plan -> plan.nextFireAt() != null && !plan.nextFireAt().isAfter(now))
                    .sorted(Comparator.comparing(PlanRecord::nextFireAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean hasActiveRun(long projectId, long planId) {
            return activeRunForPlan;
        }

        @Override
        public int countActiveScheduledRuns(long projectId) {
            return activeScheduledRuns;
        }

        @Override
        public List<Long> dependencies(long projectId, long planId) {
            return edges.getOrDefault(planId, List.of());
        }

        @Override
        public Map<Long, List<Long>> allDependencies(long projectId) {
            return new LinkedHashMap<>(edges);
        }

        @Override
        public void replaceDependencies(long projectId, long planId, List<Long> dependsOn,
                                        Long operatorId) {
            edges.put(planId, List.copyOf(dependsOn));
        }

        @Override
        public void deleteDependencies(long projectId, long planId) {
            edges.remove(planId);
        }

        @Override
        public void deleteAllEdges(long projectId, long planId) {
            edges.remove(planId);
            edges.forEach((plan, deps) -> {
                if (deps.contains(planId)) {
                    edges.put(plan, deps.stream().filter(dep -> dep != planId).toList());
                }
            });
        }

        @Override
        public Optional<PlanLastRun> latestRun(long projectId, long planId) {
            return Optional.empty();
        }

        @Override
        public Map<Long, Long> medianDurationSeconds(long projectId) {
            return new LinkedHashMap<>(medians);
        }
    }

    /** 只读组仓储：只支撑依赖图的「组内链式边」来源判定。 */
    static final class StubGroupRepository implements PlanGroupRepository {

        final List<PlanGroup> groups = new ArrayList<>();

        /** 声明一个链式组：成员按传入顺序即 sort_order 升序。 */
        void chain(long groupId, Long... planIds) {
            List<PlanGroup.PlanGroupItem> items = new ArrayList<>();
            for (int i = 0; i < planIds.length; i++) {
                items.add(new PlanGroup.PlanGroupItem(planIds[i], "plan-" + planIds[i], i));
            }
            groups.add(new PlanGroup(groupId, PROJECT, "group-" + groupId, null, items,
                    OPERATOR, CLOCK.instant()));
        }

        @Override
        public List<PlanGroup> listByProject(long projectId) {
            return new ArrayList<>(groups);
        }

        @Override
        public Optional<PlanGroup> find(long projectId, long id) {
            return groups.stream().filter(group -> group.id() == id).findFirst();
        }

        @Override
        public Optional<PlanGroup> findByName(long projectId, String name) {
            return groups.stream().filter(group -> group.name().equals(name)).findFirst();
        }

        @Override
        public long insert(PlanGroup draft, Long operatorId) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public void update(long projectId, long id, String name, String description,
                            Long operatorId) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public void delete(long projectId, long id) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public int appendItems(long projectId, long groupId, List<Long> planIds, Long operatorId) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public void removeItem(long projectId, long groupId, long planId) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public void reorder(long projectId, long groupId, List<Long> planIds, Long operatorId) {
            throw new UnsupportedOperationException("StubGroupRepository 只读");
        }

        @Override
        public boolean existsInOtherProject(long projectId, long planId) {
            return false;
        }
    }

    /** RunService 桩：只实现调度用的 createForScheduler，不触发权限与真实 id 生成。 */
    static final class StubRunService extends RunService {

        final List<RunService.CreateRunCommand> commands = new ArrayList<>();
        final List<RunOrigin> origins = new ArrayList<>();
        final AtomicLong ids = new AtomicLong();

        StubRunService() {
            super(null, null, null, null, new ExecutionProperties(), CLOCK);
        }

        @Override
        public synchronized RunRecord createForScheduler(RunService.CreateRunCommand cmd,
                                                         RunOrigin origin) {
            commands.add(cmd);
            origins.add(origin);
            long id = ids.incrementAndGet();
            return run(id, "RUN-" + id, RunState.QUEUED);
        }
    }

    static final class StubAudit extends AuditService {

        final List<String> actions = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        StubAudit() {
            super(null);
        }

        @Override
        public synchronized void record(String action, String resourceType, String resourceId,
                                        String detail) {
            actions.add(action);
            details.add(detail == null ? "" : detail);
        }

        boolean contains(String action, String detailToken) {
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i).equals(action) && details.get(i).contains(detailToken)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 派发器桩：只记录提交，不跑任务（避免测试触发真实 pabot）。 */
    static final class RecordingDispatcher implements RunDispatcher {

        final List<String> runIds = new ArrayList<>();

        @Override
        public synchronized void submit(long projectId, Long planId, String runId,
                                        int priority, Runnable task) {
            runIds.add(runId);
        }
    }

    static PlanScheduleService service(FakePlanRepository plans, StubRunService runs,
                                       StubAudit audit, RecordingDispatcher dispatcher,
                                       PlanProperties properties) {
        return new PlanScheduleService(plans, runs, null, audit, properties, dispatcher, CLOCK);
    }
}
