package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.OverviewRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.overview.ProjectOverview;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 总览只读仓储 JDBC 实现（OVW-01）。
 *
 * <p>五条 SQL 全部显式带 {@code project_id} 并过 {@link TenantGuard}；端口与用例结果表没有
 * {@code project_id} 列，一律 join 主表取租户，不做例外。
 *
 * <p>状态口径：V4_1 之后 {@code run_status} 只有
 * {@code queued/preparing/running/finished/failed/canceled}——**没有 {@code passed}**。
 * 这里统一用 {@code ('finished','failed')} 表示「完成」。
 */
@Repository
public class OverviewRepositoryJdbc implements OverviewRepository {

    private static final String CASE_TOTAL = """
            SELECT COUNT(*) FROM test_case WHERE project_id = ? AND status = 1
            """;

    /**
     * 分组统计的 key 一律用 {@code COALESCE(col, '')}：**空串表示未填**，
     * 由前端翻成「未标注」/「Not set」。不在这里写展示文案——接口吐中文，
     * 英文界面就必然冒中文。
     */
    private static final String CASE_BY_PRIORITY = """
            SELECT COALESCE(priority, '') AS k, COUNT(*) AS c
              FROM test_case
             WHERE project_id = ? AND status = 1
             GROUP BY k
             ORDER BY c DESC, k
            """;

    private static final String CASE_BY_REVIEW = """
            SELECT COALESCE(review_status, '') AS k, COUNT(*) AS c
              FROM test_case
             WHERE project_id = ? AND status = 1
             GROUP BY k
             ORDER BY c DESC, k
            """;

    private static final String CASE_TOP_DOMAINS = """
            SELECT COALESCE(domain_tag, '') AS k, COUNT(*) AS c
              FROM test_case
             WHERE project_id = ? AND status = 1
             GROUP BY k
             ORDER BY c DESC, k
             LIMIT ?
            """;

    /**
     * 资产计数与资产页同一口径（{@link AssetScopeSql}）：私有 OR 平台共享且本项目已引用。
     * 否则「总览说有 3 台设备、资产页只有 2 台」这种对不上的数会出现。
     */
    private static final String ASSET_COUNTS = "SELECT "
            + "(SELECT COUNT(*) FROM device d " + AssetScopeSql.DEVICE_JOIN
            + AssetScopeSql.DEVICE_WHERE + " AND d.status = 1) AS devices, "
            + "(SELECT COUNT(*) FROM device_port p JOIN device d ON d.id = p.device_id "
            + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE + ") AS ports, "
            + "(SELECT COUNT(*) FROM link l " + AssetScopeSql.LINK_JOIN
            + AssetScopeSql.LINK_WHERE + " AND l.status = 1) AS links, "
            + "(SELECT COUNT(*) FROM credential c " + AssetScopeSql.CREDENTIAL_JOIN
            + AssetScopeSql.CREDENTIAL_WHERE + ") AS credentials, "
            + "(SELECT COUNT(*) FROM env_profile WHERE project_id = ?) AS environments, "
            + "(SELECT COUNT(*) FROM test_set WHERE project_id = ? AND set_status = 'active') AS test_sets";

    private static final String RUN_COUNTS = """
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN COALESCE(started_at, enqueued_at) >= ? THEN 1 ELSE 0 END), 0) AS in_window,
                   COALESCE(SUM(CASE WHEN run_status IN ('queued', 'preparing', 'running') THEN 1 ELSE 0 END), 0) AS running,
                   COALESCE(SUM(CASE WHEN run_status = 'finished' THEN 1 ELSE 0 END), 0) AS finished,
                   COALESCE(SUM(CASE WHEN run_status = 'failed' THEN 1 ELSE 0 END), 0) AS failed,
                   COALESCE(SUM(CASE WHEN run_status = 'canceled' THEN 1 ELSE 0 END), 0) AS canceled,
                   AVG(CASE WHEN run_status IN ('finished', 'failed')
                                 AND started_at IS NOT NULL AND finished_at IS NOT NULL
                            THEN TIMESTAMPDIFF(MICROSECOND, started_at, finished_at) / 1000.0 END) AS avg_ms,
                   MAX(COALESCE(started_at, enqueued_at)) AS last_run_at
              FROM run
             WHERE project_id = ?
            """;

