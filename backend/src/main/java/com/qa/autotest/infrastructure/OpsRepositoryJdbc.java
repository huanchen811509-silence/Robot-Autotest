package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.ops.AlertChannel;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.AlertRule;
import com.qa.autotest.domain.ops.ReleaseRecord;
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

/** 告警 / SLO / 发布记录持久化（FR-3.3-09..11）。 */
@Repository
public class OpsRepositoryJdbc implements OpsRepository {

    private final JdbcTemplate jdbc;

    public OpsRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AlertChannel> channels(long projectId) {
        String sql = "SELECT id, project_id, kind, target, enabled, "
                + "CASE WHEN secret IS NULL OR secret = '' THEN 0 ELSE 1 END AS has_secret "
                + "FROM alert_channel WHERE project_id = ? ORDER BY id";
        TenantGuard.requireProjectScope(sql, "alert_channel");
        return jdbc.query(sql, (rs, i) -> new AlertChannel(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("kind"),
                rs.getString("target"), rs.getBoolean("enabled"),
                rs.getInt("has_secret") == 1), projectId);
    }

    @Override
    public AlertChannel saveChannel(long projectId, String kind, String target, String secret,
                                    boolean enabled, Long actorId) {
        String sql = "INSERT INTO alert_channel (project_id, kind, target, secret, enabled, "
                + "created_by) VALUES (?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "alert_channel");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, kind);
            ps.setString(3, target);
            ps.setString(4, secret);
            ps.setInt(5, enabled ? 1 : 0);
            ps.setObject(6, actorId);
            return ps;
        }, holder);
        Number key = holder.getKey();
        long id = key == null ? 0L : key.longValue();
        return new AlertChannel(id, projectId, kind, target, enabled, secret != null
                && !secret.isEmpty());
    }

    @Override
    public boolean deleteChannel(long projectId, long id) {
        String sql = "DELETE FROM alert_channel WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "alert_channel");
        return jdbc.update(sql, projectId, id) > 0;
    }

    @Override
    public Optional<String> channelSecret(long projectId, long id) {
        String sql = "SELECT secret FROM alert_channel WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "alert_channel");
        List<String> rows = jdbc.queryForList(sql, String.class, projectId, id);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public List<AlertRule> rules(long projectId) {
        String sql = "SELECT id, project_id, event_type, threshold, window_minutes, "
                + "cooldown_minutes, severity, enabled FROM alert_rule "
                + "WHERE project_id = ? ORDER BY id";
        TenantGuard.requireProjectScope(sql, "alert_rule");
        return jdbc.query(sql, (rs, i) -> new AlertRule(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("event_type"),
                rs.getDouble("threshold"), rs.getInt("window_minutes"),
                rs.getInt("cooldown_minutes"), rs.getString("severity"),
                rs.getBoolean("enabled")), projectId);
    }

    @Override
    public AlertRule saveRule(long projectId, String eventType, double threshold,
                              int windowMinutes, int cooldownMinutes, String severity,
                              boolean enabled, Long actorId) {
        String sql = "INSERT INTO alert_rule (project_id, event_type, threshold, window_minutes, "
                + "cooldown_minutes, severity, enabled, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "alert_rule");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, eventType);
            ps.setDouble(3, threshold);
            ps.setInt(4, windowMinutes);
            ps.setInt(5, cooldownMinutes);
            ps.setString(6, severity);
            ps.setInt(7, enabled ? 1 : 0);
            ps.setObject(8, actorId);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return new AlertRule(key == null ? 0L : key.longValue(), projectId, eventType,
                threshold, windowMinutes, cooldownMinutes, severity, enabled);
    }

    @Override
    public boolean deleteRule(long projectId, long id) {
        String sql = "DELETE FROM alert_rule WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "alert_rule");
        return jdbc.update(sql, projectId, id) > 0;
    }

    @Override
    public Optional<AlertEvent> findOpenEvent(long projectId, String dedupKey) {
        String sql = "SELECT id, project_id, rule_id, event_type, dedup_key, severity, status, "
                + "summary, detected_at, fired_at, resolved_at, delivery_status, attempts, "
                + "last_error FROM alert_event WHERE project_id = ? AND dedup_key = ? "
                + "AND status = 'FIRING' ORDER BY id DESC LIMIT 1";
        TenantGuard.requireProjectScope(sql, "alert_event");
        List<AlertEvent> rows = jdbc.query(sql, (rs, i) -> mapEvent(rs), projectId, dedupKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<AlertEvent> latestEvent(long projectId, String dedupKey) {
        String sql = "SELECT id, project_id, rule_id, event_type, dedup_key, severity, status, "
                + "summary, detected_at, fired_at, resolved_at, delivery_status, attempts, "
                + "last_error FROM alert_event WHERE project_id = ? AND dedup_key = ? "
                + "ORDER BY id DESC LIMIT 1";
        TenantGuard.requireProjectScope(sql, "alert_event");
        List<AlertEvent> rows = jdbc.query(sql, (rs, i) -> mapEvent(rs), projectId, dedupKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Long> projectsWithRules() {
        String sql = "SELECT DISTINCT project_id FROM alert_rule WHERE enabled = 1";
        return jdbc.queryForList(sql, Long.class);
    }

    @Override
    public long insertEvent(long projectId, Long ruleId, String eventType, String dedupKey,
                            String severity, String summary, Instant detectedAt,
                            Instant firedAt) {
        String sql = "INSERT INTO alert_event (project_id, rule_id, event_type, dedup_key, "
                + "severity, status, summary, detected_at, fired_at) "
                + "VALUES (?, ?, ?, ?, ?, 'FIRING', ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "alert_event");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setObject(2, ruleId);
            ps.setString(3, eventType);
            ps.setString(4, dedupKey);
            ps.setString(5, severity);
            ps.setString(6, summary);
            ps.setTimestamp(7, Timestamp.from(detectedAt));
            ps.setTimestamp(8, Timestamp.from(firedAt));
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    public boolean markDelivery(long id, String status, int attempts, String lastError) {
        String sql = "UPDATE alert_event SET delivery_status = ?, attempts = ?, last_error = ? "
                + "WHERE id = ?";
        TenantGuard.requireProjectScope(sql, "alert_event");
        return jdbc.update(sql, status, attempts, lastError, id) > 0;
    }

    @Override
    public boolean resolveEvent(long id) {
        String sql = "UPDATE alert_event SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP(3)"
                + " WHERE id = ? AND status = 'FIRING'";
        TenantGuard.requireProjectScope(sql, "alert_event");
        return jdbc.update(sql, id) > 0;
    }

    @Override
    public List<AlertEvent> events(long projectId, int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        String sql = "SELECT id, project_id, rule_id, event_type, dedup_key, severity, status, "
                + "summary, detected_at, fired_at, resolved_at, delivery_status, attempts, "
                + "last_error FROM alert_event WHERE project_id = ? ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "alert_event");
        return jdbc.query(sql, (rs, i) -> mapEvent(rs), projectId, cap);
    }

    @Override
    public void upsertSloDay(long projectId, LocalDate day, double availabilityPct, int p95Ms,
                             int samples, int errorBudgetMinutes) {
        String sql = "INSERT INTO slo_daily (project_id, day, availability_pct, p95_ms, "
                + "sample_count, error_budget_minutes) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE availability_pct = VALUES(availability_pct), "
                + "p95_ms = VALUES(p95_ms), sample_count = VALUES(sample_count), "
                + "error_budget_minutes = VALUES(error_budget_minutes)";
        TenantGuard.requireProjectScope(sql, "slo_daily");
        jdbc.update(sql, projectId, day, availabilityPct, p95Ms, samples, errorBudgetMinutes);
    }

    @Override
    public List<SloDay> sloDays(long projectId, LocalDate fromDay) {
        String sql = "SELECT day, availability_pct, p95_ms, sample_count, error_budget_minutes "
                + "FROM slo_daily WHERE project_id = ? AND day >= ? ORDER BY day";
        TenantGuard.requireProjectScope(sql, "slo_daily");
        return jdbc.query(sql, (rs, i) -> new SloDay(
                rs.getDate("day").toLocalDate(), rs.getDouble("availability_pct"),
                rs.getInt("p95_ms"), rs.getInt("sample_count"),
                rs.getInt("error_budget_minutes")), projectId, fromDay);
    }

    @Override
    public long[] runOutcome(long projectId, Instant since) {
        String sql = "SELECT COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN run_status IN ('failed', 'cancelled') THEN 1 ELSE 0 END), 0)"
                + " AS failed FROM run WHERE project_id = ? AND created_at >= ?";
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.queryForObject(sql, (rs, i) -> new long[]{
                rs.getLong("total"), rs.getLong("failed")}, projectId, Timestamp.from(since));
    }

    @Override
    public long offlineNodeCount(long projectId) {
        String sql = "SELECT COUNT(*) FROM execution_node WHERE UPPER(status) = 'OFFLINE'";
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long insertRelease(String version, String status, String note) {
        String sql = "INSERT INTO release_record (version, status, note) VALUES (?, ?, ?)";
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version);
            ps.setString(2, status);
            ps.setString(3, note);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    public List<ReleaseRecord> releases(int limit) {
        int cap = Math.max(1, Math.min(limit, 100));
        String sql = "SELECT id, version, status, deployed_at, verified_at, rolled_back_at, note "
                + "FROM release_record ORDER BY id DESC LIMIT ?";
        return jdbc.query(sql, (rs, i) -> new ReleaseRecord(
                rs.getLong("id"), rs.getString("version"), rs.getString("status"),
                instant(rs.getTimestamp("deployed_at")), instant(rs.getTimestamp("verified_at")),
                instant(rs.getTimestamp("rolled_back_at")), rs.getString("note")), cap);
    }

    @Override
    public Optional<ReleaseRecord> latestRelease() {
        List<ReleaseRecord> rows = releases(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static AlertEvent mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AlertEvent(rs.getLong("id"), rs.getLong("project_id"),
                (Long) rs.getObject("rule_id"), rs.getString("event_type"),
                rs.getString("dedup_key"), rs.getString("severity"), rs.getString("status"),
                rs.getString("summary"), instant(rs.getTimestamp("detected_at")),
                instant(rs.getTimestamp("fired_at")), instant(rs.getTimestamp("resolved_at")),
                rs.getString("delivery_status"), rs.getInt("attempts"),
                rs.getString("last_error"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
