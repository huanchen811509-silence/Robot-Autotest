package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.EnvironmentGateRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 环境闸门留痕 JDBC 实现：所有 SQL 先过租户守卫。 */
@Repository
public class EnvironmentGateRepositoryJdbc implements EnvironmentGateRepository {

    private final JdbcTemplate jdbc;

    public EnvironmentGateRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int noteRun(long projectId, String runId, String note) {
        String sql = "UPDATE run SET env_check_note = ? WHERE project_id = ? AND run_id = ? "
                + "AND env_check_note IS NULL";
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.update(sql, note, projectId, runId);
    }

    @Override
    public List<RunNote> notes(long projectId, int limit) {
        String sql = "SELECT run_id, env_check_note FROM run WHERE project_id = ? "
                + "AND env_check_note IS NOT NULL ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.query(sql, (rs, n) -> new RunNote(rs.getString("run_id"),
                rs.getString("env_check_note")), projectId, limit <= 0 ? 20 : Math.min(limit, 100));
    }
}
