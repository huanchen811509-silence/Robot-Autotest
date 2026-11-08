package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.RunRetryOrigin;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 运行记录仓储实现。创建 run 时同一事务写 execution_job，租约 job_id 指向它。
 */
@Repository
public class RunRepositoryJdbc implements RunRepository {

    private static final String SELECT = """
            SELECT id, run_id, project_id, test_set_id, run_status, priority, concurrency,
                   device_scope, attempt_no, total_tests, passed_tests, failed_tests, failure_class,
                   failure_reason, failure_message, artifacts_dir, enqueued_at, started_at, finished_at
              FROM run
            """;

    private static final RowMapper<RunRecord> MAPPER = (rs, rowNum) -> new RunRecord(
            rs.getLong("id"),
            rs.getString("run_id"),
            rs.getLong("project_id"),
            rs.getObject("test_set_id") == null ? null : rs.getLong("test_set_id"),
            RunState.fromDb(rs.getString("run_status")),
            rs.getInt("priority"),
            rs.getInt("concurrency"),
            rs.getInt("attempt_no"),
            valueOrZero(rs.getObject("total_tests")),
            valueOrZero(rs.getObject("passed_tests")),
            valueOrZero(rs.getObject("failed_tests")),
            rs.getString("failure_class"),
            rs.getString("failure_reason"),
            rs.getString("failure_message"),
            rs.getString("artifacts_dir"),
            instant(rs.getTimestamp("enqueued_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")), rs.getString("device_scope"));

    private static final RowMapper<RunRetryOrigin> RETRY_ORIGIN_MAPPER = (rs, rowNum) ->
            new RunRetryOrigin(
                    rs.getString("run_id"),
                    RunState.fromDb(rs.getString("run_status")),
                    rs.getString("failure_class"),
                    rs.getString("failure_reason"),
                    rs.getString("failure_message"),
                    instant(rs.getTimestamp("finished_at")));

    private final JdbcTemplate jdbc;

    public RunRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public RunRecord insert(RunRecord draft, String jobNo, String runRequestId, Long operatorId) {
        return insert(draft, jobNo, runRequestId, operatorId, RunOrigin.manual());
    }

    @Override
    @Transactional
    public RunRecord insert(RunRecord draft, String jobNo, String runRequestId,
                            Long operatorId, RunOrigin origin) {
        RunOrigin effective = origin == null ? RunOrigin.manual() : origin;
        String jobSql = """
                INSERT INTO execution_job (project_id, job_no, job_status, submitted_by,
                                           created_by, updated_by)
                VALUES (?, ?, 'submitted', ?, ?, ?)
                """;
        TenantGuard.requireProjectScope(jobSql, "execution_job");
        KeyHolder jobKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(jobSql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, draft.projectId());
            ps.setString(2, jobNo);
            ps.setObject(3, operatorId, java.sql.Types.BIGINT);
            ps.setObject(4, operatorId, java.sql.Types.BIGINT);
            ps.setObject(5, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, jobKey);

        String runSql = """
                INSERT INTO run (run_id, run_request_id, project_id, test_set_id, trigger_type,
                                 plan_id, triggered_by_plan_at,
                                 priority, enqueued_at, concurrency, device_scope, run_status,
                                 total_tests, passed_tests, failed_tests, attempt_no,
                                 created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', 0, 0, 0, 0, ?, ?)
                """;
        TenantGuard.requireProjectScope(runSql, "run");
        KeyHolder runKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(runSql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, draft.runId());
            ps.setString(2, runRequestId);
            ps.setLong(3, draft.projectId());
            ps.setObject(4, draft.testSetId(), java.sql.Types.BIGINT);
            ps.setString(5, effective.triggerType());
            ps.setObject(6, effective.planId(), java.sql.Types.BIGINT);
            ps.setTimestamp(7, effective.triggeredByPlanAt() == null
                    ? null : Timestamp.from(effective.triggeredByPlanAt()));
            ps.setInt(8, draft.priority());
            ps.setTimestamp(9, Timestamp.from(draft.enqueuedAt()));
            ps.setInt(10, draft.concurrency());
            ps.setString(11, draft.deviceScope());
            ps.setObject(12, operatorId, java.sql.Types.BIGINT);
            ps.setObject(13, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, runKey);
        long id = runKey.getKey() == null ? 0 : runKey.getKey().longValue();
        return new RunRecord(id, draft.runId(), draft.projectId(), draft.testSetId(),
                RunState.QUEUED, draft.priority(), draft.concurrency(), 0,
                0, 0, 0, null, null, null, null, draft.enqueuedAt(), null, null, draft.deviceScope());
    }

    /** 回读 run 归属节点（NODX-03 回传校验用）：空 = run 不存在或未指定节点。 */
    @Override
    public Optional<Long> findNodeId(long projectId, String runId) {
        String sql = """
                SELECT node_id
                  FROM run
                 WHERE project_id = ? AND run_id = ? AND node_id IS NOT NULL
                """;
        TenantGuard.requireProjectScope(sql, "run");
        List<Long> ids = jdbc.query(sql, (rs, rowNum) -> rs.getLong("node_id"),
                projectId, runId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** 指定执行节点（NODX-02）：与 insert 同事务调用，写 run.node_id。 */
    @Override
    public void assignNode(long projectId, String runId, long nodeId) {
        String sql = """
                UPDATE run
                   SET node_id = ?
                 WHERE project_id = ? AND run_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        jdbc.update(sql, nodeId, projectId, runId);
    }

    @Override
    public Optional<RunOrigin> findOrigin(long projectId, String runId) {
        String sql = """
                SELECT trigger_type, plan_id, triggered_by_plan_at
                  FROM run
                 WHERE project_id = ? AND run_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, (rs, rowNum) -> new RunOrigin(
                rs.getString("trigger_type"),
                rs.getObject("plan_id") == null ? null : rs.getLong("plan_id"),
                JdbcSupport.instant(rs.getTimestamp("triggered_by_plan_at"))),
                projectId, runId));
    }

