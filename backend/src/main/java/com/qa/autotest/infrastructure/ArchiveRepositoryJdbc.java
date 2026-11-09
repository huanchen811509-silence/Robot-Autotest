package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ArchiveRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.archive.ArchivePolicy;
import com.qa.autotest.domain.archive.ArchivedRunInfo;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 归档与保留持久化（FR-3.4-01）。冷热搬迁按原主键进行，恢复不改引用关系。 */
@Repository
public class ArchiveRepositoryJdbc implements ArchiveRepository {

    private final JdbcTemplate jdbc;

    public ArchiveRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ArchivePolicy policy(long projectId) {
        String sql = "SELECT retention_days, batch_size, enabled FROM archive_policy "
                + "WHERE project_id = ?";
        TenantGuard.requireProjectScope(sql, "archive_policy");
        List<ArchivePolicy> rows = jdbc.query(sql, (rs, i) -> new ArchivePolicy(
                projectId, rs.getBoolean("enabled"), rs.getInt("retention_days"),
                rs.getInt("batch_size")), projectId);
        return rows.isEmpty() ? ArchivePolicy.defaults(projectId) : rows.get(0);
    }

    @Override
    public void savePolicy(long projectId, ArchivePolicy policy, Long actorId) {
        String sql = "INSERT INTO archive_policy (project_id, enabled, retention_days, batch_size, "
                + "updated_by) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "enabled = VALUES(enabled), retention_days = VALUES(retention_days), "
                + "batch_size = VALUES(batch_size), updated_by = VALUES(updated_by)";
        TenantGuard.requireProjectScope(sql, "archive_policy");
        jdbc.update(sql, projectId, policy.enabled() ? 1 : 0, policy.retentionDays(),
                policy.batchSize(), actorId);
    }

    @Override
    public List<Long> enabledProjects() {
        String sql = "SELECT DISTINCT project_id FROM archive_policy WHERE enabled = 1";
        TenantGuard.requireProjectScope(sql, "archive_policy");
        return jdbc.query(sql, (rs, i) -> rs.getLong("project_id"));
    }

    @Override
    public List<RunCandidate> candidates(long projectId, Instant before, int limit) {
        String sql = "SELECT r.id, r.run_id, r.created_at FROM run r WHERE r.project_id = ? "
                + "AND r.created_at < ? AND r.run_status IN ('finished', 'failed', 'cancelled') "
                + "AND NOT EXISTS (SELECT 1 FROM archived_run a "
                + " WHERE a.project_id = r.project_id AND a.run_id = r.run_id "
                + " AND a.restored_at IS NULL) "
                + "ORDER BY r.id ASC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "archived_run");
        return jdbc.query(sql, (rs, i) -> {
            Timestamp created = rs.getTimestamp("created_at");
            return new RunCandidate(rs.getLong("id"), rs.getString("run_id"),
                    created == null ? null : created.toInstant());
        }, projectId, Timestamp.from(before), Math.min(Math.max(limit, 1), 2000));
    }

    @Override
    public Counts counts(long runDbId, long projectId) {
        String test = "SELECT COUNT(*) AS c FROM test_result t JOIN run r ON r.id = t.run_id "
                + "WHERE t.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(test, "test_result");
        String keyword = "SELECT COUNT(*) AS c FROM keyword_result k JOIN run r ON r.id = k.run_id "
                + "WHERE k.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(keyword, "keyword_result");
        Integer tests = jdbc.queryForObject(test, Integer.class, runDbId, projectId);
        Integer keywords = jdbc.queryForObject(keyword, Integer.class, runDbId, projectId);
        return new Counts(tests == null ? 0 : tests, keywords == null ? 0 : keywords);
    }

