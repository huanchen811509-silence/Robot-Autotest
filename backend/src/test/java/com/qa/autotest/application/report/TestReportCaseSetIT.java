package com.qa.autotest.application.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.TestReport;
import com.qa.autotest.infrastructure.ReportRepositoryJdbc;
import com.qa.autotest.infrastructure.RunMetadataRepositoryJdbc;
import com.qa.autotest.infrastructure.TestReportRepositoryJdbc;
import com.qa.autotest.infrastructure.TestSetRepositoryJdbc;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * FR-v3.10-03：报告草稿的「用例集来源」必须连真库验（TC-v3.10-13/14）。
 *
 * <p><b>为什么必须连真库</b>：这条需求的全部风险都藏在 SQL 里——
 * ① 「最近一次执行」靠 {@code COALESCE(finished_at, started_at, enqueued_at)} 排序，
 *    用替身仓储怎么写都自洽，写错了只会安静地少给一条或指到上一条旧 run；
 * ② 「指定的 run 属于该集」靠一条件 {@code test_set_id = ?}，漏了就把别的集的 run 当成本集的；
 * ③ 「来源两列」靠 INSERT 与列投影对齐，写错列序不会抛异常，只会串值。
 * 这三类错误前端一个都看不出来（下拉照常、报告照建），所以在这里钉死。
 *
 * <p>连库方式与 {@code CompareBaselineIT} / {@code OverviewRepositoryIT} 一致（ADR-0002：不用 H2），
 * 库名必须以 {@code _it} 结尾，防止误清开发库。
 */
