package com.qa.autotest.application.ci;

import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.plan.PlanProperties;
import com.qa.autotest.application.plan.PlanScheduleService;
import com.qa.autotest.application.repository.CiTriggerRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.ProjectApiTokenRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.domain.ci.CiTriggerRecord;
import com.qa.autotest.domain.ci.CiWritebackStatus;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.ProjectApiToken;
import com.qa.autotest.domain.plan.OverlapPolicy;
import com.qa.autotest.domain.plan.PlanLastRun;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataIntegrityViolationException;

/** CI 域单测的共用伪实现：不连库、不发 HTTP、不派发真实执行。 */
final class CiTestSupport {

    static final long PROJECT = 7L;
    static final long OTHER_PROJECT = 8L;
    static final long OPERATOR = 9L;
    static final String SCOPE = "ci:trigger";
    static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private CiTestSupport() {
    }

    static PlanRecord plan(long id, long projectId) {
        return new PlanRecord(id, projectId, "plan-" + id, null, null,
                List.of("suites/Connectivity"), null, 1, 3600, "0 * * * *",
                "Asia/Shanghai", PlanStatus.ENABLED, OverlapPolicy.SKIP, null, null,
                OPERATOR, Instant.parse("2026-11-01T00:00:00Z"));
    }

    static ProjectApiToken token(long id, Long projectId, String scopes) {
        return new ProjectApiToken(id, OPERATOR, "ci-token-" + id, projectId, scopes);
    }

    static RunRecord run(long id, String runId, long projectId, RunState state) {
        return new RunRecord(id, runId, projectId, null, state, 5, 1, 0,
                1, state == RunState.FINISHED ? 1 : 0, state == RunState.FAILED ? 1 : 0,
                null, null, null, "/tmp/" + runId,
                Instant.parse("2026-11-03T02:00:00Z"),
                state == RunState.QUEUED ? null : Instant.parse("2026-11-03T02:01:00Z"),
                state.terminal() ? Instant.parse("2026-11-03T02:02:00Z") : null, null, null);
    }

    static CiProperties properties() {
        CiProperties properties = new CiProperties();
        properties.setTriggerPerMinute(10);
        properties.setTriggerWindowSeconds(60);
        return properties;
    }

    /** 只实现 find 的计划仓储；其余方法在被调用时显式失败，避免测试误用。 */
    static final class FakePlanRepository implements PlanRepository {

        final Map<Long, PlanRecord> store = new LinkedHashMap<>();

        void put(PlanRecord plan) {
            store.put(plan.id(), plan);
        }

        @Override
        public Optional<PlanRecord> find(long projectId, long id) {
            PlanRecord found = store.get(id);
            if (found == null || found.projectId() != projectId) {
                return Optional.empty();
            }
            return Optional.of(found);
        }

        @Override
        public PageResult<PlanRecord> page(long projectId, int page, int size) {
            throw new UnsupportedOperationException("CI 单测不使用 page");
        }

