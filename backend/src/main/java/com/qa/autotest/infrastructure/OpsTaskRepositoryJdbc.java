package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.OpsTaskRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.ops.OpsTask;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 运维任务仓储 JDBC 实现（v5.16.0）。 */
@Repository
public class OpsTaskRepositoryJdbc implements OpsTaskRepository {

    private static final String COLUMNS = "id, project_id, task_type, operator_id, params_json, "
            + "phase, progress, status, log_text, result_json, lease_until, started_at, "
            + "finished_at, created_at";

    private static final RowMapper<OpsTask> ROW = (rs, n) -> new OpsTask(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("task_type"),
            nullableLong(rs, "operator_id"), rs.getString("params_json"), rs.getString("phase"),
            rs.getInt("progress"), rs.getString("status"), rs.getString("log_text"),
            rs.getString("result_json"), instant(rs, "lease_until"),
            instant(rs, "started_at"), instant(rs, "finished_at"), instant(rs, "created_at"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private final JdbcTemplate jdbc;

    public OpsTaskRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(long projectId, String taskType, String paramsJson, Long operatorId) {
        String sql = "INSERT INTO ops_task (project_id, task_type, operator_id, params_json, "
                + "phase, progress, status, lease_until, started_at, created_by) "
                + "VALUES (?, ?, ?, ?, 'QUEUED', 0, 'PENDING', "
                + "DATE_ADD(NOW(3), INTERVAL 5 MINUTE), NOW(3), ?)";
        TenantGuard.requireProjectScope(sql, "ops_task");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, taskType);
            if (operatorId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, operatorId);
            }
            ps.setString(4, paramsJson);
            if (operatorId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, operatorId);
            }
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    @Override
    public Optional<OpsTask> find(long projectId, long id) {
        String sql = "SELECT " + COLUMNS + " FROM ops_task WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "ops_task");
        return jdbc.query(sql, ROW, projectId, id).stream().findFirst();
    }

    @Override
    public List<OpsTask> list(long projectId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM ops_task WHERE project_id = ? "
                + "ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "ops_task");
        return jdbc.query(sql, ROW, projectId, limit <= 0 ? 20 : Math.min(limit, 200));
    }

    @Override
    public long count(long projectId) {
        String sql = "SELECT COUNT(*) FROM ops_task WHERE project_id = ?";
        TenantGuard.requireProjectScope(sql, "ops_task");
        Long value = jdbc.queryForObject(sql, Long.class, projectId);
        return value == null ? 0 : value;
    }

    @Override
    public void advance(long projectId, long id, String phase, int progress, String logLine,
                        String status) {
        String sql = "UPDATE ops_task SET phase = ?, progress = ?, status = ?, "
                + "log_text = CONCAT(COALESCE(log_text, ''), ?) "
                + "WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "ops_task");
        jdbc.update(sql, phase, progress, status, logLine + "\n", projectId, id);
    }

    @Override
    public void finish(long projectId, long id, String status, String resultJson) {
        String sql = "UPDATE ops_task SET status = ?, phase = 'DONE', progress = 100, "
                + "result_json = ?, finished_at = NOW(3) WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "ops_task");
        jdbc.update(sql, status, resultJson, projectId, id);
    }

    @Override
    public int sweep(long projectId) {
        String sql = "UPDATE ops_task SET status = 'ABORTED', phase = 'DONE', "
                + "finished_at = NOW(3), log_text = CONCAT(COALESCE(log_text, ''), "
                + "' | lease expired; swept to ABORTED') "
                + "WHERE project_id = ? AND status = 'RUNNING' AND lease_until < NOW(3)";
        TenantGuard.requireProjectScope(sql, "ops_task");
        return jdbc.update(sql, projectId);
    }
}