    @Override
    public Optional<RunRecord> findByRunId(long projectId, String runId) {
        String sql = SELECT + " WHERE project_id = ? AND run_id = ?";
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, runId));
    }

    @Override
    public Optional<Long> findJobId(long projectId, String runId) {
        String sql = """
                SELECT id FROM execution_job
                 WHERE project_id = ? AND job_no = ?
                 ORDER BY id DESC LIMIT 1
                """;
        TenantGuard.requireProjectScope(sql, "execution_job");
        List<Long> ids = jdbc.queryForList(sql, Long.class, projectId, runId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    @Override
    public Optional<RunRecord> findByRequestId(long projectId, String requestId) {
        String sql = SELECT + " WHERE project_id = ? AND run_request_id = ?";
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, requestId));
    }

    @Override
    public int updateState(String runId, RunState from, RunState to,
                           java.time.Instant startedAt, java.time.Instant finishedAt,
                           String failureClass, String failureReason, String failureMessage) {
        String sql = """
                UPDATE run
                   SET run_status = ?,
                       started_at = COALESCE(?, started_at),
                       finished_at = ?,
                       failure_class = ?,
                       failure_reason = ?,
                       failure_message = ?,
                       updated_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND project_id > 0 AND run_status = ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.update(sql, to.dbValue(),
                startedAt == null ? null : Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt),
                failureClass, failureReason, failureMessage, runId, from.dbValue());
    }

    @Override
    public int updateCounts(String runId, int total, int passed, int failed, String artifactsDir) {
        String sql = """
                UPDATE run
                   SET total_tests = ?, passed_tests = ?, failed_tests = ?,
                       artifacts_dir = ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND project_id > 0
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.update(sql, total, passed, failed, artifactsDir, runId);
    }

    @Override
    public void updateKernel(String runId, String kernel) {
        String sql = """
                UPDATE run
                   SET kernel = ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND project_id > 0
                """;
        TenantGuard.requireProjectScope(sql, "run");
        jdbc.update(sql, kernel == null || kernel.isBlank() ? "robot" : kernel, runId);
    }

    @Override
    public Optional<String> findKernel(long projectId, String runId) {
        return JdbcSupport.first(jdbc.query(
                "SELECT kernel FROM run WHERE project_id = ? AND run_id = ?",
                (rs, rowNum) -> rs.getString("kernel"), projectId, runId));
    }

    @Override
    public int recordLeaseWait(String runId, long waitMs) {
        String sql = """
                UPDATE run
                   SET lease_wait_ms = ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND project_id > 0
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.update(sql, Math.max(0, waitMs), runId);
    }

    @Override
    public Optional<Long> findLeaseWaitMs(long projectId, String runId) {
        String sql = "SELECT lease_wait_ms FROM run WHERE project_id = ? AND run_id = ?";
        TenantGuard.requireProjectScope(sql, "run");
        // 列可空：还没有回写过时返回 empty，不能把 NULL 当成 0 秒等待。
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                return Optional.<Long>empty();
            }
            long value = rs.getLong("lease_wait_ms");
            return rs.wasNull() ? Optional.<Long>empty() : Optional.of(value);
        }, projectId, runId);
    }

    @Override
    public List<RunRecord> findActive() {
        String sql = SELECT + " WHERE project_id > 0 AND run_status IN ('preparing', 'running')";
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.query(sql, MAPPER);
    }

    @Override
    public PageResult<RunRecord> page(long projectId, RunState state, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (state != null) {
            where.append(" AND run_status = ?");
            args.add(state.dbValue());
        }
        String countSql = "SELECT COUNT(*) FROM run" + where;
        TenantGuard.requireProjectScope(countSql, "run");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        String listSql = SELECT + where + " ORDER BY enqueued_at DESC, id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "run");
        List<RunRecord> rows = jdbc.query(listSql, MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public int markAttempt(String runId, int attemptNo, String retryOf) {
        String sql = """
                UPDATE run
                   SET attempt_no = ?, retry_of_run_id = ?, updated_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND project_id > 0
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.update(sql, attemptNo, retryOf, runId);
    }

    /**
     * 上一次尝试（FR-v0.4-29）：{@code retry_of_run_id} 自连接 {@code run}，
     * 一并取回原运行的状态与失败结论。两表都限定 {@code project_id}，跨项目的历史 ID 不会被拼进来。
     */
    @Override
    public Optional<RunRetryOrigin> findRetryOrigin(long projectId, String runId) {
        String sql = """
                SELECT p.run_id, p.run_status, p.failure_class, p.failure_reason,
                       p.failure_message, p.finished_at
                  FROM run r
                  JOIN run p ON p.project_id = r.project_id AND p.run_id = r.retry_of_run_id
                 WHERE r.project_id = ? AND r.run_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.query(sql, RETRY_ORIGIN_MAPPER, projectId, runId).stream().findFirst();
    }

    /** 项目内 run 按状态计数（W4 指标）。 */
    @Override
    public java.util.Map<String, Integer> countByStatus(long projectId) {
        String sql = """
                SELECT run_status, COUNT(*) AS n FROM run WHERE project_id = ? GROUP BY run_status
                """;
        TenantGuard.requireProjectScope(sql, "run");
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            counts.put(rs.getString("run_status"), rs.getInt("n"));
        }, projectId);
        return counts;
    }

    private static Integer valueOrZero(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