        @Override
        public long insert(PlanRecord draft, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(PlanRecord plan, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long projectId, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateStatus(long projectId, long id, PlanStatus status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateSchedule(long projectId, long id, Instant lastFireAt, Instant nextFireAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PlanRecord> lockDue(Instant now, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasActiveRun(long projectId, long planId) {
            return false;
        }

        @Override
        public int countActiveScheduledRuns(long projectId) {
            return 0;
        }

        @Override
        public List<Long> dependencies(long projectId, long planId) {
            return List.of();
        }

        @Override
        public Map<Long, List<Long>> allDependencies(long projectId) {
            return Map.of();
        }

        @Override
        public void replaceDependencies(long projectId, long planId, List<Long> dependsOn,
                                        Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteDependencies(long projectId, long planId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllEdges(long projectId, long planId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlanLastRun> latestRun(long projectId, long planId) {
            return Optional.empty();
        }

        @Override
        public Map<Long, Long> medianDurationSeconds(long projectId) {
            return Map.of();
        }
    }

    /** 只记录调用，不建 run、不派发执行。 */
    static final class StubSchedules extends PlanScheduleService {

        final List<PlanRecord> plans = new ArrayList<>();
        final List<String> requestIds = new ArrayList<>();
        final AtomicLong ids = new AtomicLong();

        StubSchedules() {
            super(null, null, null, null, new PlanProperties(), null, CLOCK);
        }

        @Override
        public synchronized RunRecord triggerCi(PlanRecord plan, String requestId) {
            plans.add(plan);
            requestIds.add(requestId);
            long id = ids.incrementAndGet();
            return run(id, "RUN-CI-" + id, plan.projectId(), RunState.QUEUED);
        }
    }

    /** 内存 ci_trigger 仓储，唯一键冲突时抛 DataIntegrityViolationException（模拟真实库）。 */
    static final class FakeCiTriggerRepository implements CiTriggerRepository {

        final Map<Long, CiTriggerRecord> store = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong();

        void put(CiTriggerRecord record) {
            store.put(record.id(), record);
        }

        List<CiTriggerRecord> all() {
            return new ArrayList<>(store.values());
        }

        @Override
        public Optional<CiTriggerRecord> findByPlanAndCommit(long projectId, long planId,
                                                             String commitSha) {
            return store.values().stream()
                    .filter(item -> item.projectId() == projectId && item.planId() == planId
                            && item.commitSha().equals(commitSha))
                    .findFirst();
        }

        @Override
        public Optional<CiTriggerRecord> findByRunId(long projectId, String runId) {
            return store.values().stream()
                    .filter(item -> item.projectId() == projectId && runId != null
                            && runId.equals(item.runId()))
                    .findFirst();
        }

        @Override
        public long insert(CiTriggerRecord draft, Long operatorId) {
            boolean duplicated = store.values().stream()
                    .anyMatch(item -> item.planId() == draft.planId()
                            && item.commitSha().equals(draft.commitSha()));
            if (duplicated) {
                throw new DataIntegrityViolationException("uk_ci_trigger_plan_commit");
            }
            long id = ids.incrementAndGet();
            store.put(id, new CiTriggerRecord(id, draft.projectId(), draft.planId(),
                    draft.commitSha(), draft.ref(), draft.build(), draft.repo(), draft.runId(),
                    draft.writebackStatus(), draft.writebackError(), CLOCK.instant()));
            return id;
        }

        @Override
        public int updateRunId(long projectId, long id, String runId) {
            CiTriggerRecord current = store.get(id);
            if (current == null || current.projectId() != projectId) {
                return 0;
            }
            store.put(id, new CiTriggerRecord(current.id(), current.projectId(), current.planId(),
                    current.commitSha(), current.ref(), current.build(), current.repo(), runId,
                    current.writebackStatus(), current.writebackError(), current.createdAt()));
            return 1;
        }

        @Override
        public int updateWriteback(long projectId, long id, CiWritebackStatus status, String error,
                                   Long operatorId) {
            CiTriggerRecord current = store.get(id);
            if (current == null || current.projectId() != projectId) {
                return 0;
            }
            store.put(id, new CiTriggerRecord(current.id(), current.projectId(), current.planId(),
                    current.commitSha(), current.ref(), current.build(), current.repo(),
                    current.runId(), status, error, current.createdAt()));
            return 1;
        }
    }

    static final class RecordingAudit extends AuditService {

        final List<String> actions = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        RecordingAudit() {
            super(null);
        }

        @Override
        public synchronized void record(String action, String resourceType, String resourceId,
                                        String detail) {
            actions.add(action);
            details.add(detail == null ? "" : detail);
        }

        boolean has(String action) {
            return actions.contains(action);
        }

        boolean contains(String token) {
            return details.stream().anyMatch(item -> item.contains(token));
        }
    }

    /** 令牌仓储桩：按哈希命中预置令牌。 */
    static final class FakeTokenRepository implements ProjectApiTokenRepository {

        final Map<String, ProjectApiToken> byHash = new LinkedHashMap<>();

        void put(String hash, ProjectApiToken token) {
            byHash.put(hash, token);
        }

        @Override
        public Optional<ProjectApiToken> findActiveByHash(String tokenHash, Instant now) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public void touch(String tokenHash, Instant usedAt) {
            // 单测不关心 last_used_at
        }
    }

    /** 只支持「运行结束钩子」所需的读写。 */
    static final class FakeRunRepository implements RunRepository {

        RunRecord current;
        int stateUpdates;

        void put(RunRecord record) {
            this.current = record;
        }

        @Override
        public RunRecord insert(RunRecord draft, String jobNo, String runRequestId, Long operatorId) {
            current = draft;
            return draft;
        }

        @Override
        public Optional<RunRecord> findByRunId(long projectId, String runId) {
            if (current == null || current.projectId() != projectId
                    || !current.runId().equals(runId)) {
                return Optional.empty();
            }
            return Optional.of(current);
        }

        @Override
        public Optional<RunRecord> findByRequestId(long projectId, String requestId) {
            return Optional.empty();
        }

        @Override
        public int updateState(String runId, RunState from, RunState to, Instant startedAt,
                               Instant finishedAt, String failureClass, String failureReason,
                               String failureMessage) {
            if (current == null || current.state() != from) {
                return 0;
            }
            stateUpdates++;
            current = new RunRecord(current.id(), current.runId(), current.projectId(),
                    current.testSetId(), to, current.priority(), current.concurrency(),
                    current.attemptNo(), current.totalTests(), current.passedTests(),
                    current.failedTests(), failureClass, failureReason, failureMessage,
                    current.artifactsDir(), current.enqueuedAt(), startedAt, finishedAt, current.deviceScope(), null);
            return 1;
        }

        @Override
        public int updateCounts(String runId, int total, int passed, int failed, String artifactsDir) {
            return 0;
        }

        @Override
        public List<RunRecord> findActive() {
            return List.of();
        }

        @Override
        public PageResult<RunRecord> page(long projectId, RunState state, int page, int size) {
            return new PageResult<>(List.of(), 0, page, size);
        }

        @Override
        public int markAttempt(String runId, int attemptNo, String retryOf) {
            return 0;
        }
    }

    static RunService runService(RunRepository runs, AuditService audit,
                                 List<com.qa.autotest.application.execution.RunCompletionListener> listeners) {
        return new RunService(runs, null, audit, null, new ExecutionProperties(), CLOCK, listeners);
    }
}
