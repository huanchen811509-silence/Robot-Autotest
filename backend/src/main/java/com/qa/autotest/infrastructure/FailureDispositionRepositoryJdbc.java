package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.FailureDispositionRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.execution.FailureDisposition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 失败处置仓储实现（`case_failure_disposition`）。 */
@Repository
public class FailureDispositionRepositoryJdbc implements FailureDispositionRepository {

    private static final String COLUMNS = "id, project_id, case_id, case_code, kernel, "
            + "fingerprint, category, location, status, assignee_id, decision, note, occurrences, "
            + "reopen_count, reopen_reason, last_run_id, decided_at, closed_at, created_at, "
            + "updated_at";

    private final JdbcTemplate jdbc;

    public FailureDispositionRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FailureDisposition> find(long projectId, String fingerprint, long caseId) {
        String sql = "SELECT " + COLUMNS + " FROM case_failure_disposition "
                + "WHERE project_id = ? AND fingerprint = ? AND case_id = ?";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, fingerprint, caseId)
                .stream().findFirst();
    }

    @Override
    public Optional<FailureDisposition> findById(long projectId, long id) {
        String sql = "SELECT " + COLUMNS + " FROM case_failure_disposition "
                + "WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, id).stream().findFirst();
    }

    @Override
    public long insert(FailureDisposition row, Long operatorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO case_failure_disposition
                      (project_id, case_id, case_code, kernel, fingerprint, category, location,
                       status, assignee_id, decision, note, occurrences, reopen_count,
                       reopen_reason, last_run_id, decided_at, closed_at, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.projectId());
            ps.setLong(2, row.caseId());
            ps.setString(3, row.caseCode());
            ps.setString(4, row.kernel());
            ps.setString(5, row.fingerprint());
            ps.setString(6, row.category());
            ps.setString(7, row.location());
            ps.setString(8, row.status());
            ps.setObject(9, row.assigneeId(), java.sql.Types.BIGINT);
            ps.setString(10, row.decision());
            ps.setString(11, row.note());
            ps.setInt(12, row.occurrences());
            ps.setInt(13, row.reopenCount());
            ps.setString(14, row.reopenReason());
            ps.setString(15, row.lastRunId());
            ps.setTimestamp(16, timestamp(row.decidedAt()));
            ps.setTimestamp(17, timestamp(row.closedAt()));
            ps.setObject(18, operatorId, java.sql.Types.BIGINT);
            ps.setObject(19, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keys);
        Number id = keys.getKey();
        return id == null ? 0L : id.longValue();
    }

    @Override
    public void update(FailureDisposition row, Long operatorId) {
        jdbc.update("""
                UPDATE case_failure_disposition
                   SET case_code = ?, status = ?, assignee_id = ?, decision = ?, note = ?,
                       occurrences = ?, reopen_count = ?, reopen_reason = ?, last_run_id = ?,
                       decided_at = ?, closed_at = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """, row.caseCode(), row.status(), row.assigneeId(), row.decision(), row.note(),
                row.occurrences(), row.reopenCount(), row.reopenReason(), row.lastRunId(),
                timestamp(row.decidedAt()), timestamp(row.closedAt()), operatorId,
                row.projectId(), row.id());
    }

    @Override
    public List<FailureDisposition> list(long projectId, String status) {
        String sql = "SELECT " + COLUMNS + " FROM case_failure_disposition WHERE project_id = ?"
                + (status == null || status.isBlank() ? "" : " AND status = ?")
                + " ORDER BY occurrences DESC, id DESC";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        return status == null || status.isBlank()
                ? jdbc.query(sql, (rs, rowNum) -> map(rs), projectId)
                : jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, status);
    }

    @Override
    public List<FailureDisposition> listByCase(long projectId, long caseId) {
        String sql = "SELECT " + COLUMNS + " FROM case_failure_disposition "
                + "WHERE project_id = ? AND case_id = ? ORDER BY id DESC";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, caseId);
    }

    @Override
    public List<FailureDisposition> listExempted(long projectId) {
        String sql = "SELECT " + COLUMNS + " FROM case_failure_disposition "
                + "WHERE project_id = ? AND decision = 'known_issue' "
                + "AND status IN ('decided', 'closed') ORDER BY id DESC";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId);
    }

    @Override
    public long countByStatus(long projectId, String status) {
        String sql = "SELECT COUNT(*) FROM case_failure_disposition "
                + "WHERE project_id = ? AND status = ?";
        TenantGuard.requireProjectScope(sql, "case_failure_disposition");
        Long count = jdbc.queryForObject(sql, Long.class, projectId, status);
        return count == null ? 0L : count;
    }

    private static FailureDisposition map(ResultSet rs) throws SQLException {
        return new FailureDisposition(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("case_id"),
                rs.getString("case_code"),
                rs.getString("kernel"),
                rs.getString("fingerprint"),
                rs.getString("category"),
                rs.getString("location"),
                rs.getString("status"),
                nullableLong(rs.getObject("assignee_id")),
                rs.getString("decision"),
                rs.getString("note"),
                rs.getInt("occurrences"),
                rs.getInt("reopen_count"),
                rs.getString("reopen_reason"),
                rs.getString("last_run_id"),
                instant(rs.getTimestamp("decided_at")),
                instant(rs.getTimestamp("closed_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
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
