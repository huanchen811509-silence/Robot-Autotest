package com.qa.autotest.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.report.CaseCommandExtractor;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.report.CaseNode;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.KeywordNode;
import com.qa.autotest.domain.report.MetadataOverlay;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.application.execution.CriteriaEvaluation;
import com.qa.autotest.domain.report.SuiteNode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 报告仓储 JDBC 实现（RPT-01）。
 *
 * <p>租户口径说明：{@code run} 已登记在 {@link TenantGuard} 名单，所有读写都带 project_id 校验；
 * {@code test_result} / {@code keyword_result} 是 run 的子表、<b>没有 project_id 列</b>，
 * 因此不对它们单独调用 TenantGuard —— 访问入口统一经 run 的 project_id 校验，
 * 子表只在该 run 范围内按 run_id 写入（与既有 {@code TestResultWriterJdbc} 口径一致）。
 */
@Repository
public class ReportRepositoryJdbc implements ReportRepository {

    /**
     * 判据汇总走相关子查询，不新增 run 列：
     * 判据事实只有一份（{@code test_result.criteria_verdict}），在这里聚合就不存在「run 上的
     * 汇总列与明细不一致」的可能。口径：
     * <ul>
     *   <li>任一 FAIL → FAIL；</li>
     *   <li>全部未声明（criteria_verdict 全 NULL）→ UNDETERMINED；</li>
     *   <li>有 MISSING 或既有已判又有未声明 → UNDETERMINED（样本不全，不下结论）；</li>
     *   <li>其余（全 PASS）→ PASS。</li>
     * </ul>
     * 空 run（没有 test_result）同样是 UNDETERMINED。
     */
    private static final String REPORT_COLUMNS = """
            SELECT r.project_id, r.run_id, r.run_status, r.exportable, r.metadata_missing,
                   r.metadata_json, r.pass_count, r.fail_count, r.skip_count,
                   r.started_at, r.finished_at,
                   (SELECT CASE
                             WHEN COUNT(*) = 0 THEN NULL
                             WHEN SUM(tr.criteria_verdict = 'FAIL') > 0 THEN 'FAIL'
                             WHEN SUM(tr.criteria_verdict IS NULL) > 0 THEN 'UNDETERMINED'
                             WHEN SUM(tr.criteria_verdict = 'MISSING') > 0 THEN 'UNDETERMINED'
                             WHEN SUM(tr.criteria_verdict = 'PASS') = COUNT(*) THEN 'PASS'
                             ELSE 'UNDETERMINED'
                           END
                      FROM test_result tr WHERE tr.run_id = r.id) AS criteria_conclusion,
                   (SELECT COUNT(*) FROM test_result tr
                     WHERE tr.run_id = r.id AND tr.criteria_verdict IS NOT NULL) AS criteria_evaluated,
                   (SELECT COUNT(*) FROM test_result tr
                     WHERE tr.run_id = r.id AND tr.criteria_verdict = 'MISSING') AS criteria_missing_count
              FROM run r
            """;

    private static final String UPDATE_RUN = """
            UPDATE run
               SET `dut` = ?, `link` = ?, `sw_build` = ?, `baseline` = ?, `operator` = ?,
                   `case_kind` = ?, `metadata_json` = ?, `metadata_missing` = ?, `exportable` = ?,
                   `pass_count` = ?, `fail_count` = ?, `skip_count` = ?,
                   `started_at` = COALESCE(?, `started_at`),
                   `updated_at` = CURRENT_TIMESTAMP(3)
             WHERE `project_id` = ? AND `id` = ?
            """;

    private static final String UPDATE_GATE = """
            UPDATE run
               SET `metadata_missing` = ?, `exportable` = ?, `updated_at` = CURRENT_TIMESTAMP(3)
             WHERE `project_id` = ? AND `run_id` = ?
            """;

