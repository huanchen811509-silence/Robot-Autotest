package com.qa.autotest.application.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.overview.ProjectOverview;
import com.qa.autotest.infrastructure.OverviewRepositoryJdbc;
import com.qa.autotest.infrastructure.ProjectRepositoryJdbc;
import com.qa.autotest.infrastructure.UserRepositoryJdbc;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 总览聚合与真库的一致性测试（TC-OVW-01~05），必须连真实 MySQL。
 *
 * <p><b>为什么必须连真库</b>：总览的全部价值在于「数字对」。替身仓储只能证明 Java 代码把假数据
 * 搬对了位置，证明不了 SQL 的 GROUP BY、join 与租户条件写对——而口径错误恰恰不会报错，
 * 只会安静地显示一个偏小的数。所以本类直接对 {@code robot_it} 造数、跑真 SQL、再与
 * 「直接 COUNT」逐项对照。
 *
 * <p>连库方式与 {@code CaseImportIT} / {@code SchemaIT} 一致（ADR-0002：不用 H2）；
 * 库名必须以 {@code _it} 结尾，防止误清开发库。
 *
 * <p>不做的事：不验前端渲染（那是 CDP 截图的事）、不验并发刷新（只读聚合，无意义）。
 */
@Tag("it")
class OverviewRepositoryIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");
    private static final int WINDOW_DAYS = 30;

    @Test
    @DisplayName("真库造数：用例/资产/执行三类指标与直接 SQL 逐项一致，空项目不炸，跨项目 403")
    void 总览指标与真库一致() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        OverviewService service = new OverviewService(
                new OverviewRepositoryJdbc(jdbc),
                new AccessControlService(new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc)));

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-ovw-" + stamp;
        String projectCode = "IT-OVW-" + stamp;
        String emptyCode = "IT-OVW-EMPTY-" + stamp;
        String otherCode = "IT-OVW-OTHER-" + stamp;
        Long userId = null;
        Long projectId = null;
        Long emptyProjectId = null;
        Long otherProjectId = null;
        try {
            userId = seedUser(jdbc, username);
            // 三个项目各司其职：有数据 / 空数据但我是成员 / 我完全不是成员
            projectId = seedProject(jdbc, projectCode, userId);
            emptyProjectId = seedProject(jdbc, emptyCode, userId);
            otherProjectId = seedProject(jdbc, otherCode, null);
            seedMember(jdbc, projectId, userId);
            seedMember(jdbc, emptyProjectId, userId);
            TenantContext.set(userId, username, null);

            // ---- 造数：5 用例（P0 2 / P1 3；draft 3 / approved 2；domain 两组）----
            seedCase(jdbc, projectId, "OVW-001", "P0", "draft", "rdma");
            seedCase(jdbc, projectId, "OVW-002", "P0", "approved", "rdma");
            seedCase(jdbc, projectId, "OVW-003", "P1", "draft", "iperf");
            seedCase(jdbc, projectId, "OVW-004", "P1", "approved", "iperf");
            seedCase(jdbc, projectId, "OVW-005", "P1", "draft", "iperf");
            // v5.35.0：V11_87 已删除 test_case.status（死字段，只在 INSERT 写死 1），
            // 「停用用例不计入」的前提不存在了，这里不再造那条数据。

            // ---- 资产：3 设备（1 台停用）、2 端口、2 链路（1 条停用）、2 凭据、1 环境、1 用例集 ----
            Long dev1 = seedDevice(jdbc, projectId, "GPU-01", 1);
            Long dev2 = seedDevice(jdbc, projectId, "GPU-02", 1);
            seedDevice(jdbc, projectId, "GPU-OFF", 0);
            seedPort(jdbc, dev1, "mlx5_0");
            seedPort(jdbc, dev1, "mlx5_1");
            seedLink(jdbc, projectId, "GPU-01/mlx5_0 -> DUT-01/mlx5_0", 1);
            seedLink(jdbc, projectId, "旧链路", 0);
            seedCredential(jdbc, projectId, "ssh-dut");
            seedCredential(jdbc, projectId, "ssh-switch");
            jdbc.update("INSERT INTO env_profile (project_id, name) VALUES (?, 'OFED 24.10')", projectId);
            jdbc.update("INSERT INTO test_set (project_id, set_code, name) VALUES (?, 'TS-1', 'RDMA 冒烟')",
                    projectId);
            Long testSetId = jdbc.queryForObject(
                    "SELECT id FROM test_set WHERE project_id = ? AND set_code = 'TS-1'",
                    Long.class, projectId);

            // ---- 执行：1 finished（3 PASS / 1 FAIL）+ 1 canceled + 1 running ----
            Long finishedRun = seedRun(jdbc, projectId, "IT-RUN-FIN-" + stamp, "finished",
                    "GPU-01", "GPU-01/mlx5_0", 4, testSetId);
            jdbc.update("INSERT INTO test_result (run_id, case_key, verdict, duration_ms) VALUES (?, 'S.A', 'PASS', 100)",
                    finishedRun);
            jdbc.update("INSERT INTO test_result (run_id, case_key, verdict, duration_ms) VALUES (?, 'S.B', 'PASS', 200)",
                    finishedRun);
            jdbc.update("INSERT INTO test_result (run_id, case_key, verdict, duration_ms) VALUES (?, 'S.C', 'PASS', 300)",
                    finishedRun);
            jdbc.update("INSERT INTO test_result (run_id, case_key, verdict, duration_ms) VALUES (?, 'S.D', 'FAIL', 400)",
                    finishedRun);
            seedRun(jdbc, projectId, "IT-RUN-CAN-" + stamp, "canceled",
                    "GPU-01", "GPU-01/mlx5_0", 4, null);
            seedRun(jdbc, projectId, "IT-RUN-RUN-" + stamp, "running",
                    "GPU-01", "GPU-01/mlx5_0", 4, null);

            // ---- 调用：默认参数 ----
            ProjectOverview view = service.load(projectId, 8, WINDOW_DAYS);

            // 用例
            assertThat(view.cases().total()).as("用例总数（5 条）").isEqualTo(5);
            assertThat(pairs(view.cases().byPriority()))
                    .as("优先级分布")
                    .containsExactlyInAnyOrder("P0=2", "P1=3");
            assertThat(pairs(view.cases().byReviewStatus()))
                    .as("评审状态分布")
                    .containsExactlyInAnyOrder("draft=3", "approved=2");
            assertThat(pairs(view.cases().topDomains()))
                    .as("技术域 Top N")
                    .containsExactlyInAnyOrder("iperf=3", "rdma=2");

            // 资产：停用的设备/链路不计（口径：status = 1）
            assertThat(view.assets().devices()).as("3 台里 1 台停用").isEqualTo(2);
            assertThat(view.assets().ports()).as("2 个端口都挂在在用设备上").isEqualTo(2);
            assertThat(view.assets().links()).as("2 条里 1 条停用").isEqualTo(1);
            assertThat(view.assets().credentials()).isEqualTo(2);
            assertThat(view.assets().environments()).isEqualTo(1);
            assertThat(view.assets().testSets()).isEqualTo(1);

            // 执行
            assertThat(view.runs().total()).isEqualTo(3);
            assertThat(view.runs().inWindow()).isEqualTo(3);
            assertThat(view.runs().finished()).isEqualTo(1);
            assertThat(view.runs().canceled()).isEqualTo(1);
            assertThat(view.runs().running()).isEqualTo(1);
            assertThat(view.runs().casePass()).isEqualTo(3);
            assertThat(view.runs().caseFail()).isEqualTo(1);
            assertThat(view.runs().passRateBp()).as("3 过 1 挂 = 7500 基点").isEqualTo(7500);
            assertThat(view.runs().avgDurationMs()).as("finished run 的耗时样本").isNotNull();

            // 近期执行：3 条，完成的那条通过率 7500
            assertThat(view.recentRuns()).hasSize(3);
            ProjectOverview.RecentRun finishedRow = view.recentRuns().stream()
                    .filter(row -> "finished".equals(row.state()))
                    .findFirst().orElseThrow();
            assertThat(finishedRow.passRateBp()).isEqualTo(7500);
            assertThat(finishedRow.dut()).isEqualTo("GPU-01");
            assertThat(finishedRow.testSetName()).isEqualTo("RDMA 冒烟");

            // 趋势：恰好 30 个连续点，今天有 3 条运行
            assertThat(view.dailyTrend()).hasSize(WINDOW_DAYS);
            assertThat(view.dailyTrend().get(WINDOW_DAYS - 1).finished()).isEqualTo(1);
            assertThat(view.dailyTrend().get(WINDOW_DAYS - 1).canceled()).isEqualTo(1);

            // ---- 与「直接 SQL」对照，证明接口不是自己编的数 ----
            Integer dbCases = jdbc.queryForObject(
                    // V11_87 已删除 test_case.status；生产的用例计数本来也不按它过滤
                    "SELECT COUNT(*) FROM test_case WHERE project_id = ?",
                    Integer.class, projectId);
            Integer dbPorts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device_port p JOIN device d ON d.id = p.device_id WHERE d.project_id = ?",
                    Integer.class, projectId);
            assertThat(view.cases().total()).isEqualTo(dbCases);
            assertThat(view.assets().ports()).isEqualTo(dbPorts);

            // ---- 空项目：不报错、通过率为 null（不是 0）----
            ProjectOverview empty = service.load(emptyProjectId, 8, WINDOW_DAYS);
            assertThat(empty.cases().total()).isZero();
            assertThat(empty.runs().total()).isZero();
            assertThat(empty.runs().passRateBp()).as("无样本必须是 null").isNull();
            assertThat(empty.runs().avgDurationMs()).isNull();
            assertThat(empty.recentRuns()).isEmpty();
            assertThat(empty.dailyTrend()).hasSize(WINDOW_DAYS);

            // ---- 参数越界：夹到边界而不是 400 ----
            ProjectOverview clampedLow = service.load(projectId, 0, 0);
            assertThat(clampedLow.recentRuns()).as("recent=0 夹到 1").hasSize(1);
            assertThat(clampedLow.dailyTrend()).as("days=0 夹到 1").hasSize(1);
            ProjectOverview clampedHigh = service.load(projectId, 999, 999);
            assertThat(clampedHigh.recentRuns()).as("recent=999 夹到 20，实际只有 3 条").hasSize(3);
            assertThat(clampedHigh.dailyTrend()).hasSize(OverviewService.MAX_DAYS);
            assertThat(clampedHigh.windowDays()).isEqualTo(OverviewService.MAX_DAYS);

            // ---- 跨项目：当前用户不是 other 项目成员 → 403 ----
            final Long foreignProjectId = otherProjectId;
            assertThatThrownBy(() -> service.load(foreignProjectId, 8, WINDOW_DAYS))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));

            System.out.println("[OVW-01] cases=" + view.cases().total()
                    + " assets(dev/port/link/cred/env/set)=" + view.assets().devices() + "/"
                    + view.assets().ports() + "/" + view.assets().links() + "/"
                    + view.assets().credentials() + "/" + view.assets().environments() + "/"
                    + view.assets().testSets()
                    + " runs(total/finished/canceled/running)=" + view.runs().total() + "/"
                    + view.runs().finished() + "/" + view.runs().canceled() + "/" + view.runs().running()
                    + " passRateBp=" + view.runs().passRateBp()
                    + " trendPoints=" + view.dailyTrend().size()
                    + " | 空项目 passRateBp=" + empty.runs().passRateBp()
                    + " | 跨项目=403");
        } finally {
            TenantContext.clear();
            cleanup(jdbc, projectId, emptyProjectId, otherProjectId, userId,
                    projectCode, emptyCode, otherCode, username);
        }
    }

    /**
     * 分组键的**空值口径**（UX-03 回归点）。
     *
     * <p>历史实现用 {@code COALESCE(NULLIF(col, ''), '未标注')} 在 SQL 里拼中文展示文案，
     * 结果是接口把「未标注」当数据吐给前端——英文界面必然冒中文，且前端只能靠字符串
     * 相等判断哪一项是"未填"。现已改为 `COALESCE(col, '')`：空串表示未填，
     * 由前端按当前语言翻译。
     *
     * <p>这条测试专门盯住"退回中文哨兵"的改动，因为它在中文界面下**看起来完全正常**。
     */
    @Test
    @DisplayName("分组键：未填的优先级/技术域返回空串，接口不返回中文展示文案")
    void 分组键空值返回空串() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        OverviewService service = new OverviewService(
                new OverviewRepositoryJdbc(jdbc),
                new AccessControlService(new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc)));

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-ovw-key-" + stamp;
        String projectCode = "IT-OVW-KEY-" + stamp;
        Long userId = null;
        Long projectId = null;
        try {
            userId = seedUser(jdbc, username);
            projectId = seedProject(jdbc, projectCode, userId);
            seedMember(jdbc, projectId, userId);
            TenantContext.set(userId, username, null);

            seedCase(jdbc, projectId, "OVW-K1", "P0", "draft", "rdma");
            // priority / domain_tag 允许为 NULL；review_status 是 NOT NULL DEFAULT 'draft'
            jdbc.update("INSERT INTO test_case (project_id, case_code, title) VALUES (?, ?, ?)",
                    projectId, "OVW-K2", "未填优先级与技术域");

            ProjectOverview view = service.load(projectId, 8, WINDOW_DAYS);

            assertThat(pairs(view.cases().byPriority()))
                    .as("空优先级要归到空串，不能是「未标注」")
                    .containsExactlyInAnyOrder("P0=1", "=1");
            assertThat(pairs(view.cases().topDomains()))
                    .as("空技术域要归到空串")
                    .containsExactlyInAnyOrder("rdma=1", "=1");
            assertThat(view.cases().byPriority())
                    .as("接口返回的 key 不允许含中文（那是展示文案，不是数据）")
                    .allSatisfy(item -> assertThat(item.key()).matches("[^\\u4e00-\\u9fff]*"));
            assertThat(view.cases().topDomains())
                    .allSatisfy(item -> assertThat(item.key()).matches("[^\\u4e00-\\u9fff]*"));

            System.out.println("[UX-03] 分组键空值：byPriority=" + pairs(view.cases().byPriority())
                    + " topDomains=" + pairs(view.cases().topDomains()));
        } finally {
            TenantContext.clear();
            cleanup(jdbc, projectId, null, null, userId, projectCode, null, null, username);
        }
    }

    private static List<String> pairs(List<ProjectOverview.CountItem> items) {
        return items.stream().map(item -> item.key() + "=" + item.count()).toList();
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

    private static Long seedUser(JdbcTemplate jdbc, String username) {
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, 1)", username, "$2a$10$it-not-a-real-hash", "OVW-01 IT");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private static Long seedProject(JdbcTemplate jdbc, String code, Long ownerId) {
        jdbc.update("INSERT INTO project (code, name, owner_id, status) VALUES (?, ?, ?, 1)",
                code, "OVW-01 总览集成测试", ownerId);
        return jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, code);
    }

    private static void seedMember(JdbcTemplate jdbc, Long projectId, Long userId) {
        jdbc.update("INSERT INTO project_member (project_id, user_id, member_role) "
                + "VALUES (?, ?, 'engineer')", projectId, userId);
    }

    private static void seedCase(JdbcTemplate jdbc, Long projectId, String code, String priority,
                                 String review, String domain) {
        jdbc.update("INSERT INTO test_case (project_id, case_code, title, priority, review_status, "
                + "domain_tag) VALUES (?, ?, ?, ?, ?, ?)",
                projectId, code, "用例 " + code, priority, review, domain);
    }

    private static Long seedDevice(JdbcTemplate jdbc, Long projectId, String name, int status) {
        jdbc.update("INSERT INTO device (project_id, name, status) VALUES (?, ?, ?)",
                projectId, name, status);
        return jdbc.queryForObject("SELECT id FROM device WHERE project_id = ? AND name = ?",
                Long.class, projectId, name);
    }

    private static void seedPort(JdbcTemplate jdbc, Long deviceId, String name) {
        jdbc.update("INSERT INTO device_port (device_id, port_name) VALUES (?, ?)", deviceId, name);
    }

    private static void seedLink(JdbcTemplate jdbc, Long projectId, String name, int status) {
        jdbc.update("INSERT INTO link (project_id, name, status) VALUES (?, ?, ?)",
                projectId, name, status);
    }

    private static void seedCredential(JdbcTemplate jdbc, Long projectId, String name) {
        jdbc.update("INSERT INTO credential (project_id, name, cred_type, secret_ref) "
                + "VALUES (?, ?, 'ssh', 'env:IT_PWD')", projectId, name);
    }

    /** 造一条 run，started_at 落在今天、finished_at 晚 1.5 秒（给平均耗时一个样本）。 */
    private static Long seedRun(JdbcTemplate jdbc, Long projectId, String runId, String state,
                                String dut, String link, int total, Long testSetId) {
        jdbc.update("INSERT INTO run (run_id, project_id, test_set_id, run_status, enqueued_at, started_at, "
                + "finished_at, total_tests, passed_tests, failed_tests, dut, `link`, trigger_type) "
                + "VALUES (?, ?, ?, ?, NOW(3), NOW(3), TIMESTAMPADD(MICROSECOND, 1500000, NOW(3)), "
                + "?, 0, 0, ?, ?, 'manual')",
                runId, projectId, testSetId, state, total, dut, link);
        return jdbc.queryForObject("SELECT id FROM run WHERE run_id = ?", Long.class, runId);
    }

    private static void cleanup(JdbcTemplate jdbc, Long projectId, Long emptyProjectId,
                                Long otherProjectId, Long userId,
                                String projectCode, String emptyCode, String otherCode,
                                String username) {
        try {
            for (Long id : new Long[]{projectId, emptyProjectId, otherProjectId}) {
                if (id == null) {
                    continue;
                }
                jdbc.update("DELETE FROM test_result WHERE run_id IN (SELECT id FROM run WHERE project_id = ?)", id);
                jdbc.update("DELETE FROM run WHERE project_id = ?", id);
                jdbc.update("DELETE FROM device_port WHERE device_id IN (SELECT id FROM device WHERE project_id = ?)", id);
                jdbc.update("DELETE FROM test_case WHERE project_id = ?", id);
                jdbc.update("DELETE FROM device WHERE project_id = ?", id);
                jdbc.update("DELETE FROM link WHERE project_id = ?", id);
                jdbc.update("DELETE FROM credential WHERE project_id = ?", id);
                jdbc.update("DELETE FROM env_profile WHERE project_id = ?", id);
                jdbc.update("DELETE FROM test_set WHERE project_id = ?", id);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", id);
                jdbc.update("DELETE FROM project WHERE id = ?", id);
            }
            if (userId != null) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        } catch (RuntimeException ex) {
            System.err.println("[OverviewIT] 清理失败，残留 project=" + projectCode + "/" + emptyCode
                    + "/" + otherCode + " user=" + username + "：" + ex.getMessage());
        }
    }
}
