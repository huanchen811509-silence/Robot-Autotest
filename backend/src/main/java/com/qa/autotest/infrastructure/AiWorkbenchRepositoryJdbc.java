package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.AiWorkbenchRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** AI 工作台持久化（FR-3.3-07）。表定义见 V11_21。 */
@Repository
public class AiWorkbenchRepositoryJdbc implements AiWorkbenchRepository {

    private static final int PREVIEW_MAX = 160;

    private final JdbcTemplate jdbc;

    public AiWorkbenchRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Config config(long projectId) {
        String sql = "SELECT enabled, daily_quota, default_agent, default_model, "
                + "max_context_chars FROM ai_project_config WHERE project_id = ?";
        TenantGuard.requireProjectScope(sql, "ai_project_config");
        List<Config> rows = jdbc.query(sql, (rs, i) -> new Config(
                rs.getBoolean("enabled"), rs.getInt("daily_quota"),
                rs.getString("default_agent"), rs.getString("default_model"),
                rs.getInt("max_context_chars")), projectId);
        return rows.isEmpty() ? Config.defaults() : rows.get(0);
    }

    @Override
    public Config saveConfig(long projectId, Config config, Long actorId) {
        String sql = "INSERT INTO ai_project_config (project_id, enabled, daily_quota, "
                + "default_agent, default_model, max_context_chars, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), "
                + "daily_quota = VALUES(daily_quota), default_agent = VALUES(default_agent), "
                + "default_model = VALUES(default_model), "
                + "max_context_chars = VALUES(max_context_chars), updated_by = VALUES(updated_by)";
        TenantGuard.requireProjectScope(sql, "ai_project_config");
        jdbc.update(sql, projectId, config.enabled() ? 1 : 0, config.dailyQuota(),
                config.defaultAgent(), config.defaultModel(), config.maxContextChars(), actorId);
        return config(projectId);
    }

    @Override
    public long countActionsSince(long projectId, Instant since) {
        String sql = "SELECT COUNT(*) FROM ai_action a JOIN ai_session s ON s.id = a.session_id "
                + "WHERE s.project_id = ? AND a.created_at >= ?";
        TenantGuard.requireProjectScope(sql, "ai_session");
        Long count = jdbc.queryForObject(sql, Long.class, projectId,
                Timestamp.from(since));
        return count == null ? 0L : count;
    }

    @Override
    public long appendMessage(long projectId, long sessionId, String role, String content,
                              String citationsJson, String mode, String traceId) {
        String sql = "INSERT INTO ai_message (project_id, session_id, role, content, "
                + "citations_json, mode, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "ai_message");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, citationsJson);
            ps.setString(6, mode);
            ps.setString(7, traceId);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    public List<SessionRow> sessions(long projectId, long userId, int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        String sql = "SELECT s.id, s.status, s.created_at, "
                + "(SELECT COUNT(*) FROM ai_message m WHERE m.project_id = s.project_id "
                + " AND m.session_id = s.id) AS message_count, "
                + "(SELECT m.content FROM ai_message m WHERE m.project_id = s.project_id "
                + " AND m.session_id = s.id ORDER BY m.id ASC LIMIT 1) AS title, "
                + "(SELECT m.content FROM ai_message m WHERE m.project_id = s.project_id "
                + " AND m.session_id = s.id ORDER BY m.id DESC LIMIT 1) AS last_preview "
                + "FROM ai_session s WHERE s.project_id = ? AND s.user_id = ? "
                + "ORDER BY s.id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "ai_message");
        return jdbc.query(sql, (rs, i) -> new SessionRow(
                rs.getLong("id"), rs.getString("status"),
                preview(rs.getString("title")), rs.getInt("message_count"),
                preview(rs.getString("last_preview")),
                instant(rs.getTimestamp("created_at"))), projectId, userId, cap);
    }

    @Override
    public List<MessageRow> messages(long projectId, long sessionId) {
        String sql = "SELECT id, role, content, citations_json, mode, created_at FROM ai_message "
                + "WHERE project_id = ? AND session_id = ? ORDER BY id ASC";
        TenantGuard.requireProjectScope(sql, "ai_message");
        return jdbc.query(sql, (rs, i) -> new MessageRow(
                rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                rs.getString("citations_json"), rs.getString("mode"),
                instant(rs.getTimestamp("created_at"))), projectId, sessionId);
    }

    @Override
    public boolean sessionBelongs(long projectId, long sessionId) {
        String sql = "SELECT COUNT(*) FROM ai_session WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "ai_session");
        Long count = jdbc.queryForObject(sql, Long.class, projectId, sessionId);
        return count != null && count > 0;
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').strip();
        return flat.length() <= PREVIEW_MAX ? flat : flat.substring(0, PREVIEW_MAX);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
