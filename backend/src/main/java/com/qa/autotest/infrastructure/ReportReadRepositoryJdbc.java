package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ReportReadRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.report.CaseLogRef;
import com.qa.autotest.domain.report.CaseVerdict;
import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.RunCaseRow;
import com.qa.autotest.domain.report.RunSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 报告域只读仓储 JDBC 实现（RPT-02 / RPT-03）。
 *
 * <p>test_result / keyword_result 没有 project_id 列，统一 join {@code run} 做租户校验；
 * 所有 SQL 都带 project_id，满足 {@link TenantGuard} 第 2 层要求。
 */
@Repository
public class ReportReadRepositoryJdbc implements ReportReadRepository {

    private static final String RUN_SNAPSHOT = """
            SELECT project_id, run_id, run_status, `dut`, `link`, started_at
              FROM `run`
            """;

    private static final String CASE_VERDICTS = """
            SELECT t.case_key, t.verdict, COALESCE(t.duration_ms, 0) AS duration_ms
              FROM test_result t
              JOIN `run` r ON r.id = t.run_id
             WHERE r.project_id = ? AND r.run_id = ?
             ORDER BY t.id
            """;

    // v3.9.0：多带命令三列（FR-v3.9-02）；老库未迁移时列不存在，故读取侧对 null 宽容。
    private static final String CASE_ROWS = """
            SELECT t.case_key, t.verdict, COALESCE(t.duration_ms, 0) AS duration_ms, t.failure_msg,
                   t.command_text, t.command_source, t.command_exit_code,
                   COALESCE(t.retry_count, 0) AS retry_count, COALESCE(t.flaky, 0) AS flaky
              FROM test_result t
              JOIN `run` r ON r.id = t.run_id
             WHERE r.project_id = ? AND r.run_id = ?
             ORDER BY t.id
            """;

    private static final String KEYWORD_ROWS = """
            SELECT k.id, k.test_result_id, t.case_key, k.parent_id,
                   k.kw_name, k.kw_status, k.depth
              FROM keyword_result k
              JOIN test_result t ON t.id = k.test_result_id
              JOIN `run` r ON r.id = k.run_id
             WHERE r.project_id = ? AND r.run_id = ?
             ORDER BY k.id
            """;

    private static final String CASE_LOG = """
            SELECT t.case_key, t.log_offset, t.log_span
              FROM test_result t
              JOIN `run` r ON r.id = t.run_id
             WHERE r.project_id = ? AND r.run_id = ? AND t.case_key = ?
            """;

    private final JdbcTemplate jdbc;

    public ReportReadRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RunSnapshot> findRun(long projectId, String runId) {
        String sql = RUN_SNAPSHOT + " WHERE project_id = ? AND run_id = ?";
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, (rs, rowNum) -> mapRun(rs), projectId, runId));
    }

    @Override
    public Optional<RunSnapshot> findBaseline(long projectId, String dut, String link,
                                              Instant before, String excludeRunId) {
        if (before == null) {
            return Optional.empty();
        }
        String sql = RUN_SNAPSHOT + """
                 WHERE project_id = ?
                   AND run_status = 'finished'
                   AND COALESCE(`dut`, '') = ?
                   AND COALESCE(`link`, '') = ?
                   AND started_at IS NOT NULL
                   AND started_at < ?
                   AND run_id <> ?
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, (rs, rowNum) -> mapRun(rs),
                projectId, dut == null ? "" : dut, link == null ? "" : link,
                Timestamp.from(before), excludeRunId == null ? "" : excludeRunId));
    }

    @Override
    public List<RunSnapshot> listBaselineCandidates(long projectId, String dut, String link,
                                                    String excludeRunId, int limit) {
        // N4：不再按 DUT/LINK 过滤——跨环境手动基线在 v2.1 里是允许的（响应带 crossEnvironment 标记），
        // 但仍把同环境排在前面，避免跨环境候选挤掉用户最可能选的那几条。
        String sql = RUN_SNAPSHOT + """
                 WHERE project_id = ?
                   AND run_status = 'finished'
                   AND run_id <> ?
                 ORDER BY CASE WHEN COALESCE(`dut`, '') = ? AND COALESCE(`link`, '') = ?
                               THEN 0 ELSE 1 END,
                          started_at DESC, id DESC
                 LIMIT ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.query(sql, (rs, rowNum) -> mapRun(rs),
                projectId, excludeRunId == null ? "" : excludeRunId,
                dut == null ? "" : dut, link == null ? "" : link, limit);
    }

    @Override
    public List<CaseVerdict> listCaseVerdicts(long projectId, String runId) {
        TenantGuard.requireProjectScope(CASE_VERDICTS, "run");
        return jdbc.query(CASE_VERDICTS, (rs, rowNum) -> new CaseVerdict(
                rs.getString("case_key"), rs.getString("verdict"), rs.getLong("duration_ms")),
                projectId, runId);
    }

    @Override
    public List<RunCaseRow> listCases(long projectId, String runId) {
        TenantGuard.requireProjectScope(CASE_ROWS, "run");
        return jdbc.query(CASE_ROWS, (rs, rowNum) -> new RunCaseRow(
                rs.getString("case_key"), rs.getString("verdict"),
                rs.getLong("duration_ms"), rs.getString("failure_msg"),
                rs.getString("command_text"), rs.getString("command_source"),
                nullableInt(rs.getObject("command_exit_code")),
                rs.getInt("retry_count"), rs.getInt("flaky") != 0),
                projectId, runId);
    }

    @Override
    public List<KeywordRow> listKeywords(long projectId, String runId) {
        TenantGuard.requireProjectScope(KEYWORD_ROWS, "run");
        return jdbc.query(KEYWORD_ROWS, (rs, rowNum) -> new KeywordRow(
                rs.getLong("id"),
                rs.getLong("test_result_id"),
                rs.getString("case_key"),
                nullableLong(rs.getObject("parent_id")),
                rs.getString("kw_name"),
                rs.getString("kw_status"),
                rs.getInt("depth")),
                projectId, runId);
    }

    @Override
    public Optional<CaseLogRef> findCaseLog(long projectId, String runId, String caseKey) {
        TenantGuard.requireProjectScope(CASE_LOG, "run");
        return JdbcSupport.first(jdbc.query(CASE_LOG, (rs, rowNum) -> new CaseLogRef(
                rs.getString("case_key"),
                nullableLong(rs.getObject("log_offset")) == null ? -1L : rs.getLong("log_offset"),
                rs.getString("log_span")),
                projectId, runId, caseKey));
    }

    private RunSnapshot mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunSnapshot(
                rs.getLong("project_id"),
                rs.getString("run_id"),
                rs.getString("run_status"),
                rs.getString("dut"),
                rs.getString("link"),
                JdbcSupport.instant(rs.getTimestamp("started_at")));
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
