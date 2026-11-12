package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.RunFailureBreakdownRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** run 级失败归因取数（W52-10）。 */
@Repository
public class RunFailureBreakdownRepositoryJdbc implements RunFailureBreakdownRepository {

    /**
     * 非 PASS 的逐行结果。
     *
     * <p>{@code tr.case_key} 可能为空（pytest 早期入库的行没有 case_key），此时用
     * {@code test_case.case_code}；两者都空才退化成空串，由上层标「（无编号）」——
     * 不要用 {@code CONCAT} 拼兜底串，那样会把「没有编号」显示成有编号。
     */
    private static final String ROW_SQL = """
            SELECT COALESCE(NULLIF(tc.case_code, ''), tr.case_key, '') AS case_code,
                   COALESCE(r.kernel, 'robot') AS kernel,
                   tr.verdict AS verdict,
                   COALESCE(tr.failure_msg, '') AS failure_msg
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
              LEFT JOIN test_case tc ON tc.id = tr.case_id
             WHERE r.project_id = ?
               AND r.run_id = ?
               AND tr.verdict <> 'PASS'
             ORDER BY tr.id
            """;

    /**
     * 计数。planned 把 PLANNED / DISPATCHED / PENDING 都算「计划未执行」：
     * 已派发但没回结果，与「没跑」在报告口径上是同一件事——都不能算通过，也不能算失败。
     */
    private static final String COUNT_SQL = """
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN tr.verdict = 'PASS' THEN 1 ELSE 0 END), 0) AS passed,
                   COALESCE(SUM(CASE WHEN tr.verdict IN ('PLANNED', 'DISPATCHED', 'PENDING')
                                     THEN 1 ELSE 0 END), 0) AS planned
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND r.run_id = ?
            """;

    private final JdbcTemplate jdbc;

    public RunFailureBreakdownRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> nonPassedRows(long projectId, String runId) {
        TenantGuard.requireProjectScope(ROW_SQL, "run");
        List<Row> rows = new ArrayList<>();
        jdbc.query(ROW_SQL, rs -> {
            rows.add(new Row(rs.getString("case_code"), rs.getString("kernel"),
                    rs.getString("verdict"), rs.getString("failure_msg")));
        }, projectId, runId);
        return List.copyOf(rows);
    }

    @Override
    public Counts counts(long projectId, String runId) {
        TenantGuard.requireProjectScope(COUNT_SQL, "run");
        return jdbc.query(COUNT_SQL, rs -> {
            if (!rs.next()) {
                return new Counts(0, 0, 0);
            }
            return new Counts(rs.getInt("total"), rs.getInt("passed"), rs.getInt("planned"));
        }, projectId, runId);
    }
}
