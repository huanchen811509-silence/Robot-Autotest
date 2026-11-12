package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.EnvironmentRadarRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 环境健康雷达取数（`test_result` JOIN `run`）。 */
@Repository
public class EnvironmentRadarRepositoryJdbc implements EnvironmentRadarRepository {

    /**
     * 失败样本。
     *
     * <p>只取 {@code verdict='FAIL'}：SKIP 不是失败，把 SKIP 计入分母会让「环境失败占比」
     * 随着越来越多人跳过用例而虚假下降——那是用一个统计口径掩盖另一个问题。
     */
    private static final String FAILURE_SQL = """
            SELECT tr.run_id, tr.case_id, r.kernel, tr.failure_msg, tr.created_at
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND tr.created_at >= ?
               AND tr.verdict = 'FAIL'
             ORDER BY tr.created_at DESC
             LIMIT ?
            """;

    private static final String COUNT_SQL = """
            SELECT COUNT(*)
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND tr.created_at >= ?
               AND tr.verdict = 'FAIL'
            """;

    private final JdbcTemplate jdbc;

    public EnvironmentRadarRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FailureSample> recentFailures(long projectId, Instant since, int limit) {
        TenantGuard.requireProjectScope(FAILURE_SQL, "run");
        return jdbc.query(FAILURE_SQL, (rs, rowNum) -> new FailureSample(
                rs.getLong("run_id"),
                rs.getLong("case_id"),
                rs.getString("kernel"),
                rs.getString("failure_msg"),
                instant(rs.getTimestamp("created_at"))), projectId, Timestamp.from(since),
                Math.max(1, limit));
    }

    @Override
    public long countFailures(long projectId, Instant since) {
        TenantGuard.requireProjectScope(COUNT_SQL, "run");
        Long count = jdbc.queryForObject(COUNT_SQL, Long.class, projectId, Timestamp.from(since));
        return count == null ? 0L : count;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
