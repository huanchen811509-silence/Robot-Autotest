package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.AiSessionRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** AI 三表落库的 JDBC 实现（FR-AI-03 / FR-AI-04）。表定义见 V8_4，新增列见 V11_9。 */
@Repository
public class AiSessionRepositoryJdbc implements AiSessionRepository {

    private static final int SUMMARY_MAX = 400;

    private final JdbcTemplate jdbc;

    public AiSessionRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long openSession(long projectId, long userId, String sessionUuid) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ai_session (project_id, user_id, session_uuid, status) "
                            + "VALUES (?, ?, ?, 'OPEN')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setLong(2, userId);
            ps.setString(3, sessionUuid);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("ai_session 未返回自增主键");
        }
        return key.longValue();
    }

    @Override
    public void closeSession(long sessionId) {
        jdbc.update("UPDATE ai_session SET status = 'CLOSED', "
                + "closed_at = CURRENT_TIMESTAMP(3) WHERE id = ?", sessionId);
    }

    @Override
    public void recordAction(long sessionId, String action, String level, boolean confirmed,
                             String result, String summary, String stdout, String stderr,
                             int exitCode, long durationMs, String cli, boolean timedOut) {
        jdbc.update("INSERT INTO ai_action (session_id, action, level, confirmed, result, "
                        + "summary, stdout, stderr, exit_code, duration_ms, cli_name, timed_out) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, action, level, confirmed ? 1 : 0, result,
                truncate(summary), stdout, stderr, exitCode, durationMs, cli,
                timedOut ? 1 : 0);
    }

    @Override
    public void recordProbe(String cliName, boolean available, boolean nonInteractive) {
        jdbc.update("INSERT INTO ai_cli_probe (cli_name, available, non_interactive) "
                + "VALUES (?, ?, ?)", cliName, available ? 1 : 0, nonInteractive ? 1 : 0);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= SUMMARY_MAX ? value : value.substring(0, SUMMARY_MAX);
    }
}
