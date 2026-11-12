package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ExecModeStatsRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 按执行方式聚合用例结果（`test_result` + `run`）。 */
@Repository
public class ExecModeStatsRepositoryJdbc implements ExecModeStatsRepository {

    /**
     * 聚合。
     *
     * <p>分母只取 PASS/FAIL：PLANNED、PENDING、SKIP 混进分母会让通过率随「跳过的多少」
     * 上下浮动，而跳过根本不是结论。这条与时长基线、环境雷达的口径保持一致——
     * 三个功能对同一张表用三种分母，是最容易发生也最难发现的统计事故。
     */
    private static final String BASE_SQL = """
            SELECT COALESCE(tr.exec_mode, 'auto') AS exec_mode,
                   COUNT(*) AS total,
                   SUM(CASE WHEN tr.verdict = 'PASS' THEN 1 ELSE 0 END) AS passed
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND tr.created_at >= ?
               AND tr.verdict IN ('PASS', 'FAIL')
            """;

    private final JdbcTemplate jdbc;

    public ExecModeStatsRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ModeCount> byExecMode(long projectId, Instant since, String runId) {
        boolean singleRun = runId != null && !runId.isBlank();
        String sql = BASE_SQL
                + (singleRun ? "   AND r.run_id = ?\n" : "")
                + "  GROUP BY COALESCE(tr.exec_mode, 'auto') ORDER BY exec_mode";
        TenantGuard.requireProjectScope(sql, "run");
        Object[] args = singleRun
                ? new Object[] {projectId, Timestamp.from(since), runId.trim()}
                : new Object[] {projectId, Timestamp.from(since)};
        return jdbc.query(sql, (rs, rowNum) -> new ModeCount(
                rs.getString("exec_mode"),
                rs.getInt("total"),
                rs.getInt("passed")), args);
    }
}