@Tag("it")
class TestReportCaseSetIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");
    private static final String WATERMARK = "INTERNAL USE ONLY";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportSnapshotService snapshots = new ReportSnapshotService(mapper);
    private final ReportViewRenderer renderer = new ReportViewRenderer(mapper);

    @Test
    @DisplayName("用例集来源：最近一次执行按 coalesce 时间取、指定 run 必须属于该集、来源两列落库、冻结快照自包含")
    void 用例集来源全链路() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        TestSetRepositoryJdbc caseSets = new TestSetRepositoryJdbc(jdbc);
        TestReportRepositoryJdbc reports = new TestReportRepositoryJdbc(jdbc);
        RunMetadataRepositoryJdbc metadata = new RunMetadataRepositoryJdbc(jdbc);
        ReportRepositoryJdbc runs = new ReportRepositoryJdbc(jdbc, mapper, metadata);

        String stamp = String.valueOf(System.currentTimeMillis());
        String projectCode = "IT-RSC-" + stamp;
        Long projectId = null;
        Long caseSetId = null;
        try {
            projectId = seedProject(jdbc, projectCode);
            caseSetId = seedCaseSet(jdbc, projectId, "TS-RSC-" + stamp);
            Long otherSetId = seedCaseSet(jdbc, projectId, "TS-RSC-OTHER-" + stamp);

            // 三条 run：旧的一次、新的一次、以及一条「还在跑」的（只有 started_at）
            String oldRun = "IT-RSC-OLD-" + stamp;
            String newRun = "IT-RSC-NEW-" + stamp;
            String runningRun = "IT-RSC-RUNNING-" + stamp;
            String foreignRun = "IT-RSC-FOREIGN-" + stamp;
            seedRun(jdbc, projectId, oldRun, caseSetId, "finished", "TIMESTAMPADD(DAY, -3, NOW(3))");
            seedRun(jdbc, projectId, newRun, caseSetId, "finished", "TIMESTAMPADD(HOUR, -2, NOW(3))");
            seedRun(jdbc, projectId, runningRun, caseSetId, "running", "NOW(3)");
            // 同项目、但属于另一个用例集：用来证明「指定 run 必须属于该集」
            seedRun(jdbc, projectId, foreignRun, otherSetId, "finished", "TIMESTAMPADD(MINUTE, -5, NOW(3))");
            seedMetadata(jdbc, projectId, runningRun);
            // 报告取的是 NEW 那条，元数据也必须种在它身上——种错行会让后面的
            // 「快照自包含」断言看起来通过（两边都空），实际什么也没验。
            seedMetadata(jdbc, projectId, newRun);

            // ---- ① 最近一次 = 还在跑的那条（coalesce 让它有 started_at 可比） ----
            assertThat(caseSets.latestRunId(projectId, caseSetId))
                    .as("运行中的 run 也要能当「最近一次」，否则会指向上一条已完成的旧 run")
                    .contains(runningRun);

            // 把 running 那条改成「1 小时前结束」：仍应是最新（NEW 是 2 小时前结束），
            // 这一步守的是 finished_at 一旦有值就必须优先于 started_at
            jdbc.update("UPDATE run SET run_status = 'finished', started_at = TIMESTAMPADD(HOUR, -2, NOW(3)), "
                    + "finished_at = TIMESTAMPADD(HOUR, -1, NOW(3)) WHERE project_id = ? AND run_id = ?",
                    projectId, runningRun);
            assertThat(caseSets.latestRunId(projectId, caseSetId))
                    .as("有 finished_at 时以它为准").contains(runningRun);

            // 再改成 5 天前结束：应回落到 NEW（-2 小时），而不是被 started_at 带偏
            jdbc.update("UPDATE run SET finished_at = TIMESTAMPADD(DAY, -5, NOW(3)) "
                    + "WHERE project_id = ? AND run_id = ?", projectId, runningRun);
            assertThat(caseSets.latestRunId(projectId, caseSetId))
                    .as("第二条更晚的 finished_at 应胜出").contains(newRun);

            // ---- ② 指定 run 必须属于该集 ----
            assertThat(caseSets.containsRun(projectId, caseSetId, newRun)).isTrue();
            assertThat(caseSets.containsRun(projectId, caseSetId, foreignRun))
                    .as("别的用例集的 run 不能被当成本集的").isFalse();

            // ---- ③ 来源两列落库并读回 ----
            ReportRecord record = runs.find(projectId, newRun).orElseThrow();
            ReportSnapshot draft = snapshots.build(record, 1, null);
            long reportId = reports.insert(projectId, newRun, ReportSourceType.CASE_SET, caseSetId,
                    "用例集报告 " + stamp, 1, draft);
            TestReport saved = reports.find(reportId).orElseThrow();
            assertThat(saved.sourceType()).isEqualTo(ReportSourceType.CASE_SET);
            assertThat(saved.caseSetId()).isEqualTo(caseSetId);
            assertThat(saved.runId()).isEqualTo(newRun);

            // 旧路径（不传来源）必须是 RUN / caseSetId=NULL——回填口径与 INSERT 默认值两处一致
            long runReportId = reports.insert(projectId, oldRun, ReportSourceType.RUN, null,
                    "按 run 报告 " + stamp, 1, snapshots.build(runs.find(projectId, oldRun).orElseThrow(), 1, null));
            TestReport runReport = reports.find(runReportId).orElseThrow();
            assertThat(runReport.sourceType()).isEqualTo(ReportSourceType.RUN);
            assertThat(runReport.caseSetId()).isNull();

            // 同一个 run 上可以有多份报告（按 run 建的 + 按用例集建的）：版本号必须按 run 全局累加，
            // 唯一键 uk_report_run_version 才不会被「本报告版本 + 1」这种分配方式撞上。
            reports.insert(projectId, newRun, ReportSourceType.RUN, null,
                    "同 run 第二份 " + stamp, 2, snapshots.build(record, 2, null));
            assertThat(reports.maxVersion(projectId, newRun))
                    .as("同 run 第二份报告占用 v2").isEqualTo(2);

            // ---- ④ 冻结快照自包含：源 run 消失后渲染结果不变 ----
            // 冻结版本号按 run 全局分配（与 TestReportService.freeze 同一口径）：
            // 上面已经占用了 v1（本报告）与 v2（同 run 第二份），这里必须是 v3。
            int freezeVersion = reports.maxVersion(projectId, newRun) + 1;
            ReportSnapshot frozen = snapshots.build(runs.find(projectId, newRun).orElseThrow(),
                    freezeVersion, null);
            reports.freeze(reportId, frozen);
            TestReport afterFreeze = reports.find(reportId).orElseThrow();
            assertThat(afterFreeze.version()).isEqualTo(freezeVersion);
            // 先证明这份快照里确实装了「这次执行」的事实，否则下面「删掉源数据后不变」
            // 只是因为两边都空，等于什么也没验。
            assertThat(afterFreeze.snapshotJson())
                    .as("快照必须记住源 run 与它的元数据（自包含的实质）")
                    .contains(newRun)
                    .contains("it-report-case-set");
            String before = renderJson(afterFreeze);
            assertThat(before).as("渲染结果不能是空壳").isNotEmpty();

            // 把源数据整条删掉（选报告「用例集来源」就是为了脱离某次执行的存续）
            jdbc.update("DELETE FROM run_metadata WHERE project_id = ? AND run_id = ?", projectId, newRun);
            jdbc.update("DELETE FROM run WHERE project_id = ? AND run_id = ?", projectId, newRun);
            assertThat(runs.find(projectId, newRun)).as("源 run 已删").isEmpty();

            String after = renderJson(reports.find(reportId).orElseThrow());
            assertThat(after)
                    .as("冻结后渲染只读 snapshot_json：源 run 被删也不能影响交付内容")
                    .isEqualTo(before);
        } finally {
            cleanup(jdbc, projectId);
        }
    }

    /** 渲染结果序列化成字符串比较：Map 的 equals 对 Double/Integer 宽松，比字符串更不可靠。 */
    private String renderJson(TestReport report) {
        ReportSnapshot snapshot = new ReportSnapshot(report.snapshotJson(), report.snapshotHtml(),
                report.snapshotHash(), report.version(), report.sourceSummary());
        Map<String, Object> view = renderer.external(snapshot, report.id(), report.title(),
                report.frozenAt() == null ? "" : report.frozenAt().toString(), WATERMARK);
        try {
            return mapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataSource dataSource() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        // 驱动是 MariaDB Java client（pom 无 MySQL Connector/J），前缀必须是 jdbc:mariadb://
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
        return new DriverManagerDataSource(url, user, password);
    }

    private static Long seedProject(JdbcTemplate jdbc, String code) {
        jdbc.update("INSERT INTO project (code, name, status) VALUES (?, ?, 1)",
                code, "FR-v3.10-03 用例集来源集成测试");
        return jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, code);
    }

    private static Long seedCaseSet(JdbcTemplate jdbc, Long projectId, String setCode) {
        jdbc.update("INSERT INTO test_set (project_id, set_code, name) VALUES (?, ?, ?)",
                projectId, setCode, "用例集来源 " + setCode);
        return jdbc.queryForObject(
                "SELECT id FROM test_set WHERE project_id = ? AND set_code = ?", Long.class,
                projectId, setCode);
    }

    /**
     * @param startedAtSql 直接拼进 SQL 的表达式（`NOW(3)` / `TIMESTAMPADD(...)`），
     *                     避免在 Java 侧造时间再被时区与毫秒精度扰动
     */
    private static void seedRun(JdbcTemplate jdbc, Long projectId, String runId, Long testSetId,
                                String state, String startedAtSql) {
        jdbc.update("INSERT INTO run (run_id, project_id, test_set_id, run_status, enqueued_at, "
                + "started_at, finished_at, total_tests, passed_tests, failed_tests, trigger_type) "
                + "VALUES (?, ?, ?, ?, " + startedAtSql + ", " + startedAtSql + ", "
                + (("finished".equals(state)) ? startedAtSql : "NULL") + ", 0, 0, 0, 'manual')",
                runId, projectId, testSetId, state);
    }

    /** 快照里的元数据来自 run_metadata；不种一条，就分不清「自包含」还是「两处都空」。 */
    private static void seedMetadata(JdbcTemplate jdbc, Long projectId, String runId) {
        jdbc.update("INSERT INTO run_metadata (project_id, run_id, meta_key, meta_value) "
                + "VALUES (?, ?, 'QA_PROJECT', 'it-report-case-set')", projectId, runId);
    }

    private static void cleanup(JdbcTemplate jdbc, Long projectId) {
        if (projectId == null) {
            return;
        }
        try {
            jdbc.update("DELETE FROM test_report WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM run_metadata WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM run WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM test_set WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM project WHERE id = ?", projectId);
        } catch (RuntimeException ex) {
            System.err.println("[TestReportCaseSetIT] 清理失败，残留 projectId=" + projectId
                    + "：" + ex.getMessage());
        }
    }
}