    /**
     * 用例级结论计数（不是 run 数）。只统计窗口内「已完成」的 run：
     * 取消的 run 可能只产出一半结果，算进分母会把通过率拉低。
     */
    private static final String CASE_VERDICTS = """
            SELECT COALESCE(SUM(CASE WHEN t.verdict = 'PASS' THEN 1 ELSE 0 END), 0) AS case_pass,
                   COALESCE(SUM(CASE WHEN t.verdict = 'FAIL' THEN 1 ELSE 0 END), 0) AS case_fail,
                   COALESCE(SUM(CASE WHEN t.verdict = 'SKIP' THEN 1 ELSE 0 END), 0) AS case_skip
              FROM test_result t
              JOIN run r ON r.id = t.run_id
             WHERE r.project_id = ?
               AND r.run_status IN ('finished', 'failed')
               AND COALESCE(r.started_at, r.enqueued_at) >= ?
            """;

    private static final String RECENT_RUNS = """
            SELECT r.run_id, r.run_status, r.started_at, r.finished_at, r.enqueued_at,
                   r.total_tests, r.passed_tests, r.failed_tests,
                   r.dut, r.link, r.trigger_type,
                   s.name AS test_set_name,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'PASS') AS case_pass,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'FAIL') AS case_fail,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id AND t.verdict = 'SKIP') AS case_skip,
                   (SELECT COUNT(*) FROM test_result t WHERE t.run_id = r.id) AS case_rows
              FROM run r
              LEFT JOIN test_set s ON s.id = r.test_set_id AND s.project_id = r.project_id
             WHERE r.project_id = ?
             ORDER BY COALESCE(r.started_at, r.enqueued_at) DESC, r.id DESC
             LIMIT ?
            """;

    private static final String DAILY_TREND = """
            SELECT DATE_FORMAT(COALESCE(started_at, enqueued_at), '%Y-%m-%d') AS d,
                   COALESCE(SUM(CASE WHEN run_status = 'finished' THEN 1 ELSE 0 END), 0) AS finished_runs,
                   COALESCE(SUM(CASE WHEN run_status = 'failed' THEN 1 ELSE 0 END), 0) AS failed_runs,
                   COALESCE(SUM(CASE WHEN run_status = 'canceled' THEN 1 ELSE 0 END), 0) AS canceled_runs
              FROM run
             WHERE project_id = ? AND COALESCE(started_at, enqueued_at) >= ?
             GROUP BY d
             ORDER BY d
            """;

    private final JdbcTemplate jdbc;

    public OverviewRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProjectOverview.CaseStats caseStats(long projectId, int topDomainLimit) {
        TenantGuard.requireProjectScope(CASE_TOTAL, "test_case");
        TenantGuard.requireProjectScope(CASE_BY_PRIORITY, "test_case");
        TenantGuard.requireProjectScope(CASE_BY_REVIEW, "test_case");
        TenantGuard.requireProjectScope(CASE_TOP_DOMAINS, "test_case");

        Integer total = jdbc.queryForObject(CASE_TOTAL, Integer.class, projectId);
        return new ProjectOverview.CaseStats(
                total == null ? 0 : total,
                counts(CASE_BY_PRIORITY, projectId),
                counts(CASE_BY_REVIEW, projectId),
                counts(CASE_TOP_DOMAINS, projectId, topDomainLimit));
    }

    @Override
    public ProjectOverview.AssetStats assetStats(long projectId) {
        TenantGuard.requireProjectScope(ASSET_COUNTS, "device");
        return jdbc.queryForObject(ASSET_COUNTS, (rs, rowNum) -> new ProjectOverview.AssetStats(
                rs.getInt("devices"),
                rs.getInt("ports"),
                rs.getInt("links"),
                rs.getInt("credentials"),
                rs.getInt("environments"),
                rs.getInt("test_sets")),
                projectId, projectId, projectId, projectId, projectId, projectId,
                projectId, projectId, projectId, projectId);
    }