    @Override
    public long insertArchive(long projectId, long runDbId, String runId,
                              int testResultCount, int keywordResultCount) {
        String sql = "INSERT INTO archived_run (project_id, run_db_id, run_id, "
                + "test_result_count, keyword_result_count, archived_at, restored_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), NULL) "
                + "ON DUPLICATE KEY UPDATE run_db_id = VALUES(run_db_id), "
                + "test_result_count = VALUES(test_result_count), "
                + "keyword_result_count = VALUES(keyword_result_count), "
                + "archived_at = CURRENT_TIMESTAMP(3), restored_at = NULL, id = LAST_INSERT_ID(id)";
        TenantGuard.requireProjectScope(sql, "archived_run");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, runDbId);
            ps.setString(3, runId);
            ps.setInt(4, testResultCount);
            ps.setInt(5, keywordResultCount);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    @Transactional
    public int moveTestResults(long runDbId, long archiveId, long projectId) {
        String sql = "INSERT INTO archived_test_result (id, archive_id, run_id, case_id, case_key, "
                + "round_no, verdict, duration_ms, log_offset, log_span, failure_msg, failure_kind, "
                + "created_at, created_by, updated_at, updated_by) "
                + "SELECT t.id, ?, t.run_id, t.case_id, t.case_key, t.round_no, t.verdict, "
                + "t.duration_ms, t.log_offset, t.log_span, t.failure_msg, t.failure_kind, "
                + "t.created_at, t.created_by, t.updated_at, t.updated_by "
                + "FROM test_result t JOIN run r ON r.id = t.run_id "
                + "WHERE t.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(sql, "archived_test_result");
        jdbc.update(sql, archiveId, runDbId, projectId);
        String delete = "DELETE t FROM test_result t JOIN run r ON r.id = t.run_id "
                + "WHERE t.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(delete, "test_result");
        return jdbc.update(delete, runDbId, projectId);
    }

    @Override
    @Transactional
    public int moveKeywordResults(long runDbId, long archiveId, long projectId) {
        String sql = "INSERT INTO archived_keyword_result (id, archive_id, run_id, test_result_id, "
                + "parent_id, kw_name, kw_status, depth, arg_count, log_span, log_offset, "
                + "created_at, created_by, updated_at, updated_by) "
                + "SELECT k.id, ?, k.run_id, k.test_result_id, k.parent_id, k.kw_name, "
                + "k.kw_status, k.depth, k.arg_count, k.log_span, k.log_offset, "
                + "k.created_at, k.created_by, k.updated_at, k.updated_by "
                + "FROM keyword_result k JOIN run r ON r.id = k.run_id "
                + "WHERE k.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(sql, "archived_keyword_result");
        jdbc.update(sql, archiveId, runDbId, projectId);
        String delete = "DELETE k FROM keyword_result k JOIN run r ON r.id = k.run_id "
                + "WHERE k.run_id = ? AND r.project_id = ?";
        TenantGuard.requireProjectScope(delete, "keyword_result");
        return jdbc.update(delete, runDbId, projectId);
    }

