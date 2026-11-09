package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.SearchRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 全局搜索 JDBC 实现。
 *
 * <p>LIKE 统一用 {@code !} 作为转义符：{@code %}/{@code _} 必须按字面匹配，
 * 否则用户输入 {@code %} 会变成全表扫描式的通配查询。
 */
@Repository
public class SearchRepositoryJdbc implements SearchRepository {

    private final JdbcTemplate jdbc;

    public SearchRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TypeResult search(long projectId, String query, String type, int limit) {
        String like = like(query);
        return switch (type) {
            case "case" -> searchCases(projectId, query, like, limit);
            case "run" -> searchRuns(projectId, like, limit, false);
            case "report" -> searchRuns(projectId, like, limit, true);
            case "plan" -> searchPlans(projectId, like, limit);
            case "asset" -> searchAssets(projectId, like, limit);
            default -> new TypeResult(List.of(), 0);
        };
    }

    private TypeResult searchCases(long projectId, String query, String like, int limit) {
        String where = "FROM test_case WHERE project_id = ? AND "
                + "(case_code LIKE ? ESCAPE '!' OR title LIKE ? ESCAPE '!')";
        String countSql = "SELECT COUNT(*) " + where;
        TenantGuard.requireProjectScope(countSql, "test_case");
        long total = count(countSql, projectId, like, like);
        if (limit <= 0) {
            return new TypeResult(List.of(), total);
        }
        String sql = "SELECT id, case_code, title, review_status, updated_at " + where
                + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "test_case");
        String path = "/cases?keyword=" + encode(query);
        List<Hit> hits = jdbc.query(sql, (rs, rowNum) -> new Hit(
                "case", rs.getLong("id"), rs.getString("title"),
                rs.getString("case_code"), projectId, instant(rs.getTimestamp("updated_at")),
                path, rs.getString("review_status")), projectId, like, like, limit);
        return new TypeResult(hits, total);
    }

    private TypeResult searchRuns(long projectId, String like, int limit, boolean reportsOnly) {
        String where = "FROM run WHERE project_id = ? AND run_id LIKE ? ESCAPE '!'"
                + (reportsOnly ? " AND run_status IN ('finished','failed','canceled')" : "");
        String countSql = "SELECT COUNT(*) " + where;
        TenantGuard.requireProjectScope(countSql, "run");
        long total = count(countSql, projectId, like);
        if (limit <= 0) {
            return new TypeResult(List.of(), total);
        }
        String sql = "SELECT run_id, run_status, COALESCE(finished_at, started_at, created_at) AS activity_at "
                + where + " ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "run");
        String type = reportsOnly ? "report" : "run";
        List<Hit> hits = jdbc.query(sql, (rs, rowNum) -> {
            String runId = rs.getString("run_id");
            String path = reportsOnly ? "/reports/" + encode(runId)
                    : "/runs?runId=" + encode(runId);
            return new Hit(type, 0, runId,
                    reportsOnly ? "运行结果" : rs.getString("run_status"),
                    projectId, instant(rs.getTimestamp("activity_at")), path,
                    rs.getString("run_status"));
        }, projectId, like, limit);
        return new TypeResult(hits, total);
    }

    private TypeResult searchPlans(long projectId, String like, int limit) {
        String where = "FROM execution_plan WHERE project_id = ? AND name LIKE ? ESCAPE '!'";
        String countSql = "SELECT COUNT(*) " + where;
        TenantGuard.requireProjectScope(countSql, "execution_plan");
        long total = count(countSql, projectId, like);
        if (limit <= 0) {
            return new TypeResult(List.of(), total);
        }
        String sql = "SELECT id, name, status, updated_at " + where
                + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "execution_plan");
        List<Hit> hits = jdbc.query(sql, (rs, rowNum) -> new Hit(
                "plan", rs.getLong("id"), rs.getString("name"),
                rs.getString("status"), projectId, instant(rs.getTimestamp("updated_at")),
                "/plans?id=" + rs.getLong("id"), rs.getString("status")),
                projectId, like, limit);
        return new TypeResult(hits, total);
    }

    private TypeResult searchAssets(long projectId, String like, int limit) {
        // 资产池里存的是各资产表的 id；名称与 alias 都可能被用户搜索，这里都匹配。
        String union = "SELECT 'DEVICE' AS asset_type, d.id AS asset_id, COALESCE(pa.alias, d.name) AS title,"
                + " d.name AS subtitle, d.updated_at AS activity_at"
                + " FROM project_asset pa JOIN device d ON pa.asset_type = 'DEVICE' AND d.id = pa.asset_id"
                + " WHERE pa.project_id = ? AND (d.name LIKE ? ESCAPE '!' OR pa.alias LIKE ? ESCAPE '!')"
                + " UNION ALL SELECT 'LINK', l.id, COALESCE(pa.alias, l.name), l.name, l.updated_at"
                + " FROM project_asset pa JOIN link l ON pa.asset_type = 'LINK' AND l.id = pa.asset_id"
                + " WHERE pa.project_id = ? AND (l.name LIKE ? ESCAPE '!' OR pa.alias LIKE ? ESCAPE '!')"
                + " UNION ALL SELECT 'CREDENTIAL', c.id, COALESCE(pa.alias, c.name), c.cred_type, c.updated_at"
                + " FROM project_asset pa JOIN credential c ON pa.asset_type = 'CREDENTIAL' AND c.id = pa.asset_id"
                + " WHERE pa.project_id = ? AND (c.name LIKE ? ESCAPE '!' OR pa.alias LIKE ? ESCAPE '!')";
        String countSql = "SELECT COUNT(*) FROM (" + union + ") matched";
        TenantGuard.requireProjectScope(countSql, "project_asset");
        long total = count(countSql, projectId, like, like, projectId, like, like,
                projectId, like, like);
        if (limit <= 0) {
            return new TypeResult(List.of(), total);
        }
        String sql = union + " ORDER BY activity_at DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "project_asset");
        List<Hit> hits = jdbc.query(sql, (rs, rowNum) -> new Hit(
                "asset", rs.getLong("asset_id"), rs.getString("title"),
                rs.getString("asset_type"), projectId, instant(rs.getTimestamp("activity_at")),
                "/assets?type=" + rs.getString("asset_type") + "&id=" + rs.getLong("asset_id"),
                rs.getString("asset_type")), projectId, like, like, projectId, like, like,
                projectId, like, like, limit);
        return new TypeResult(hits, total);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 把 {@code %}、{@code _}、转义符 {@code !} 变成字面量。 */
    static String like(String raw) {
        String value = raw == null ? "" : raw;
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** 路径参数编码：只做最小转义，避免空格与斜杠破坏 URL。 */
    static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
