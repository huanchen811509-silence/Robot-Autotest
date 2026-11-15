package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ReportDigestRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.report.ReportDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 报告结果概览仓储 JDBC 实现（PRJ-04）。
 *
 * <p>与 {@link OverviewRepositoryJdbc} 同口径：{@code run_status IN ('finished','failed')} 表示「完成」，
 * 通过率只算用例级明细；排序一律带 id 兜底，避免同一毫秒的两条运行顺序漂移（截图对不上数时最难查）。
 */
@Repository
public class ReportDigestRepositoryJdbc implements ReportDigestRepository {

    private static final String RECENT_REPORTS = """
            SELECT r.run_id, r.run_status, r.finished_at, r.started_at, r.enqueued_at, r.total_tests,
                   r.suite_count,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'PASS') AS case_pass,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'FAIL') AS case_fail,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'SKIP') AS case_skip
              FROM run r
             WHERE r.project_id = ? AND r.run_status IN ('finished', 'failed')
             ORDER BY COALESCE(r.finished_at, r.started_at, r.enqueued_at) DESC, r.id DESC
             LIMIT ?
            """;

    private static final String TOP_FAILED_CASES = """
            SELECT t.case_key, COUNT(*) AS failures,
                   MAX(COALESCE(r.finished_at, r.started_at, r.enqueued_at)) AS last_failed_at
              FROM test_result t
              JOIN run r ON r.id = t.run_id
             WHERE r.project_id = ? AND t.verdict = 'FAIL'
               AND COALESCE(r.started_at, r.enqueued_at) >= ?
             GROUP BY t.case_key
             ORDER BY failures DESC, t.case_key
             LIMIT ?
            """;

    /**
     * 覆盖率：启用用例数 + 能在任何已完成运行的结果里找到同名 key 的启用用例数。
     *
     * <p>{@code EXISTS} 里必须再带一次 {@code project_id}（结果表自己没有该列），否则跨项目同名 key
     * 会把别人的结果算进本项目的覆盖。
     */
    private static final String COVERAGE_COUNTS = """
            SELECT
              (SELECT COUNT(*) FROM test_case c WHERE c.project_id = ? AND c.status = 1) AS case_total,
              (SELECT COUNT(*) FROM test_case c
                WHERE c.project_id = ? AND c.status = 1
                  AND EXISTS (SELECT 1 FROM test_result t
                                JOIN run r ON r.id = t.run_id
                               WHERE r.project_id = c.project_id
                                 AND r.run_status IN ('finished', 'failed')
                                 AND t.case_key = c.case_code)) AS covered
            """;

    private static final String PASS_RATE_TREND = """
            SELECT r.run_id,
                   COALESCE(r.finished_at, r.started_at, r.enqueued_at) AS at,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'PASS') AS case_pass,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'FAIL') AS case_fail,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'SKIP') AS case_skip
              FROM run r
             WHERE r.project_id = ? AND r.run_status IN ('finished', 'failed')
               AND COALESCE(r.started_at, r.enqueued_at) >= ?
             ORDER BY at, r.id
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public ReportDigestRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportDigest.ReportRow> recentReports(long projectId, int limit) {
        TenantGuard.requireProjectScope(RECENT_REPORTS, "run");
        return jdbc.query(RECENT_REPORTS, (rs, rowNum) -> {
            int pass = rs.getInt("case_pass");
            int fail = rs.getInt("case_fail");
            int skip = rs.getInt("case_skip");
            int total = rs.getInt("total_tests");
            if (total <= 0) {
                total = pass + fail + skip;
            }
            return new ReportDigest.ReportRow(
                    rs.getString("run_id"),
                    rs.getString("run_status"),
                    JdbcSupport.instant(rs.getTimestamp("finished_at")),
                    total, pass, fail, skip,
                    ReportDigest.passRateBp(pass, fail, skip),
                    rs.getObject("suite_count", Integer.class));
        }, projectId, limit);
    }

    @Override
    public List<ReportDigest.FailedCase> topFailedCases(long projectId, Instant windowStart, int limit) {
        TenantGuard.requireProjectScope(TOP_FAILED_CASES, "run");
        return jdbc.query(TOP_FAILED_CASES, (rs, rowNum) -> new ReportDigest.FailedCase(
                rs.getString("case_key"),
                rs.getInt("failures"),
                JdbcSupport.instant(rs.getTimestamp("last_failed_at"))),
                projectId, Timestamp.from(windowStart), limit);
    }

    @Override
    public int[] coverageCounts(long projectId) {
        TenantGuard.requireProjectScope(COVERAGE_COUNTS, "test_case");
        int[] counts = jdbc.queryForObject(COVERAGE_COUNTS, (rs, rowNum) -> new int[]{
                rs.getInt("case_total"), rs.getInt("covered")}, projectId, projectId);
        return counts == null ? new int[]{0, 0} : counts;
    }

    @Override
    public List<ReportDigest.PassPoint> passRateTrend(long projectId, Instant windowStart, int limit) {
        TenantGuard.requireProjectScope(PASS_RATE_TREND, "run");
        return jdbc.query(PASS_RATE_TREND, (rs, rowNum) -> {
            int pass = rs.getInt("case_pass");
            int fail = rs.getInt("case_fail");
            int skip = rs.getInt("case_skip");
            return new ReportDigest.PassPoint(
                    rs.getString("run_id"),
                    JdbcSupport.instant(rs.getTimestamp("at")),
                    ReportDigest.passRateBp(pass, fail, skip),
                    fail);
        }, projectId, Timestamp.from(windowStart), limit);
    }
}
