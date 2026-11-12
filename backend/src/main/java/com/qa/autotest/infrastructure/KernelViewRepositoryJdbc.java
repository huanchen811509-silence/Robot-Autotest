package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.KernelViewRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 双内核统一视图取数（`run` × `test_result`，按内核分组）。 */
@Repository
public class KernelViewRepositoryJdbc implements KernelViewRepository {

    /**
     * run 级统计。
     *
     * <p>与用例统计分开查：一条 run 对多条 test_result，join 起来再 COUNT 会把 run 数
     * 放大成「用例数」。两个子查询各自聚合再在应用层对齐，虽然多一次往返，
     * 但不会有「2 条 run 显示成 240 条」这种一眼看不出错的数。
     */
    private static final String RUN_SQL = """
            SELECT COALESCE(r.kernel, 'robot') AS kernel,
                   COUNT(*) AS runs,
                   SUM(CASE WHEN r.run_status = 'FINISHED' THEN 1 ELSE 0 END) AS finished,
                   SUM(CASE WHEN r.run_status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                   COALESCE(AVG(CASE WHEN r.started_at IS NOT NULL AND r.finished_at IS NOT NULL
                                     THEN TIMESTAMPDIFF(SECOND, r.started_at, r.finished_at)
                                     ELSE NULL END), 0) AS avg_seconds
              FROM run r
             WHERE r.project_id = ?
               AND r.created_at >= ?
               AND COALESCE(r.kernel, 'robot') <> 'manual'
             GROUP BY COALESCE(r.kernel, 'robot')
            """;

    private static final String CASE_SQL = """
            SELECT COALESCE(r.kernel, 'robot') AS kernel,
                   COUNT(*) AS cases,
                   SUM(CASE WHEN tr.verdict = 'PASS' THEN 1 ELSE 0 END) AS passed_cases
              FROM test_result tr
              JOIN run r ON r.id = tr.run_id
             WHERE r.project_id = ?
               AND tr.created_at >= ?
               AND tr.verdict IN ('PASS', 'FAIL')
               AND COALESCE(r.kernel, 'robot') <> 'manual'
             GROUP BY COALESCE(r.kernel, 'robot')
            """;

    private final JdbcTemplate jdbc;

    public KernelViewRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<KernelLane> lanes(long projectId, Instant since) {
        TenantGuard.requireProjectScope(RUN_SQL, "run");
        Map<String, int[]> runStats = new LinkedHashMap<>();
        Map<String, Long> avgSeconds = new LinkedHashMap<>();
        jdbc.query(RUN_SQL, rs -> {
            String kernel = rs.getString("kernel");
            runStats.put(kernel, new int[] {rs.getInt("runs"), rs.getInt("finished"),
                    rs.getInt("failed")});
            avgSeconds.put(kernel, Math.round(rs.getDouble("avg_seconds")));
        }, projectId, Timestamp.from(since));

        Map<String, int[]> caseStats = new LinkedHashMap<>();
        jdbc.query(CASE_SQL, rs -> {
            caseStats.put(rs.getString("kernel"),
                    new int[] {rs.getInt("cases"), rs.getInt("passed_cases")});
        }, projectId, Timestamp.from(since));

        // 两个来源的键取并集：只有 run 没有结果的（刚失败在产物阶段）也要出现在视图里
        List<String> kernels = new ArrayList<>(runStats.keySet());
        for (String kernel : caseStats.keySet()) {
            if (!kernels.contains(kernel)) {
                kernels.add(kernel);
            }
        }
        List<KernelLane> lanes = new ArrayList<>();
        for (String kernel : kernels) {
            int[] run = runStats.getOrDefault(kernel, new int[] {0, 0, 0});
            int[] cases = caseStats.getOrDefault(kernel, new int[] {0, 0});
            lanes.add(new KernelLane(kernel, run[0], run[1], run[2], cases[0], cases[1],
                    avgSeconds.getOrDefault(kernel, 0L)));
        }
        lanes.sort((a, b) -> a.kernel().compareTo(b.kernel()));
        return List.copyOf(lanes);
    }
}
