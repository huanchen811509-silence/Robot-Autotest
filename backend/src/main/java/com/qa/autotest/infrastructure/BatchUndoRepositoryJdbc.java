package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.BatchUndoRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** 批量撤销凭据 JDBC 实现。 */
@Repository
public class BatchUndoRepositoryJdbc implements BatchUndoRepository {

    private static final String COLUMNS = "id, project_id, token, action, target_type, payload, "
            + "item_count, expires_at, consumed_at, created_at";

    private final JdbcTemplate jdbc;

    public BatchUndoRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(long projectId, String token, String action, String targetType,
                       String payload, int itemCount, Instant expiresAt, Long operatorId) {
        String sql = "INSERT INTO batch_undo (project_id, token, action, target_type, payload, "
                + "item_count, expires_at, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "batch_undo");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, token);
            ps.setString(3, action);
            ps.setString(4, targetType);
            ps.setString(5, payload);
            ps.setInt(6, itemCount);
            ps.setTimestamp(7, Timestamp.from(expiresAt));
            if (operatorId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, operatorId);
                ps.setLong(9, operatorId);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    @Override
    public Optional<UndoRecord> find(long projectId, String token) {
        String sql = "SELECT " + COLUMNS + " FROM batch_undo WHERE project_id = ? AND token = ?";
        TenantGuard.requireProjectScope(sql, "batch_undo");
        return jdbc.query(sql, (rs, rowNum) -> new UndoRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("token"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("payload"),
                rs.getInt("item_count"),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("consumed_at")),
                instant(rs.getTimestamp("created_at"))), projectId, token).stream().findFirst();
    }

    @Override
    public boolean consume(long projectId, long id, Instant now) {
        String sql = "UPDATE batch_undo SET consumed_at = ? "
                + "WHERE project_id = ? AND id = ? AND consumed_at IS NULL AND expires_at >= ?";
        TenantGuard.requireProjectScope(sql, "batch_undo");
        return jdbc.update(sql, Timestamp.from(now), projectId, id, Timestamp.from(now)) > 0;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
