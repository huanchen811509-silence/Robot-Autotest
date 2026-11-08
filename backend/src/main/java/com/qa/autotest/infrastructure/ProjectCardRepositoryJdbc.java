package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ProjectCardRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 项目卡牌聚合仓储 JDBC 实现（PRD v2.3 FR-UI-09，UI-02）。
 *
 * <p><b>一条 SQL 查完整批项目</b>：五张表各自先按 {@code project_id} 分组再 LEFT JOIN 到
 * {@code project}，外层只用 {@code p.id IN (...)} 圈住调用方给的项目集合。这样「N 个项目」与
 * 「1 个项目」的查询次数都是 1，不会随项目数线性增长（FR-UI-14 要求卡牌页只发 1 次请求）。
 *
 * <p>口径全部在 SQL 里显式写出，避免「看代码才知道」：
 * <ul>
 *   <li>窗口比较用 {@code COALESCE(started_at, created_at)}：老数据可能没写 {@code started_at}，
 *       直接比会让它们凭空消失在窗口外；</li>
 *   <li>{@code runCount} 不限状态——取消/失败的运行也是「跑过」；</li>
 *   <li>通过率只取 {@code run_status = 'finished'}：失败的 run 可能只跑了一半，
 *       放进分母会把通过率压低；</li>
 *   <li>{@code last_run_at} 取全量而非窗口内——卡牌要回答「最近什么时候跑过」，
 *       哪怕那是 3 个月前。</li>
 * </ul>
 *
 * <p>{@code test_case} / {@code run} / {@code device} / {@code project_member} 都是租户表，
 * SQL 整体带 {@code project_id} 条件并过 {@link TenantGuard}。
 */
@Repository
public class ProjectCardRepositoryJdbc implements ProjectCardRepository {

    /**
     * {@code %s} 处填 {@code IN} 的占位符列表。参数顺序必须与 SQL 文本中出现顺序一致：
     * 三个窗口起点 → 当前用户 id → 项目 id 列表。
     */
    private static final String AGGREGATE = """
            SELECT p.id AS project_id,
                   COALESCE(tc.cnt, 0) AS case_count,
                   COALESCE(rn.cnt, 0) AS run_count,
                   COALESCE(rn.total_tests, 0) AS finished_total_tests,
                   COALESCE(rn.passed_tests, 0) AS finished_passed_tests,
                   rn.last_run_at,
                   COALESCE(dv.cnt, 0) AS device_count,
                   COALESCE(mc.cnt, 0) AS member_count,
                   me.member_role
              FROM project p
              LEFT JOIN (SELECT project_id, COUNT(*) AS cnt
                           FROM test_case GROUP BY project_id) tc
                     ON tc.project_id = p.id
              LEFT JOIN (SELECT project_id,
                                SUM(CASE WHEN COALESCE(started_at, created_at) >= ? THEN 1 ELSE 0 END) AS cnt,
                                SUM(CASE WHEN COALESCE(started_at, created_at) >= ?
                                          AND run_status = 'finished' THEN total_tests ELSE 0 END) AS total_tests,
                                SUM(CASE WHEN COALESCE(started_at, created_at) >= ?
                                          AND run_status = 'finished' THEN passed_tests ELSE 0 END) AS passed_tests,
                                MAX(COALESCE(started_at, created_at)) AS last_run_at
                           FROM run GROUP BY project_id) rn
                     ON rn.project_id = p.id
              LEFT JOIN (SELECT project_id, COUNT(*) AS cnt
                           FROM device GROUP BY project_id) dv
                     ON dv.project_id = p.id
              LEFT JOIN (SELECT project_id, COUNT(*) AS cnt
                           FROM project_member GROUP BY project_id) mc
                     ON mc.project_id = p.id
              LEFT JOIN (SELECT project_id, member_role
                           FROM project_member WHERE user_id = ?) me
                     ON me.project_id = p.id
             WHERE p.id IN (%s)
            """;

    private final JdbcTemplate jdbc;

    public ProjectCardRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, ProjectCardAggregate> aggregate(Collection<Long> projectIds, Instant windowStart,
                                                     long viewerUserId) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(projectIds.size(), "?"));
        String sql = AGGREGATE.formatted(placeholders);
        TenantGuard.requireProjectScope(sql, "project");

        Timestamp from = Timestamp.from(windowStart);
        Object[] args = new Object[4 + projectIds.size()];
        args[0] = from;
        args[1] = from;
        args[2] = from;
        args[3] = viewerUserId;
        int index = 4;
        for (Long projectId : projectIds) {
            args[index++] = projectId;
        }

        Map<Long, ProjectCardAggregate> rows = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            long projectId = rs.getLong("project_id");
            rows.put(projectId, new ProjectCardAggregate(
                    rs.getLong("case_count"),
                    rs.getLong("run_count"),
                    rs.getLong("finished_total_tests"),
                    rs.getLong("finished_passed_tests"),
                    JdbcSupport.instant(rs.getTimestamp("last_run_at")),
                    rs.getLong("device_count"),
                    rs.getLong("member_count"),
                    rs.getString("member_role")));
        }, args);
        return rows;
    }
}
