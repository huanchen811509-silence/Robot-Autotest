package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试用内存运行仓储：状态跃迁语义与 JDBC 实现保持一致的「from 必须匹配」。 */
final class InMemoryRunRepository implements RunRepository {

    private final Map<String, RunRecord> byRunId = new LinkedHashMap<>();
    private final Map<String, RunRecord> byRequest = new LinkedHashMap<>();
    private final Map<String, String> retryOf = new LinkedHashMap<>();
    private long seq;

    void seed(RunRecord run) {
        byRunId.put(run.runId(), run);
        seq = Math.max(seq, run.id());
    }

    Optional<RunRecord> peek(String runId) {
        return Optional.ofNullable(byRunId.get(runId));
    }

    /** 重试来源（{@code retry_of_run_id}）：RunRecord 不暴露该字段，测试从仓储侧读取。 */
    Optional<String> retryOf(String runId) {
        return Optional.ofNullable(retryOf.get(runId));
    }

    int insertCount() {
        return byRunId.size();
    }

    @Override
    public synchronized RunRecord insert(RunRecord draft, String jobNo, String runRequestId, Long operatorId) {
        if (byRequest.containsKey(runRequestId)) {
            // 复刻 uk_run_project_request：并发下第二个插入必然冲突。
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "duplicate run_request_id " + runRequestId);
        }
        long id = ++seq;
        RunRecord stored = new RunRecord(id, draft.runId(), draft.projectId(), draft.testSetId(),
                draft.state(), draft.priority(), draft.concurrency(), draft.attemptNo(),
                0, 0, 0, null, null, null, null, draft.enqueuedAt(), null, null, draft.deviceScope(), null);
        byRunId.put(stored.runId(), stored);
        byRequest.put(runRequestId, stored);
        return stored;
    }

    @Override
    public Optional<RunRecord> findByRunId(long projectId, String runId) {
        RunRecord stored = byRunId.get(runId);
        return stored != null && stored.projectId() == projectId
                ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public Optional<RunRecord> findByRequestId(long projectId, String requestId) {
        RunRecord stored = byRequest.get(requestId);
        return stored != null && stored.projectId() == projectId
                ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public int updateState(String runId, RunState from, RunState to,
                           Instant startedAt, Instant finishedAt,
                           String failureClass, String failureReason, String failureMessage) {
        RunRecord current = byRunId.get(runId);
        if (current == null || current.state() != from) {
            return 0;
        }
        byRunId.put(runId, new RunRecord(current.id(), current.runId(), current.projectId(),
                current.testSetId(), to, current.priority(), current.concurrency(),
                current.attemptNo(), current.totalTests(), current.passedTests(),
                current.failedTests(), failureClass, failureReason, failureMessage,
                current.artifactsDir(), current.enqueuedAt(),
                startedAt == null ? current.startedAt() : startedAt,
                finishedAt == null ? current.finishedAt() : finishedAt, current.deviceScope(), null));
        return 1;
    }

    @Override
    public int updateCounts(String runId, int total, int passed, int failed, String artifactsDir) {
        RunRecord current = byRunId.get(runId);
        if (current == null) {
            return 0;
        }
        byRunId.put(runId, new RunRecord(current.id(), current.runId(), current.projectId(),
                current.testSetId(), current.state(), current.priority(), current.concurrency(),
                current.attemptNo(), total, passed, failed, current.failureClass(),
                current.failureReason(), current.failureMessage(), artifactsDir,
                current.enqueuedAt(), current.startedAt(), current.finishedAt(), current.deviceScope(), null));
        return 1;
    }

    @Override
    public List<RunRecord> findActive() {
        return byRunId.values().stream().filter(r -> r.state().active()).toList();
    }

    @Override
    public PageResult<RunRecord> page(long projectId, RunState state, int page, int size) {
        List<RunRecord> items = byRunId.values().stream()
                .filter(r -> r.projectId() == projectId)
                .filter(r -> state == null || r.state() == state).toList();
        return new PageResult<>(items, items.size(), page, size);
    }

    @Override
    public int markAttempt(String runId, int attemptNo, String retryOfRunId) {
        RunRecord current = byRunId.get(runId);
        if (current == null) {
            return 0;
        }
        byRunId.put(runId, new RunRecord(current.id(), current.runId(), current.projectId(),
                current.testSetId(), current.state(), current.priority(), current.concurrency(),
                attemptNo, current.totalTests(), current.passedTests(), current.failedTests(),
                current.failureClass(), current.failureReason(), current.failureMessage(),
                current.artifactsDir(), current.enqueuedAt(), current.startedAt(),
                current.finishedAt(), current.deviceScope(), null));
        retryOf.put(runId, retryOfRunId);
        return 1;
    }
}
