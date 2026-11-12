package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 项目级配置项仓储实现（`project_setting` KV）。 */
@Repository
public class ProjectSettingRepositoryJdbc implements ProjectSettingRepository {

    private final JdbcTemplate jdbc;

    public ProjectSettingRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(long projectId, String key) {
        return jdbc.queryForList(
                        "SELECT setting_value FROM project_setting WHERE project_id = ? AND setting_key = ?",
                        String.class, projectId, key)
                .stream().findFirst();
    }

    @Override
    public Map<String, String> findAll(long projectId, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM project_setting "
                        + "WHERE project_id = ? AND setting_key LIKE ? ORDER BY setting_key",
                rs -> {
                    String key = rs.getString("setting_key");
                    if (prefix != null && !prefix.isEmpty() && key.startsWith(prefix)) {
                        key = key.substring(prefix.length());
                    }
                    result.put(key, rs.getString("setting_value"));
                }, projectId, (prefix == null ? "" : prefix) + "%");
        return result;
    }

    @Override
    public void put(long projectId, String key, String value, Long operatorId) {
        jdbc.update("""
                INSERT INTO project_setting (project_id, setting_key, setting_value, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value),
                                        updated_by = VALUES(updated_by)
                """, projectId, key, value, operatorId, operatorId);
    }

    @Override
    public void remove(long projectId, String key) {
        jdbc.update("DELETE FROM project_setting WHERE project_id = ? AND setting_key = ?",
                projectId, key);
    }
}
