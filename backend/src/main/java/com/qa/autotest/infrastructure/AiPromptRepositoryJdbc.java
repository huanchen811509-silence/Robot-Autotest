package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.AiPromptRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.ai.PromptTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 提示词库持久化（FR-3.3-03）。 */
@Repository
public class AiPromptRepositoryJdbc implements AiPromptRepository {

    private static final String COLUMNS = "id, project_id, template_key, name, body, version, "
            + "builtin, enabled, updated_at";

    private final JdbcTemplate jdbc;

    public AiPromptRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PromptTemplate> list(long projectId) {
        String sql = "SELECT " + COLUMNS + " FROM ai_prompt_template "
                + "WHERE project_id IN (0, ?) AND enabled = 1 ORDER BY template_key, project_id";
        TenantGuard.requireProjectScope(sql, "ai_prompt_template");
        return jdbc.query(sql, (rs, i) -> new PromptTemplate(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("template_key"),
                rs.getString("name"), rs.getString("body"), rs.getInt("version"),
                rs.getBoolean("builtin"), rs.getBoolean("enabled"),
                String.valueOf(rs.getTimestamp("updated_at"))), projectId);
    }

    @Override
    public Optional<PromptTemplate> find(long projectId, String key) {
        String sql = "SELECT " + COLUMNS + " FROM ai_prompt_template "
                + "WHERE project_id IN (0, ?) AND template_key = ? "
                + "ORDER BY project_id DESC LIMIT 1";
        TenantGuard.requireProjectScope(sql, "ai_prompt_template");
        List<PromptTemplate> rows = jdbc.query(sql, (rs, i) -> new PromptTemplate(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("template_key"),
                rs.getString("name"), rs.getString("body"), rs.getInt("version"),
                rs.getBoolean("builtin"), rs.getBoolean("enabled"),
                String.valueOf(rs.getTimestamp("updated_at"))), projectId, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public PromptTemplate save(long projectId, String key, String name, String body,
                               Long actorId) {
        String sql = "INSERT INTO ai_prompt_template (project_id, template_key, name, body, "
                + "version, builtin, enabled, created_by) VALUES (?, ?, ?, ?, 1, 0, 1, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), body = VALUES(body), "
                + "version = version + 1, enabled = 1, created_by = VALUES(created_by)";
        TenantGuard.requireProjectScope(sql, "ai_prompt_template");
        jdbc.update(sql, projectId, key, name, body, actorId);
        return find(projectId, key).orElseThrow(
                () -> new IllegalStateException("prompt template missing after save: " + key));
    }

    @Override
    public boolean delete(long projectId, String key) {
        String sql = "DELETE FROM ai_prompt_template "
                + "WHERE project_id = ? AND template_key = ? AND builtin = 0";
        TenantGuard.requireProjectScope(sql, "ai_prompt_template");
        return jdbc.update(sql, projectId, key) > 0;
    }
}