    @Override
    public ProjectOverview.RunCounts runCounts(long projectId, Instant windowStart) {
        TenantGuard.requireProjectScope(RUN_COUNTS, "run");
        TenantGuard.requireProjectScope(CASE_VERDICTS, "run");

        ProjectOverview.RunCounts base = jdbc.queryForObject(RUN_COUNTS,
                (rs, rowNum) -> new ProjectOverview.RunCounts(
                        rs.getInt("total"),
                        rs.getInt("in_window"),
                        rs.getInt("running"),
                        rs.getInt("finished"),
                        rs.getInt("failed"),
                        rs.getInt("canceled"),
                        0, 0, 0,
                        doubleOrNull(rs.getObject("avg_ms")),
                        JdbcSupport.instant(rs.getTimestamp("last_run_at"))),
                Timestamp.from(windowStart), projectId);

        int[] verdicts = jdbc.queryForObject(CASE_VERDICTS, (rs, rowNum) -> new int[]{
                rs.getInt("case_pass"), rs.getInt("case_fail"), rs.getInt("case_skip")},
                projectId, Timestamp.from(windowStart));

        ProjectOverview.RunCounts counted = base == null ? ProjectOverview.RunCounts.empty() : base;
        int pass = verdicts == null ? 0 : verdicts[0];
        int fail = verdicts == null ? 0 : verdicts[1];
        int skip = verdicts == null ? 0 : verdicts[2];
        return new ProjectOverview.RunCounts(counted.total(), counted.inWindow(), counted.running(),
                counted.finished(), counted.failed(), counted.canceled(),
                pass, fail, skip, counted.avgDurationMs(), counted.lastRunAt());
    }

    @Override
    public List<ProjectOverview.RecentRun> recentRuns(long projectId, int limit) {
        TenantGuard.requireProjectScope(RECENT_RUNS, "run");
        return jdbc.query(RECENT_RUNS, (rs, rowNum) -> {
            int pass = rs.getInt("case_pass");
            int fail = rs.getInt("case_fail");
            int skip = rs.getInt("case_skip");
            Instant started = JdbcSupport.instant(rs.getTimestamp("started_at"));
            Instant finished = JdbcSupport.instant(rs.getTimestamp("finished_at"));
            Instant enqueued = JdbcSupport.instant(rs.getTimestamp("enqueued_at"));
            Long duration = started == null || finished == null
                    ? null : finished.toEpochMilli() - started.toEpochMilli();
            // v5.22.0 / FR-v5.22-05：计数只认明细聚合。
            // 原来写的是 `pass > 0 ? pass : passed_tests`——「哪个非零用哪个」，在两个真源之间
            // 反复横跳：明细 0 PASS 而列里存着 5 的时候（正是 L-19-06 的形状）它恰好会挑错的那个。
            // 现在规则只有一条：**有明细用明细，没明细才退到列上的执行期估值**。
            // 四列已由入库重算与 backfill-run-counts.sh 对齐，这里的兜底只是给「根本没明细」的 run 留个脸。
            int rows = rs.getInt("case_rows");
            boolean detailed = rows > 0;
            int total = detailed
                    ? pass + fail + skip
                    : Math.max(0, rs.getInt("total_tests"));
            return new ProjectOverview.RecentRun(
                    rs.getString("run_id"),
                    rs.getString("run_status"),
                    started == null ? enqueued : started,
                    finished,
                    duration,
                    total,
                    detailed ? pass : rs.getInt("passed_tests"),
                    detailed ? fail : rs.getInt("failed_tests"),
                    rs.getString("dut"),
                    rs.getString("link"),
                    rs.getString("test_set_name"),
                    rs.getString("trigger_type"),
                    ProjectOverview.passRateBp(pass, fail, skip));
        }, projectId, limit);
    }

    @Override
    public List<ProjectOverview.DailyPoint> dailyTrend(long projectId, Instant windowStart) {
        TenantGuard.requireProjectScope(DAILY_TREND, "run");
        return jdbc.query(DAILY_TREND, (rs, rowNum) -> new ProjectOverview.DailyPoint(
                rs.getString("d"),
                rs.getInt("finished_runs"),
                rs.getInt("failed_runs"),
                rs.getInt("canceled_runs")), projectId, Timestamp.from(windowStart));
    }

    private List<ProjectOverview.CountItem> counts(String sql, Object... args) {
        return jdbc.query(sql,
                (rs, rowNum) -> new ProjectOverview.CountItem(rs.getString("k"), rs.getInt("c")), args);
    }

    private static Double doubleOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).doubleValue();
    }
}
