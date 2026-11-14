package com.qa.autotest.infrastructure;

import com.qa.autotest.application.execution.CriteriaIngestService;
import com.qa.autotest.application.execution.RunConfirmationService;
import com.qa.autotest.application.execution.SelectionResolver;
import com.qa.autotest.application.execution.SelectionSnapshotService;
import com.qa.autotest.common.tenant.TenantContext;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * v5.7.0「执行可信度 + 选择快照」持久化适配器：实现 4 个应用层 Store 接口。
 *
 * <p><b>租户口径</b>：每条 SELECT / UPDATE 都带 {@code project_id = ?}（INSERT 落 project_id 列），
 * 且服务层入口先 {@code access.bindProject} + {@code access.require}，绝不绕过租户过滤。
 * <b>SQL 口径</b>：值一律 {@code ?} 参数绑定；动态 IN 只拼占位符，动态筛选只拼
 * {@link #FILTER_COLUMNS} 白名单里的固定列名，绝不拼接用户输入。
 */
@Repository
public class RunTrustJdbcStore implements
        RunConfirmationService.ExecutionPlanStore,
        SelectionSnapshotService.SnapshotStore,
        SelectionResolver.CaseScopeQuery,
        CriteriaIngestService.ResultStore {

    /** 允许出现在 test_case 查询里的筛选键 → 固定列名（白名单，非用户输入）。 */
    private static final Map<String, String> FILTER_COLUMNS = Map.of(
            "domain", "domain_tag",
            "priority", "priority",
            "status", "status",
            "caseKind", "case_kind");

    private static final RowMapper<SelectionSnapshotService.SnapshotRow> SNAPSHOT_MAPPER =
            (rs, n) -> new SelectionSnapshotService.SnapshotRow(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getString("selection_sha"),
                    rs.getString("selection_mode"),
                    rs.getString("set_ids_json"),
                    rs.getString("case_ids_json"),
                    rs.getString("filter_json"),
                    rs.getInt("case_count"),
                    instant(rs.getTimestamp("consumed_at")),
                    instant(rs.getTimestamp("created_at")));

    private static final RowMapper<CriteriaIngestService.CriteriaRow> CRITERIA_MAPPER =
            (rs, n) -> new CriteriaIngestService.CriteriaRow(
                    rs.getLong("case_id"),
                    rs.getString("case_code"),
                    rs.getString("verdict"),
                    com.qa.autotest.application.execution.CaseConclusionResolver
                            .normalizeStatus(rs.getString("run_status")),
                    rs.getString("criteria_json"),
                    rs.getString("criteria_verdict"),
                    rs.getString("criteria_missing"));

    private final JdbcTemplate jdbc;

    public RunTrustJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- RunConfirmationService.ExecutionPlanStore ----------

    @Override
    public boolean exists(long projectId, long planId) {
        String sql = "SELECT 1 FROM execution_plan WHERE id = ? AND project_id = ?";
        return !jdbc.queryForList(sql, Integer.class, planId, projectId).isEmpty();
    }

    @Override
    public void updateConfirmation(long projectId, long planId, String digest, Instant at, Long by) {
        Timestamp now = Timestamp.from(at);
        jdbc.update("UPDATE execution_plan SET confirmed_digest = ?, confirmed_at = ?, "
                        + "confirmed_by = ?, updated_at = ?, updated_by = ? "
                        + "WHERE id = ? AND project_id = ?",
                digest, now, by, now, by, planId, projectId);
    }

    // ---------- SelectionSnapshotService.SnapshotStore ----------

    private static final String SNAPSHOT_COLUMNS =
            "id, project_id, selection_sha, selection_mode, set_ids_json, case_ids_json, "
                    + "filter_json, case_count, consumed_at, created_at";

    @Override
    public Optional<SelectionSnapshotService.SnapshotRow> findBySha(long projectId, String sha) {
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM execution_selection_snapshot "
                + "WHERE project_id = ? AND selection_sha = ?";
        return first(jdbc.query(sql, SNAPSHOT_MAPPER, projectId, sha));
    }

    @Override
    public Optional<SelectionSnapshotService.SnapshotRow> findById(long projectId, long id) {
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM execution_selection_snapshot "
                + "WHERE project_id = ? AND id = ?";
        return first(jdbc.query(sql, SNAPSHOT_MAPPER, projectId, id));
    }

    @Override
    public long insert(SelectionSnapshotService.SnapshotRow draft) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO execution_selection_snapshot
                      (project_id, selection_sha, selection_mode, set_ids_json, case_ids_json,
                       filter_json, case_count, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, draft.projectId());
            ps.setString(2, draft.selectionSha());
            ps.setString(3, draft.mode());
            ps.setString(4, draft.setIdsJson());
            ps.setString(5, draft.caseIdsJson());
            ps.setString(6, draft.filterJson());
            ps.setInt(7, draft.caseCount());
            Long by = TenantContext.userId();
            if (by == null) {
                ps.setNull(8, Types.BIGINT);
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(8, by);
                ps.setLong(9, by);
            }
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    @Override
    public int markConsumed(long projectId, long id, Instant at, Long by) {
        Timestamp now = Timestamp.from(at);
        return jdbc.update("UPDATE execution_selection_snapshot "
                        + "SET consumed_at = ?, consumed_by = ?, updated_at = ?, updated_by = ? "
                        + "WHERE project_id = ? AND id = ? AND consumed_at IS NULL",
                now, by, now, by, projectId, id);
    }

    // ---------- SelectionResolver.CaseScopeQuery ----------

    @Override
    public List<Long> allCaseIds(long projectId, Map<String, Object> filter) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT id" + scopeWhere(projectId, filter, args) + " ORDER BY id";
        return jdbc.queryForList(sql, Long.class, args.toArray());
    }

    @Override
    public List<Long> caseIdsOfSets(long projectId, List<Long> setIds) {
        List<Long> ids = cleanIds(setIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);   // test_set.project_id
        args.add(projectId);   // test_case.project_id
        args.addAll(ids);
        String sql = "SELECT DISTINCT tsi.test_case_id FROM test_set_item tsi "
                + "JOIN test_set ts ON ts.id = tsi.test_set_id AND ts.project_id = ? "
                + "JOIN test_case tc ON tc.id = tsi.test_case_id AND tc.project_id = ? "
                + "WHERE tsi.test_set_id IN (" + marks + ") ORDER BY tsi.test_case_id";
        return jdbc.queryForList(sql, Long.class, args.toArray());
    }

    @Override
    public List<Long> retainProjectCaseIds(long projectId, List<Long> caseIds) {
        List<Long> ids = cleanIds(caseIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(ids);
        String sql = "SELECT id FROM test_case WHERE project_id = ? "
                + "AND id IN (" + marks + ") ORDER BY id";
        return jdbc.queryForList(sql, Long.class, args.toArray());
    }

    @Override
    public Map<Long, String> caseCodes(long projectId, List<Long> ids) {
        List<Long> clean = cleanIds(ids);
        if (clean.isEmpty()) {
            return Map.of();
        }
        String marks = String.join(",", Collections.nCopies(clean.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(clean);
        String sql = "SELECT id, case_code FROM test_case WHERE project_id = ? "
                + "AND id IN (" + marks + ")";
        Map<Long, String> result = new LinkedHashMap<>();
        List<Map.Entry<Long, String>> rows = jdbc.query(sql,
                (rs, n) -> Map.entry(rs.getLong("id"), rs.getString("case_code")),
                args.toArray());
        for (Map.Entry<Long, String> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    @Override
    public long count(long projectId, Map<String, Object> filter) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + scopeWhere(projectId, filter, args);
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    // ---------- CriteriaIngestService.ResultStore ----------

    @Override
    public void updateCriteria(long projectId, String runId, long caseId,
                               String criteriaJson, String verdict, String missing) {
        jdbc.update("UPDATE test_result SET criteria_json = ?, criteria_verdict = ?, "
                        + "criteria_missing = ?, updated_at = ? "
                        + "WHERE case_id = ? AND run_id = "
                        + "(SELECT id FROM run WHERE project_id = ? AND run_id = ?)",
                criteriaJson, verdict, missing, Timestamp.from(Instant.now()),
                caseId, projectId, runId);
    }

    @Override
    public List<CriteriaIngestService.CriteriaRow> listCriteria(long projectId, String runId) {
        String sql = "SELECT tr.case_id, tc.case_code, tr.verdict AS verdict, r.run_status, "
                + "tr.criteria_json, tr.criteria_verdict, tr.criteria_missing "
                + "FROM test_result tr "
                + "JOIN run r ON r.id = tr.run_id "
                + "LEFT JOIN test_case tc ON tc.id = tr.case_id "
                + "WHERE r.project_id = ? AND r.run_id = ? "
                + "ORDER BY tr.case_id, tr.round_no";
        return jdbc.query(sql, CRITERIA_MAPPER, projectId, runId);
    }

    // ---------- 辅助 ----------

    /** 只拼固定列名（白名单）与 {@code ?} 占位符，值全部走参数绑定。 */
    private static String scopeWhere(long projectId, Map<String, Object> filter,
                                     List<Object> args) {
        StringBuilder sql = new StringBuilder(" FROM test_case WHERE project_id = ?");
        args.add(projectId);
        if (filter == null || filter.isEmpty()) {
            return sql.toString();
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String column = FILTER_COLUMNS.get(entry.getKey());
            Object value = entry.getValue();
            if (column != null && value != null && !String.valueOf(value).isBlank()) {
                sql.append(" AND ").append(column).append(" = ?");
                args.add(String.valueOf(value));
            }
        }
        return sql.toString();
    }

    private static List<Long> cleanIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows == null || rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