    private static final String INSERT_TEST_RESULT = """
            INSERT INTO test_result (run_id, case_id, case_key, round_no, verdict,
                                     duration_ms, failure_msg, log_offset, log_span, failure_kind,
                                     command_text, command_source, command_exit_code,
                                     retry_count, flaky,
                                     criteria_json, criteria_verdict, criteria_missing)
            VALUES (?, NULL, ?, 1, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_KEYWORD_RESULT = """
            INSERT INTO keyword_result (run_id, test_result_id, parent_id, kw_name,
                                        kw_status, depth, arg_count, log_offset, log_span)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RunMetadataRepository runMetadata;
    private final RowMapper<ReportRecord> rowMapper = this::mapRow;

    public ReportRepositoryJdbc(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                RunMetadataRepository runMetadata) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.runMetadata = runMetadata;
    }

    @Override
    public void replace(long projectId, long runDbId, ParsedReport parsed, GateResult gate, Instant startedAt) {
        TenantGuard.requireProjectScope(UPDATE_RUN, "run");
        int affected = jdbc.update(UPDATE_RUN,
                fit(parsed.metadataValue("DUT"), 40),
                fit(parsed.metadataValue("LINK"), 40),
                fit(parsed.metadataValue("SW_BUILD"), 40),
                fit(parsed.metadataValue("BASELINE"), 40),
                fit(parsed.metadataValue("OPERATOR"), 40),
                fit(parsed.metadataValue("CASE_KIND"), 20),
                writeMetadata(parsed.metadata()),
                gate.missingCsv(),
                gate.exportable() ? 1 : 0,
                parsed.passCount(),
                parsed.failCount(),
                parsed.skipCount(),
                startedAt == null ? null : Timestamp.from(startedAt),
                projectId,
                runDbId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "运行记录不存在或不属于当前项目: " + runDbId);
        }

        // 幂等：重复解析同一 run 时先清空旧结果，避免 test_result / keyword_result 翻倍。
        jdbc.update("DELETE FROM test_result WHERE run_id = ?", runDbId);
        jdbc.update("DELETE FROM keyword_result WHERE run_id = ?", runDbId);
        SuiteNode root = parsed.root();
        if (root != null) {
            String runId = jdbc.queryForObject("SELECT run_id FROM run WHERE id = ?", String.class, runDbId);
            insertSuite(runDbId, root, rootBasePath(root.name(), runId));
        }
    }

    @Override
    public Optional<ReportRecord> find(long projectId, String runId) {
        // W57-31：展示层默认过滤软删行（V11_63 的 run.deleted_at）。只改查询条件。
        String sql = REPORT_COLUMNS + " WHERE project_id = ? AND run_id = ? AND deleted_at IS NULL";
        TenantGuard.requireProjectScope(sql, "run");
        return JdbcSupport.first(jdbc.query(sql, rowMapper, projectId, runId))
                .map(record -> MetadataOverlay.apply(record, runMetadata.findAll(projectId, runId)));
    }

    @Override
    public void updateGate(long projectId, String runId, GateResult gate) {
        TenantGuard.requireProjectScope(UPDATE_GATE, "run");
        jdbc.update(UPDATE_GATE, gate.missingCsv(), gate.exportable() ? 1 : 0, projectId, runId);
    }

    @Override
    public PageResult<ReportRecord> page(long projectId, int page, int size) {
        // W57-31：列表计数与明细一起过滤软删行，保证 total 与 items 口径一致。
        String countSql = "SELECT COUNT(*) FROM run WHERE project_id = ? AND deleted_at IS NULL";
        TenantGuard.requireProjectScope(countSql, "run");
        Long total = jdbc.queryForObject(countSql, Long.class, projectId);
        long totalValue = total == null ? 0L : total;

        String listSql = REPORT_COLUMNS
                + " WHERE project_id = ? AND deleted_at IS NULL"
                + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "run");
        List<ReportRecord> rows = jdbc.query(listSql, rowMapper,
                projectId, size, (long) (page - 1) * size);
        return new PageResult<>(rows, totalValue, page, size);
    }

    /**
     * 根套件名由执行侧设为 {@code run-<runId>}（用于 RF 报告标题），但它不是用例路径的一部分。
     * 若把它拼进 {@code case_key}，同一条用例每次运行的 key 都不同，趋势/对比/覆盖率全部失效（G-B8-1）。
     * 这里剥离根套件，只保留「套件路径.用例名」，使 case_key 跨 run 稳定。
     */
    private void insertSuite(long runDbId, SuiteNode suite, String path) {
        for (CaseNode test : suite.cases()) {
            insertCase(runDbId, path.isEmpty() ? test.name() : path + "." + test.name(), test);
        }
        for (SuiteNode child : suite.suites()) {
            insertSuite(runDbId, child, path.isEmpty() ? child.name() : path + "/" + child.name());
        }
    }

    static String rootBasePath(String rootName, String runId) {
        if (rootName == null) {
            return "";
        }
        if (runId != null && !runId.isBlank() && rootName.equals("run-" + runId)) {
            return "";
        }
        return rootName;
    }

    private void insertCase(long runDbId, String caseKey, CaseNode test) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_TEST_RESULT, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runDbId);
            ps.setString(2, fit(caseKey, 160));
            ps.setString(3, test.status() == null ? "UNKNOWN" : test.status());
            ps.setLong(4, test.durationMs());
            ps.setString(5, test.failureMessage());
            ps.setObject(6, test.logOffset() < 0 ? null : test.logOffset(), Types.BIGINT);
            ps.setString(7, test.logSpan());
            // v3.9.0 / FR-v3.9-02：命令原文按 4KB 截断（CaseCommandExtractor 已截过，
            // 这里再按列口径兜一次，防止别处绕过提取器直接构造 CaseNode 写超长内容）
            ps.setString(8, fit(test.commandText(), CaseCommandExtractor.MAX_COMMAND_LENGTH + 32));
            ps.setString(9, fit(test.commandSource(), 24));
            ps.setObject(10, test.commandExitCode(), Types.INTEGER);
            // v5.6.0 / FR-v5.6-24：round_no 保持 1（它表达的是「报告取第几轮结论」，
            // 与 RF 的三遍执行同一语义）；复测轮数单独记，两者混用会让 RF 侧统计失真。
            ps.setInt(11, Math.max(0, test.retryCount()));
            ps.setBoolean(12, test.flaky());
            // W57-50：判据在入库这一刻评估并落列。之前这三列恒 NULL，报告结论只能退回
            // 「failCount==0 就 PASS」，等于把「判据机检」这条主题架空（审计 P0-4）。
            CriteriaEvaluation.Result criteria = CriteriaEvaluation.evaluate(test.criteriaPayload());
            ps.setString(13, criteria.json());
            ps.setString(14, criteria.verdict());
            ps.setString(15, criteria.missing());
            return ps;
        }, keyHolder);
        Long testResultId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        insertKeywords(runDbId, testResultId, null, test.keywords(), 0);
    }

    private void insertKeywords(long runDbId, Long testResultId, Long parentId,
                                List<KeywordNode> keywords, int depth) {
        for (KeywordNode keyword : keywords) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(INSERT_KEYWORD_RESULT, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, runDbId);
                ps.setObject(2, testResultId, Types.BIGINT);
                ps.setObject(3, parentId, Types.BIGINT);
                ps.setString(4, keyword.name() == null ? "" : fit(keyword.name(), 256));
                ps.setString(5, keyword.status());
                ps.setInt(6, depth);
                ps.setObject(7, keyword.logOffset() < 0 ? null : keyword.logOffset(), Types.BIGINT);
                ps.setString(8, keyword.logSpan());
                return ps;
            }, keyHolder);
            Long keywordId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
            insertKeywords(runDbId, testResultId, keywordId, keyword.children(), depth + 1);
        }
    }

    private ReportRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ReportRecord(
                rs.getLong("project_id"),
                rs.getString("run_id"),
                rs.getString("run_status"),
                rs.getInt("exportable") != 0,
                splitCsv(rs.getString("metadata_missing")),
                readMetadata(rs.getString("metadata_json")),
                rs.getInt("pass_count"),
                rs.getInt("fail_count"),
                rs.getInt("skip_count"),
                JdbcSupport.instant(rs.getTimestamp("started_at")),
                JdbcSupport.instant(rs.getTimestamp("finished_at")),
                rs.getString("criteria_conclusion"),
                rs.getInt("criteria_evaluated"),
                rs.getInt("criteria_missing_count"));
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : Arrays.asList(csv.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static String fit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