    @Override
    public Optional<ArchivedRunInfo> findArchive(long projectId, String runId) {
        String sql = "SELECT id, project_id, run_id, run_db_id, test_result_count, "
                + "keyword_result_count, archived_at, restored_at FROM archived_run "
                + "WHERE project_id = ? AND run_id = ?";
        TenantGuard.requireProjectScope(sql, "archived_run");
        List<ArchivedRunInfo> rows = jdbc.query(sql, (rs, i) -> mapRun(rs), projectId, runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ArchivedRunInfo> archives(long projectId, int limit) {
        String sql = "SELECT id, project_id, run_id, run_db_id, test_result_count, "
                + "keyword_result_count, archived_at, restored_at FROM archived_run "
                + "WHERE project_id = ? ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "archived_run");
        return jdbc.query(sql, (rs, i) -> mapRun(rs), projectId,
                Math.min(Math.max(limit, 1), 200));
    }

    @Override
    public List<ColdResult> coldResults(long archiveId, long projectId, int limit) {
        String sql = "SELECT a.id, a.case_id, a.verdict, a.duration_ms, a.failure_msg, "
                + "a.failure_kind, a.created_at FROM archived_test_result a "
                + "JOIN archived_run ar ON ar.id = a.archive_id "
                + "WHERE a.archive_id = ? AND ar.project_id = ? ORDER BY a.id ASC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "archived_test_result");
        return jdbc.query(sql, (rs, i) -> {
            Timestamp created = rs.getTimestamp("created_at");
            long caseId = rs.getLong("case_id");
            Long caseValue = rs.wasNull() ? null : caseId;
            long duration = rs.getLong("duration_ms");
            Long durationValue = rs.wasNull() ? null : duration;
            return new ColdResult(rs.getLong("id"), caseValue, rs.getString("verdict"),
                    durationValue, rs.getString("failure_msg"), rs.getString("failure_kind"),
                    created == null ? null : created.toInstant());
        }, archiveId, projectId, Math.min(Math.max(limit, 1), 2000));
    }

    @Override
    @Transactional
    public int restoreTestResults(long archiveId, long projectId) {
        String sql = "INSERT INTO test_result (id, run_id, case_id, case_key, round_no, verdict, "
                + "duration_ms, log_offset, log_span, failure_msg, failure_kind, "
                + "created_at, created_by, updated_at, updated_by) "
                + "SELECT a.id, a.run_id, a.case_id, a.case_key, a.round_no, a.verdict, "
                + "a.duration_ms, a.log_offset, a.log_span, a.failure_msg, a.failure_kind, "
                + "a.created_at, a.created_by, a.updated_at, a.updated_by "
                + "FROM archived_test_result a JOIN archived_run ar ON ar.id = a.archive_id "
                + "WHERE a.archive_id = ? AND ar.project_id = ? AND ar.restored_at IS NULL";
        TenantGuard.requireProjectScope(sql, "archived_test_result");
        return jdbc.update(sql, archiveId, projectId);
    }

    @Override
    @Transactional
    public int restoreKeywordResults(long archiveId, long projectId) {
        String sql = "INSERT INTO keyword_result (id, run_id, test_result_id, parent_id, kw_name, "
                + "kw_status, depth, arg_count, log_span, log_offset, "
                + "created_at, created_by, updated_at, updated_by) "
                + "SELECT a.id, a.run_id, a.test_result_id, a.parent_id, a.kw_name, "
                + "a.kw_status, a.depth, a.arg_count, a.log_span, a.log_offset, "
                + "a.created_at, a.created_by, a.updated_at, a.updated_by "
                + "FROM archived_keyword_result a JOIN archived_run ar ON ar.id = a.archive_id "
                + "WHERE a.archive_id = ? AND ar.project_id = ? AND ar.restored_at IS NULL";
        TenantGuard.requireProjectScope(sql, "archived_keyword_result");
        return jdbc.update(sql, archiveId, projectId);
    }

    @Override
    public void markRestored(long projectId, String runId) {
        String sql = "UPDATE archived_run SET restored_at = CURRENT_TIMESTAMP(3) "
                + "WHERE project_id = ? AND run_id = ?";
        TenantGuard.requireProjectScope(sql, "archived_run");
        jdbc.update(sql, projectId, runId);
    }

    @Override
    public void deleteArchiveDetails(long archiveId) {
        String test = "DELETE FROM archived_test_result WHERE archive_id = ?";
        TenantGuard.requireProjectScope(test, "archived_test_result");
        jdbc.update(test, archiveId);
        String keyword = "DELETE FROM archived_keyword_result WHERE archive_id = ?";
        TenantGuard.requireProjectScope(keyword, "archived_keyword_result");
        jdbc.update(keyword, archiveId);
    }

    private static ArchivedRunInfo mapRun(ResultSet rs) throws java.sql.SQLException {
        Timestamp archived = rs.getTimestamp("archived_at");
        Timestamp restored = rs.getTimestamp("restored_at");
        return new ArchivedRunInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("run_id"), rs.getLong("run_db_id"),
                rs.getInt("test_result_count"), rs.getInt("keyword_result_count"),
                archived == null ? null : archived.toInstant(),
                restored == null ? null : restored.toInstant());
    }
}
