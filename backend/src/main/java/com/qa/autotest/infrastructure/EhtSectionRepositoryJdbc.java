package com.qa.autotest.infrastructure;

import com.qa.autotest.application.report.CompareService;
import com.qa.autotest.application.report.EhtSections;
import com.qa.autotest.application.report.ReportViewRenderer;
import com.qa.autotest.application.repository.EhtSectionRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * v5.14.0 / W64-14：三段式报告只读装配。
 *
 * <p>四条 SQL 全部经 {@code run.project_id} 收口（{@link TenantGuard} 名单里是 run）；
 * {@code test_result} / {@code pytest_case_step} / {@code run_artifact} 都是 run 的子表，
 * 不单独做租户校验，与 ReportRepositoryJdbc 的既有口径一致。
 *
 * <p>所有对外字符串都过 {@link ReportViewRenderer#scrub}，长文本截断：
 * 报告是要导出给外部看的，不能把 IP / 凭据 / 绝对路径 / 整段 MEDIUMTEXT 原样带出去。
 */
@Repository
public class EhtSectionRepositoryJdbc implements EhtSectionRepository {

    private static final String ENV_SQL = """
            SELECT r.kernel,
                   CASE WHEN r.metadata_json IS NOT NULL AND JSON_VALID(r.metadata_json)
                        THEN JSON_UNQUOTE(JSON_EXTRACT(r.metadata_json, '$.COMMIT_SHA'))
                        ELSE NULL END AS commit_sha
              FROM run r
             WHERE r.project_id = ? AND r.run_id = ?
             LIMIT 1
            """;

    /** 四值互斥且穷尽：PASS / FAIL / MISSING / NULL。COUNT(*) 即用例总数。 */
    private static final String COUNTS_SQL = """
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(tr.criteria_verdict = 'PASS'), 0) AS passed,
                   COALESCE(SUM(tr.criteria_verdict = 'FAIL'), 0) AS failed,
                   COALESCE(SUM(tr.criteria_verdict = 'MISSING'), 0) AS missing_evidence,
                   COALESCE(SUM(tr.criteria_verdict IS NULL), 0) AS undetermined
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ? AND r.run_id = ?
            """;

    /** 未定项点名：优先 pytest 真实 nodeid，退化 case_key（与 test_result 同源）。 */
    private static final String UNDETERMINED_SQL = """
            SELECT COALESCE((SELECT p.node_id FROM pytest_case_step p
                              WHERE p.run_id = r.id AND p.case_key = tr.case_key
                              ORDER BY p.id ASC LIMIT 1),
                             NULLIF(tr.case_key, ''),
                             CONCAT('case#', tr.id)) AS node_ref,
                   COALESCE(tr.criteria_missing, '未声明判据') AS reason
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ? AND r.run_id = ?
               AND (tr.criteria_verdict IS NULL OR tr.criteria_verdict = 'MISSING')
             ORDER BY tr.id ASC
             LIMIT 20
            """;

    /** 证据层：判据原文、实测值、用例级 stdout 相对路径；无用例级时用 run 级产物兜底。 */
    private static final String EVIDENCE_SQL = """
            SELECT COALESCE((SELECT p.node_id FROM pytest_case_step p
                              WHERE p.run_id = r.id AND p.case_key = tr.case_key
                              ORDER BY p.id ASC LIMIT 1),
                             NULLIF(tr.case_key, ''),
                             CONCAT('case#', tr.id)) AS node_ref,
                   tr.criteria_json,
                   tr.failure_msg,
                   (SELECT p.stdout_path FROM pytest_case_step p
                     WHERE p.run_id = r.id AND p.case_key = tr.case_key
                       AND p.stdout_path IS NOT NULL
                     ORDER BY p.id ASC LIMIT 1) AS evidence_path,
                   (SELECT a.path FROM run_artifact a
                     WHERE a.run_id = r.id AND a.availability = 'ok'
                     ORDER BY a.id ASC LIMIT 1) AS fallback_path
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ? AND r.run_id = ?
             ORDER BY tr.id ASC
             LIMIT 50
            """;

    private final JdbcTemplate jdbc;
    private final CompareService compare;

    public EhtSectionRepositoryJdbc(JdbcTemplate jdbc, CompareService compare) {
        this.jdbc = jdbc;
        this.compare = compare;
    }

    @Override
    public EhtSections load(long projectId, String runId) {
        if (projectId <= 0 || runId == null || runId.isBlank()) {
            return EhtSections.empty("robot");
        }
        String kernel = "robot";
        String commitSha = null;
        try {
            TenantGuard.requireProjectScope(ENV_SQL, "run");
            List<Map<String, Object>> env = jdbc.queryForList(ENV_SQL, projectId, runId);
            if (!env.isEmpty()) {
                Object k = env.get(0).get("kernel");
                kernel = k == null ? "robot" : String.valueOf(k).trim().toLowerCase(Locale.ROOT);
                Object c = env.get(0).get("commit_sha");
                commitSha = c == null ? null : clip(String.valueOf(c), 80);
            }
        } catch (RuntimeException ex) {
            return EhtSections.empty("robot");
        }

        TenantGuard.requireProjectScope(COUNTS_SQL, "run");
        List<Map<String, Object>> rows = jdbc.queryForList(COUNTS_SQL, projectId, runId);
        EhtSections.Counts counts = rows.isEmpty() ? new EhtSections.Counts(0, 0, 0, 0, 0)
                : new EhtSections.Counts(num(rows.get(0).get("passed")), num(rows.get(0).get("failed")),
                        num(rows.get(0).get("missing_evidence")), num(rows.get(0).get("undetermined")),
                        num(rows.get(0).get("total")));

        TenantGuard.requireProjectScope(UNDETERMINED_SQL, "run");
        List<EhtSections.Undetermined> undetermined = jdbc.query(UNDETERMINED_SQL,
                (rs, n) -> new EhtSections.Undetermined(
                        clip(rs.getString("node_ref"), 160),
                        clip(rs.getString("reason"), 200)), projectId, runId);

        TenantGuard.requireProjectScope(EVIDENCE_SQL, "run");
        List<EhtSections.Evidence> evidence = jdbc.query(EVIDENCE_SQL,
                (rs, n) -> {
                    String path = EhtSections.relativePath(rs.getString("evidence_path"));
                    if (path == null || path.isBlank()) {
                        path = EhtSections.relativePath(rs.getString("fallback_path"));
                    }
                    return new EhtSections.Evidence(
                            clip(rs.getString("node_ref"), 160),
                            clip(rs.getString("criteria_json"), 200),
                            clip(rs.getString("failure_msg"), 200),
                            path == null ? null : clip(path, 512));
                }, projectId, runId);

        return new EhtSections(kernel, counts, undetermined, evidence, Map.of(),
                com.qa.autotest.domain.report.MetadataGate.REQUIRED, commitSha,
                diff(projectId, runId));
    }

    /**
     * 差异复用既有 {@link CompareService}（按 DUT/LINK/时间自动基线，R-26）。
     * 无基线时**不许**把「所有当前 FAIL」当成「新增失败」——那正是假差异，
     * 所以 baseline=null 一律返回 Diff.none()，界面显示「无上次执行」。
     * 权限上下文或历史数据异常时静默降级，报告主体不能因此建不出来。
     */
    private EhtSections.Diff diff(long projectId, String runId) {
        try {
            return EhtSections.Diff.from(compare.compare(projectId, runId, null));
        } catch (RuntimeException ex) {
            // 权限上下文或历史数据异常时静默降级，报告主体不能因此建不出来。
            return EhtSections.Diff.none();
        }
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** 脱敏 + 截断：报告是给外部看的，长文本与绝对路径都不许原样出去。 */
    private static String clip(String raw, int max) {
        String safe = ReportViewRenderer.scrub(raw == null ? "" : raw);
        return safe.length() > max ? safe.substring(0, max) + "…" : safe;
    }
}
