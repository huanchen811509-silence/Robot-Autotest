package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.CaseDurationBaselineRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.execution.CaseDurationBaseline;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 用例时长基线仓储实现（`case_duration_baseline` + 样本取自 `test_result`）。 */
@Repository
public class CaseDurationBaselineRepositoryJdbc implements CaseDurationBaselineRepository {

    private static final String COLUMNS = "id, project_id, case_id, kernel, sample_count, "
            + "p50_ms, p90_ms, p99_ms, max_ms, excluded_count, insufficient, window_start, "
            + "window_end, computed_at";

    /**
     * 样本查询。
     *
     * <p>只取 PASS/FAIL：SKIP 与 plan-only 的 duration 是「几乎没跑」的耗时，
     * 混进来会把 P50 拉到几毫秒，软超时阈值随之失效。剔除环境失败在应用层做
     * （{@code failure_msg} 关键字粗筛）——那需要与归因分类保持一致，
     * 写在 SQL 里就会出现「SQL 一套关键字、Java 一套分类」的双真源。
     */
    private static final String SAMPLE_SQL = """
            SELECT tr.case_id, r.kernel, tr.duration_ms, tr.verdict, tr.failure_msg, tr.created_at
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND tr.created_at >= ?
               AND tr.case_id IS NOT NULL
               AND tr.duration_ms IS NOT NULL
               AND tr.verdict IN ('PASS', 'FAIL')
            """;

    private final JdbcTemplate jdbc;

    public CaseDurationBaselineRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DurationSample> samples(long projectId, java.time.Instant since) {
        TenantGuard.requireProjectScope(SAMPLE_SQL, "run");
        return jdbc.query(SAMPLE_SQL, (rs, rowNum) -> new DurationSample(
                rs.getLong("case_id"),
                rs.getString("kernel"),
                rs.getLong("duration_ms"),
                rs.getString("verdict"),
                rs.getString("failure_msg"),
                instant(rs.getTimestamp("created_at"))), projectId, Timestamp.from(since));
    }

    @Override
    @Transactional
    public void replaceAll(long projectId, List<CaseDurationBaseline> rows) {
        String delete = "DELETE FROM case_duration_baseline WHERE project_id = ?";
        TenantGuard.requireProjectScope(delete, "case_duration_baseline");
        jdbc.update(delete, projectId);
        for (CaseDurationBaseline row : rows) {
            jdbc.update("""
                    INSERT INTO case_duration_baseline
                      (project_id, case_id, kernel, sample_count, p50_ms, p90_ms, p99_ms,
                       max_ms, excluded_count, insufficient, window_start, window_end,
                       computed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, row.projectId(), row.caseId(), row.kernel(), row.sampleCount(),
                    row.p50Ms(), row.p90Ms(), row.p99Ms(), row.maxMs(), row.excludedCount(),
                    row.insufficient(), timestamp(row.windowStart()), timestamp(row.windowEnd()),
                    timestamp(row.computedAt()));
        }
    }

    @Override
    public List<CaseDurationBaseline> list(long projectId) {
        String sql = "SELECT " + COLUMNS + " FROM case_duration_baseline WHERE project_id = ? "
                + "ORDER BY case_id, kernel";
        TenantGuard.requireProjectScope(sql, "case_duration_baseline");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId);
    }

    @Override
    public Optional<CaseDurationBaseline> find(long projectId, long caseId, String kernel) {
        String sql = "SELECT " + COLUMNS + " FROM case_duration_baseline "
                + "WHERE project_id = ? AND case_id = ? AND kernel = ?";
        TenantGuard.requireProjectScope(sql, "case_duration_baseline");
        return jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, caseId, kernel)
                .stream().findFirst();
    }

    @Override
    public long countByProject(long projectId) {
        String sql = "SELECT COUNT(*) FROM case_duration_baseline WHERE project_id = ?";
        TenantGuard.requireProjectScope(sql, "case_duration_baseline");
        Long count = jdbc.queryForObject(sql, Long.class, projectId);
        return count == null ? 0L : count;
    }

    @Override
    public List<Long> projectsWithRecentRuns(java.time.Instant since) {
        return jdbc.queryForList(
                "SELECT DISTINCT project_id FROM run WHERE created_at >= ? ORDER BY project_id",
                Long.class, Timestamp.from(since));
    }

    /**
     * {@code caseCode -> p90Ms}。
     *
     * <p>只取 {@code insufficient = 0} 的行：样本不足的基线是「猜的」，
     * 拿它去估算长跑会让判定失去意义。SQL 里直接过滤，应用层就永远拿不到可疑值。
     */
    @Override
    public java.util.Map<String, Long> p90ByCaseCode(long projectId, String kernel) {
        String sql = """
                SELECT tc.case_code, b.p90_ms
                  FROM case_duration_baseline b
                  JOIN test_case tc ON tc.id = b.case_id
                 WHERE b.project_id = ?
                   AND b.insufficient = 0
                   AND b.p90_ms IS NOT NULL
                """ + (kernel == null ? "" : "   AND b.kernel = ?\n");
        TenantGuard.requireProjectScope(sql, "case_duration_baseline");
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString("case_code"), rs.getLong("p90_ms"));
        }, kernel == null ? new Object[] {projectId} : new Object[] {projectId, kernel});
        return java.util.Map.copyOf(out);
    }

    private static CaseDurationBaseline map(ResultSet rs) throws SQLException {
        return new CaseDurationBaseline(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("case_id"),
                rs.getString("kernel"),
                rs.getInt("sample_count"),
                nullableLong(rs.getObject("p50_ms")),
                nullableLong(rs.getObject("p90_ms")),
                nullableLong(rs.getObject("p99_ms")),
                nullableLong(rs.getObject("max_ms")),
                rs.getInt("excluded_count"),
                rs.getBoolean("insufficient"),
                instant(rs.getTimestamp("window_start")),
                instant(rs.getTimestamp("window_end")),
                instant(rs.getTimestamp("computed_at")));
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
