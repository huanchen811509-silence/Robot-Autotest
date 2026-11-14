package com.qa.autotest.application.report;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 报告 / 运行记录「展示层」生命周期（v5.7.0 / W57-31、33、34）。
 *
 * <p>U-07 已查实：本库没有独立的执行报告展示表。执行报告展示层就是 {@code run} 表
 * （ReportController 按 runId 取数），用户报告是 {@code test_report} 表。V11_63 给两张表
 * 补了 {@code remark / tags / deleted_at / deleted_by}（run 另有 {@code display_name}）。
 * 本服务只动这 7 个展示层列，{@code test_result / run_artifact / test_report_audit} 等
 * 原始事实表一律不碰。
 *
 * <p>三条口径：
 * <ul>
 *   <li><b>软删幂等</b>：重复删不报错，返回 {@code deleted=false}；恢复重复调用同样不报错；</li>
 *   <li><b>冻结保护</b>：{@code test_report.state='FROZEN'} 拒绝软删与改标题，提示「请先撤销冻结」；
 *       只改 remark / tags 放行（那是备注，不是交付件标题）；</li>
 *   <li><b>默认过滤</b>：对外展示查询一律带 {@code deleted_at IS NULL}；生命周期操作
 *       （删除幂等 / 恢复）必须能看到已删行，故 {@link #findRunRow} 不加该条件——
 *       过滤落在展示入口，见 {@link #countVisibleRuns} 与仓储层的 find/page。</li>
 * </ul>
 *
 * <p>所有 SQL 参数绑定（{@code ?}），不拼接用户输入；截断到列长度，避免超长报错。
 */
@Service
public class ReportLifecycleService {

    private static final String FROZEN = "FROZEN";

    private static final String SQL_FIND_RUN = """
            SELECT id, display_name, remark, tags, deleted_at
              FROM run WHERE project_id = ? AND run_id = ?
            """;

    private static final String SQL_FIND_REPORT = """
            SELECT id, run_id, title, remark, tags, state, deleted_at
              FROM test_report WHERE project_id = ? AND id = ?
            """;

    private static final String SQL_UPDATE_RUN = """
            UPDATE run SET display_name = COALESCE(?, display_name),
                           remark = COALESCE(?, remark),
                           tags = COALESCE(?, tags),
                           updated_at = NOW(3)
             WHERE project_id = ? AND run_id = ? AND deleted_at IS NULL
            """;

    private static final String SQL_SOFT_DELETE_RUN = """
            UPDATE run SET deleted_at = NOW(3), deleted_by = ?, updated_at = NOW(3)
             WHERE project_id = ? AND run_id = ? AND deleted_at IS NULL
            """;

    private static final String SQL_RESTORE_RUN = """
            UPDATE run SET deleted_at = NULL, deleted_by = NULL, updated_at = NOW(3)
             WHERE project_id = ? AND run_id = ? AND deleted_at IS NOT NULL
            """;

    private static final String SQL_UPDATE_REPORT = """
            UPDATE test_report SET title = COALESCE(?, title),
                                   remark = COALESCE(?, remark),
                                   tags = COALESCE(?, tags),
                                   updated_at = NOW(3)
             WHERE project_id = ? AND id = ? AND deleted_at IS NULL
            """;

    private static final String SQL_SOFT_DELETE_REPORT = """
            UPDATE test_report SET deleted_at = NOW(3), deleted_by = ?, updated_at = NOW(3)
             WHERE project_id = ? AND id = ? AND deleted_at IS NULL
            """;

    private static final String SQL_RESTORE_REPORT = """
            UPDATE test_report SET deleted_at = NULL, deleted_by = NULL, updated_at = NOW(3)
             WHERE project_id = ? AND id = ? AND deleted_at IS NOT NULL
            """;

    private static final String SQL_IMPACT_EXPORTS = """
            SELECT COUNT(*) AS c FROM test_report_export e
              JOIN test_report r ON r.id = e.report_id
             WHERE r.project_id = ? AND r.run_id = ? AND r.deleted_at IS NULL
            """;

    private static final String SQL_IMPACT_ARTIFACTS = """
            SELECT COUNT(*) AS c FROM run_artifact
             WHERE run_id = (SELECT id FROM run WHERE project_id = ? AND run_id = ?)
            """;

    private static final String SQL_COUNT_VISIBLE = """
            SELECT COUNT(*) AS c FROM run WHERE project_id = ? AND deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    /** 单一公开构造器（构造器注入，禁字段注入）。 */
    public ReportLifecycleService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- run

    /** 编辑 run 的展示层元数据；null 字段表示「不改」（COALESCE 保留原值）。 */
    public RunMeta updateRun(long projectId, String runId, RunUpdate update) {
        requireRunId(runId);
        Map<String, Object> row = requireRunRow(projectId, runId);
        if (isDeleted(row)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "运行记录已删除: " + runId);
        }
        RunUpdate value = update == null ? new RunUpdate(null, null, null) : update;
        execute(SQL_UPDATE_RUN, Arrays.asList(
                fit(value.displayName(), 200), fit(value.remark(), 1000), fit(value.tags(), 512),
                projectId, runId));
        audit.record("report.run.update", "run", runId,
                "displayName=" + flag(value.displayName()) + "; remark=" + flag(value.remark())
                        + "; tags=" + flag(value.tags()));
        return runMetaOf(runId, findRunRow(projectId, runId).orElse(row));
    }

    /** 软删 run；重复删幂等返回 deleted=false，不写第二条审计。 */
    public DeleteResult deleteRun(long projectId, String runId) {
        requireRunId(runId);
        Map<String, Object> row = requireRunRow(projectId, runId);
        RunImpact impact = impact(projectId, runId);
        if (isDeleted(row)) {
            return new DeleteResult(false, impact);
        }
        execute(SQL_SOFT_DELETE_RUN, Arrays.asList(TenantContext.userId(), projectId, runId));
        audit.record("report.run.delete", "run", runId, "softDeleted=true; impact=" + impact);
        return new DeleteResult(true, impact);
    }

    /** 恢复 run；未删除时幂等直接返回当前元数据。 */
    public RunMeta restoreRun(long projectId, String runId) {
        requireRunId(runId);
        Map<String, Object> row = requireRunRow(projectId, runId);
        if (!isDeleted(row)) {
            return runMetaOf(runId, row);
        }
        execute(SQL_RESTORE_RUN, Arrays.asList(projectId, runId));
        audit.record("report.run.restore", "run", runId, "restored=true");
        return runMetaOf(runId, findRunRow(projectId, runId).orElse(row));
    }

    // ---------------------------------------------------------------- test_report

    /** 编辑用户报告元数据；冻结件改标题一律拒绝。 */
    public TestReportMeta updateTestReport(long projectId, long id, TestReportUpdate update) {
        Map<String, Object> row = requireReportRow(projectId, id);
        if (isDeleted(row)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "测试报告已删除: " + id);
        }
        TestReportUpdate value = update == null ? new TestReportUpdate(null, null, null) : update;
        if (value.title() != null && isFrozen(row)) {
            throw new BusinessException(ErrorCode.CONFLICT, "请先撤销冻结");
        }
        execute(SQL_UPDATE_REPORT, Arrays.asList(
                fit(value.title(), 200), fit(value.remark(), 1000), fit(value.tags(), 512),
                projectId, id));
        audit.record("report.test.update", "test_report", String.valueOf(id),
                "title=" + flag(value.title()) + "; remark=" + flag(value.remark())
                        + "; tags=" + flag(value.tags()));
        return reportMetaOf(id, findReportRow(projectId, id).orElse(row));
    }

    /** 软删用户报告；冻结件拒绝删除，重复删幂等。 */
    public DeleteResult deleteTestReport(long projectId, long id) {
        Map<String, Object> row = requireReportRow(projectId, id);
        RunImpact impact = impact(projectId, str(row.get("run_id")));
        if (isDeleted(row)) {
            return new DeleteResult(false, impact);
        }
        if (isFrozen(row)) {
            throw new BusinessException(ErrorCode.CONFLICT, "请先撤销冻结");
        }
        execute(SQL_SOFT_DELETE_REPORT, Arrays.asList(TenantContext.userId(), projectId, id));
        audit.record("report.test.delete", "test_report", String.valueOf(id),
                "softDeleted=true; impact=" + impact);
        return new DeleteResult(true, impact);
    }

    /** 恢复用户报告；未删除时幂等直接返回当前元数据。 */
    public TestReportMeta restoreTestReport(long projectId, long id) {
        Map<String, Object> row = requireReportRow(projectId, id);
        if (!isDeleted(row)) {
            return reportMetaOf(id, row);
        }
        execute(SQL_RESTORE_REPORT, Arrays.asList(projectId, id));
        audit.record("report.test.restore", "test_report", String.valueOf(id), "restored=true");
        return reportMetaOf(id, findReportRow(projectId, id).orElse(row));
    }

    // ---------------------------------------------------------------- 影响面 / 默认过滤

    /** 删除影响面：关联 test_report_export 与 run_artifact 的条数（都只读，不改事实表）。 */
    public RunImpact impact(long projectId, String runId) {
        long exports = countOf(SQL_IMPACT_EXPORTS, Arrays.asList(projectId, runId));
        long artifacts = countOf(SQL_IMPACT_ARTIFACTS, Arrays.asList(projectId, runId));
        return new RunImpact(exports, artifacts);
    }

    /** 默认过滤示例 / 展示层计数：只算未软删的 run。 */
    public long countVisibleRuns(long projectId) {
        return countOf(SQL_COUNT_VISIBLE, Arrays.asList(projectId));
    }

    // ---------------------------------------------------------------- JDBC 通道（package-private，便于单测 spy 覆盖）

    List<Map<String, Object>> query(String sql, List<Object> args) {
        return jdbc.queryForList(sql, args.toArray());
    }

    int execute(String sql, List<Object> args) {
        return jdbc.update(sql, args.toArray());
    }

    Optional<Map<String, Object>> findRunRow(long projectId, String runId) {
        return query(SQL_FIND_RUN, Arrays.asList(projectId, runId)).stream().findFirst();
    }

    Optional<Map<String, Object>> findReportRow(long projectId, long id) {
        return query(SQL_FIND_REPORT, Arrays.asList(projectId, id)).stream().findFirst();
    }

    private Map<String, Object> requireRunRow(long projectId, String runId) {
        return findRunRow(projectId, runId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "运行记录不存在: " + runId));
    }

    private Map<String, Object> requireReportRow(long projectId, long id) {
        return findReportRow(projectId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "测试报告不存在: " + id));
    }

    private long countOf(String sql, List<Object> args) {
        List<Map<String, Object>> rows = query(sql, args);
        if (rows.isEmpty()) {
            return 0L;
        }
        Object value = rows.get(0).get("c");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 旧数据 deleted_at 为 NULL —— 视为可见（未删除）。 */
    static boolean isDeleted(Map<String, Object> row) {
        return row.get("deleted_at") != null;
    }

    static boolean isFrozen(Map<String, Object> row) {
        Object state = row.get("state");
        return state != null && FROZEN.equalsIgnoreCase(String.valueOf(state));
    }

    private static RunMeta runMetaOf(String runId, Map<String, Object> row) {
        return new RunMeta(runId, str(row.get("display_name")), str(row.get("remark")),
                str(row.get("tags")));
    }

    private static TestReportMeta reportMetaOf(long id, Map<String, Object> row) {
        return new TestReportMeta(id, str(row.get("title")), str(row.get("remark")),
                str(row.get("tags")), str(row.get("state")));
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }
    }

    /** null = 不改；非 null 截断到列长度，避免 Drizzle/MySQL 抛 DataTooLong。 */
    private static String fit(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 审计只记「改没改」，不记用户填写的正文，避免把备注内容带进审计摘要。 */
    private static String flag(String value) {
        return value == null ? "unchanged" : "set";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------- 视图 record

    public record RunUpdate(String displayName, String remark, String tags) {
    }

    public record TestReportUpdate(String title, String remark, String tags) {
    }

    public record RunMeta(String runId, String displayName, String remark, String tags) {
    }

    public record TestReportMeta(long id, String title, String remark, String tags, String state) {
    }

    public record RunImpact(long testReportExportCount, long runArtifactCount) {
    }

    public record DeleteResult(boolean deleted, RunImpact impact) {
    }
}
