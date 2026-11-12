package com.qa.autotest.infrastructure;

import com.qa.autotest.application.execution.ManualExecutionService;
import com.qa.autotest.application.repository.ManualExecutionRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.execution.ManualExecution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 手工执行仓储实现（`manual_execution` + `manual_step_result`）。 */
@Repository
public class ManualExecutionRepositoryJdbc implements ManualExecutionRepository {

    private static final String HEAD_COLUMNS = "id, project_id, run_id, case_id, executor_id, "
            + "executor_name, started_at, finished_at, env_note, verdict, note, created_at, "
            + "updated_at";

    private static final String STEP_COLUMNS =
            "execution_id, step_no, verdict, note, attachments, confirmed_at";

    private final JdbcTemplate jdbc;

    public ManualExecutionRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public long insert(ManualExecution execution, Long operatorId) {
        String sql = """
                INSERT INTO manual_execution
                  (project_id, run_id, case_id, executor_id, executor_name, started_at,
                   finished_at, env_note, verdict, note, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        TenantGuard.requireProjectScope(sql, "manual_execution");
        jdbc.update(sql, execution.projectId(), execution.runId(), execution.caseId(),
                execution.executorId(), execution.executorName(),
                timestamp(execution.startedAt()), timestamp(execution.finishedAt()),
                execution.envNote(), execution.verdict(), execution.note(),
                operatorId, operatorId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) {
            throw new IllegalStateException("manual_execution 插入后拿不到自增 id");
        }
        if (execution.steps() != null) {
            for (ManualExecution.StepResult step : execution.steps()) {
                insertStep(id, step, operatorId);
            }
        }
        return id;
    }

    private void insertStep(long executionId, ManualExecution.StepResult step, Long operatorId) {
        String sql = """
                INSERT INTO manual_step_result
                  (execution_id, step_no, verdict, note, attachments, confirmed_at,
                   created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(sql, executionId, step.stepNo(), step.verdict(), step.note(),
                ManualExecutionService.attachmentsJson(step.attachments()),
                timestamp(step.confirmedAt()), operatorId, operatorId);
    }

    @Override
    public void update(ManualExecution execution, Long operatorId) {
        String sql = """
                UPDATE manual_execution
                   SET finished_at = ?, verdict = ?, note = ?, updated_by = ?
                 WHERE id = ? AND project_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "manual_execution");
        jdbc.update(sql, timestamp(execution.finishedAt()), execution.verdict(),
                execution.note(), operatorId, execution.id(), execution.projectId());
    }

    @Override
    public void updateStep(long executionId, ManualExecution.StepResult step, Long operatorId) {
        String sql = """
                UPDATE manual_step_result
                   SET verdict = ?, note = ?, attachments = ?, confirmed_at = ?, updated_by = ?
                 WHERE execution_id = ? AND step_no = ?
                """;
        jdbc.update(sql, step.verdict(), step.note(),
                ManualExecutionService.attachmentsJson(step.attachments()),
                timestamp(step.confirmedAt()), operatorId, executionId, step.stepNo());
    }

    @Override
    public Optional<ManualExecution> find(long projectId, long executionId) {
        String sql = "SELECT " + HEAD_COLUMNS + " FROM manual_execution "
                + "WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "manual_execution");
        List<ManualExecution> rows = jdbc.query(sql, (rs, rowNum) -> map(rs, true),
                projectId, executionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<ManualExecution> listByRun(long projectId, long runId) {
        String sql = "SELECT " + HEAD_COLUMNS + " FROM manual_execution "
                + "WHERE project_id = ? AND run_id = ? ORDER BY id";
        TenantGuard.requireProjectScope(sql, "manual_execution");
        return jdbc.query(sql, (rs, rowNum) -> map(rs, true), projectId, runId);
    }

    @Override
    public List<ManualExecution> listByCase(long projectId, long caseId, Instant since, int limit) {
        String sql = "SELECT " + HEAD_COLUMNS + " FROM manual_execution "
                + "WHERE project_id = ? AND case_id = ? AND started_at >= ? "
                + "ORDER BY started_at DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "manual_execution");
        return jdbc.query(sql, (rs, rowNum) -> map(rs, false), projectId, caseId,
                Timestamp.from(since), Math.max(1, limit));
    }

    @Override
    public long countByVerdict(long projectId, String verdict, Instant since) {
        String sql = "SELECT COUNT(*) FROM manual_execution "
                + "WHERE project_id = ? AND verdict = ? AND started_at >= ?";
        TenantGuard.requireProjectScope(sql, "manual_execution");
        Long count = jdbc.queryForObject(sql, Long.class, projectId, verdict,
                Timestamp.from(since));
        return count == null ? 0L : count;
    }

    /** 映射；{@code withSteps} 为 false 时步骤列表留空（列表页不需要逐步数据）。 */
    private ManualExecution map(ResultSet rs, boolean withSteps) throws SQLException {
        long id = rs.getLong("id");
        return new ManualExecution(id,
                rs.getLong("project_id"),
                rs.getLong("run_id"),
                rs.getLong("case_id"),
                nullableLong(rs.getObject("executor_id")),
                rs.getString("executor_name"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("env_note"),
                rs.getString("verdict"),
                rs.getString("note"),
                withSteps ? steps(id) : List.of(),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private List<ManualExecution.StepResult> steps(long executionId) {
        String sql = "SELECT " + STEP_COLUMNS + " FROM manual_step_result "
                + "WHERE execution_id = ? ORDER BY step_no";
        List<ManualExecution.StepResult> out = new ArrayList<>();
        Map<Integer, ManualExecution.StepResult> ordered = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            ordered.put(rs.getInt("step_no"), new ManualExecution.StepResult(
                    rs.getInt("step_no"),
                    rs.getString("verdict"),
                    rs.getString("note"),
                    ManualExecutionService.parseAttachments(rs.getString("attachments")),
                    instant(rs.getTimestamp("confirmed_at"))));
        }, executionId);
        out.addAll(ordered.values());
        return List.copyOf(out);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
