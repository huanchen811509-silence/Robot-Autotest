package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.PrecheckRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 运行预检 JDBC 实现；所有 SQL 均以 project_id 收口。 */
@Repository
public class PrecheckRepositoryJdbc implements PrecheckRepository {

    private final JdbcTemplate jdbc;

    public PrecheckRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countCases(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM test_case WHERE project_id = ? AND id IN ("
                + placeholders(caseIds.size()) + ")";
        TenantGuard.requireProjectScope(sql, "test_case");
        Object[] args = args(projectId, caseIds);
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public int countTestSetCases(long projectId, long testSetId) {
        String sql = "SELECT COUNT(DISTINCT i.test_case_id) "
                + "FROM test_set_item i JOIN test_case c ON c.id = i.test_case_id "
                + "WHERE c.project_id = ? AND i.test_set_id = ?";
        TenantGuard.requireProjectScope(sql, "test_case");
        Long value = jdbc.queryForObject(sql, Long.class, projectId, testSetId);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public int countPlanCases(long projectId, long planId) {
        String sql = "SELECT COUNT(DISTINCT i.test_case_id) "
                + "FROM execution_plan p "
                + "JOIN test_set_item i ON i.test_set_id = p.test_set_id "
                + "JOIN test_case c ON c.id = i.test_case_id "
                + "WHERE p.project_id = ? AND p.id = ? AND c.project_id = ?";
        TenantGuard.requireProjectScope(sql, "execution_plan");
        Long value = jdbc.queryForObject(sql, Long.class, projectId, planId, projectId);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public int countRiskyCases(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM test_case WHERE project_id = ? "
                + "AND safety_level IN ('L2','LX') AND id IN (" + placeholders(caseIds.size()) + ")";
        TenantGuard.requireProjectScope(sql, "test_case");
        Object[] args = args(projectId, caseIds);
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public int countRiskyTestSetCases(long projectId, long testSetId) {
        String sql = "SELECT COUNT(DISTINCT i.test_case_id) "
                + "FROM test_set_item i JOIN test_case c ON c.id = i.test_case_id "
                + "WHERE c.project_id = ? AND i.test_set_id = ? AND c.safety_level IN ('L2','LX')";
        TenantGuard.requireProjectScope(sql, "test_case");
        Long value = jdbc.queryForObject(sql, Long.class, projectId, testSetId);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public int countRiskyPlanCases(long projectId, long planId) {
        String sql = "SELECT COUNT(DISTINCT i.test_case_id) "
                + "FROM execution_plan p "
                + "JOIN test_set_item i ON i.test_set_id = p.test_set_id "
                + "JOIN test_case c ON c.id = i.test_case_id "
                + "WHERE p.project_id = ? AND p.id = ? AND c.project_id = ? "
                + "AND c.safety_level IN ('L2','LX')";
        TenantGuard.requireProjectScope(sql, "execution_plan");
        Long value = jdbc.queryForObject(sql, Long.class, projectId, planId, projectId);
        return value == null ? 0 : value.intValue();
    }

    @Override
    public DurationSample durationSample(long projectId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        String sql = "SELECT COUNT(*) AS sample_count, COALESCE(AVG(duration_ms), 0) AS avg_ms FROM ("
                + "SELECT tr.duration_ms FROM test_result tr JOIN run r ON r.id = tr.run_id "
                + "WHERE r.project_id = ? AND tr.duration_ms IS NOT NULL "
                + "ORDER BY tr.id DESC LIMIT ?) sample";
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.queryForObject(sql, (rs, rowNum) -> new DurationSample(
                rs.getInt("sample_count"), rs.getLong("avg_ms")), projectId, capped);
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static Object[] args(long projectId, List<Long> caseIds) {
        Object[] args = new Object[caseIds.size() + 1];
        args[0] = projectId;
        for (int i = 0; i < caseIds.size(); i++) {
            args[i + 1] = caseIds.get(i);
        }
        return args;
    }
}
