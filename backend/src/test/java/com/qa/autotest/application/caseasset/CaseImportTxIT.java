package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.infrastructure.AuditRepositoryJdbc;
import com.qa.autotest.infrastructure.ProjectRepositoryJdbc;
import com.qa.autotest.infrastructure.TestCaseRepositoryJdbc;
import com.qa.autotest.infrastructure.UserRepositoryJdbc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * CAS-03 的「事务语义」集成测试：整批回滚 / 403 拒绝 / 并发导入。
 *
 * <p><b>为什么要有这一条</b>：{@link CaseImportIT} 直接 new 出 {@link CaseImportService}，
 * 没有代理，{@code @Transactional} 不生效，所以它只能证明「解析 + 逐行插入 + 重复检测」，
 * 证明不了 {@code commit} 中途撞编号时已插入的行会不会留下来。本类用
 * <b>最小 Spring 上下文</b>（只有数据源、事务管理器与三个 Bean，不起 Boot、不起 Web、
 * 不跑 Flyway）把服务包进真事务代理，连真实 MySQL 验证。
 *
 * <p><b>本类覆盖</b>：
 * <ul>
 *   <li>整批回滚：批次第 3 行撞已有编号 → 抛 CONFLICT，前两行必须随事务一起消失。</li>
 *   <li>403 拒绝：项目内 viewer 角色（矩阵里没有 IMPORT:EXECUTE）与非成员两条路径。</li>
 *   <li>并发导入：两个线程同时导同一批编号，最终只能有一批落库、不能出现「各插一半」。</li>
 * </ul>
 *
 * <p><b>本类仍不覆盖</b>：HTTP 层的 403 状态码与响应体（没有 Web 层，这里断言的是服务层
 * {@link ErrorCode#FORBIDDEN}）；真实浏览器/网关下的并发压力（只测两个线程的竞态）。
 *
 * <p>连库方式与 {@code CaseImportIT} 一致（ADR-0002：不用 H2），库名必须以 {@code _it} 结尾。
 */
@Tag("it")
class CaseImportTxIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CaseImportService importer;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");
        context = new AnnotationConfigApplicationContext(TxConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        importer = context.getBean(CaseImportService.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        try {
            for (Long projectId : projectIds) {
                jdbc.update("DELETE FROM test_case WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member_role WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM audit_log WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            }
            for (Long userId : userIds) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("commit 中途撞编号：本批已插入的行整批回滚，库里不留半批")
    void 整批回滚() {
        long[] seed = seed("admin");
        long projectId = seed[1];
        TenantContext.set(seed[0], "it-tx-" + seed[0], null);

        // 先放一条真用例，假装它是上一次导入的结果
        importer.commit(projectId, rows("TN-TX-EXIST"));
        assertThat(count(projectId)).isEqualTo(1);

        // 第二批：两条新编号 → 撞已有编号 → 再一条新编号。冲突发生在中间
        List<CaseImportService.ImportRow> batch =
                rows("TN-TX-NEW-1", "TN-TX-NEW-2", "TN-TX-EXIST", "TN-TX-NEW-3");
        assertThatThrownBy(() -> importer.commit(projectId, batch))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        assertThat(count(projectId))
                .as("冲突前已插入的 TN-TX-NEW-1/2 必须随事务回滚，只剩原先那 1 条")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("项目内 viewer 角色导入：服务层 403，一行不落库")
    void 访客角色拒绝() {
        long[] seed = seed("viewer", false);
        long projectId = seed[1];
        TenantContext.set(seed[0], "it-tx-" + seed[0], null);

        assertThatThrownBy(() -> importer.commit(projectId, rows("TN-TX-V-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count(projectId)).isZero();
    }

    @Test
    @DisplayName("非项目成员导入：403 且消息不泄露项目是否存在")
    void 非成员拒绝() {
        long[] owner = seed("admin");
        long projectId = owner[1];
        long[] outsider = seed(null);
        TenantContext.set(outsider[0], "it-tx-" + outsider[0], null);

        assertThatThrownBy(() -> importer.commit(projectId, rows("TN-TX-O-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo("无权访问该项目");
                });
        assertThat(count(projectId)).isZero();
    }

    @Test
    @DisplayName("两个线程同时导同一批编号：只允许一批落库，不能各插一半")
    void 并发导入() throws Exception {
        long[] seed = seed("admin");
        final long userId = seed[0];
        final long projectId = seed[1];
        final String username = "it-tx-" + userId;
        final List<CaseImportService.ImportRow> batch =
                rows("TN-TX-C-1", "TN-TX-C-2", "TN-TX-C-3", "TN-TX-C-4", "TN-TX-C-5");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> task = () -> {
            TenantContext.set(userId, username, null);
            try {
                start.await();
                return importer.commit(projectId, batch);
            } catch (RuntimeException ex) {
                return ex;
            } finally {
                TenantContext.clear();
            }
        };
        try {
            Future<Object> first = pool.submit(task);
            Future<Object> second = pool.submit(task);
            start.countDown();
            Object a = first.get(60, TimeUnit.SECONDS);
            Object b = second.get(60, TimeUnit.SECONDS);

            long winners = java.util.stream.Stream.of(a, b)
                    .filter(r -> r instanceof CaseImportService.ImportResult)
                    .count();
            Object loser = a instanceof CaseImportService.ImportResult ? b : a;
            Object winner = a instanceof CaseImportService.ImportResult ? a : b;

            assertThat(winners).as("同一批编号只能有一批提交成功").isEqualTo(1);
            assertThat(loser).as("另一线程必须失败，不能静默成功").isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) loser).errorCode())
                    .as("并发撞唯一键要落成 409 冲突，不能变成兜底 500")
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(((Throwable) loser).getMessage()).contains("并发导入冲突");
            assertThat(count(projectId))
                    .as("失败那批不能留下半批数据（整批要么 5 条要么 0 条）")
                    .isEqualTo(batch.size());
            System.out.println("[CAS-03 并发] 成功批 imported="
                    + ((CaseImportService.ImportResult) winner).imported()
                    + " | 失败批异常=" + loser.getClass().getName()
                    + " | 库里行数=" + count(projectId));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 最小 Spring 上下文：只为让 @Transactional 生效
    // ------------------------------------------------------------------

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(dataSourceUrl(), username(), password());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TestCaseRepository testCaseRepository(JdbcTemplate jdbc) {
            return new TestCaseRepositoryJdbc(jdbc);
        }

        @Bean
        AccessControlService accessControlService(JdbcTemplate jdbc) {
            return new AccessControlService(new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc));
        }

        @Bean
        AuditService auditService(JdbcTemplate jdbc) {
            return new AuditService(new AuditRepositoryJdbc(jdbc));
        }

        @Bean
        CaseImportService caseImportService(TestCaseRepository cases, AccessControlService access,
                                            AuditService audit) {
            return new CaseImportService(cases, access, audit);
        }
    }

    private static String dataSourceUrl() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        // 驱动是 MariaDB Java client，前缀必须是 jdbc:mariadb://（写 jdbc:mysql:// 会报
        // No suitable driver found，看着像「驱动没引」，实际是前缀不对）。
        return "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
    }

    private static String username() {
        return System.getenv().getOrDefault("DB_USERNAME", "robot");
    }

    private static String password() {
        return System.getenv().getOrDefault("DB_PASSWORD", "");
    }

    // ------------------------------------------------------------------
    // 造数据 / 清理
    // ------------------------------------------------------------------

    /** 建用户 + 项目 + 成员关系；memberRole 为 null 表示「不是项目成员」。返回 [userId, projectId]。 */
    private long[] seed(String memberRole) {
        return seed(memberRole, true);
    }

    /**
     * @param selfOwner 被测用户是否即项目负责人。负责人隐含 PM（ProjectVisibilitySql），
     *                  所以「viewer 只读」这类判据必须传 false —— 否则测的是负责人。
     */
    private long[] seed(String memberRole, boolean selfOwner) {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        String username = "it-cas03-tx-" + stamp;
        String projectCode = "IT-CAS03TX-" + stamp;
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, 1)", username, "$2a$10$it-not-a-real-hash", "CAS-03 事务 IT");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        Long ownerId = userId;
        if (!selfOwner) {
            String ownerName = username + "-owner";
            jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                    + "VALUES (?, ?, ?, 1)", ownerName, "$2a$10$it-not-a-real-hash", "CAS-03 负责人");
            ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                    Long.class, ownerName);
            userIds.add(ownerId);
        }
        jdbc.update("INSERT INTO project (code, name, owner_id, status) VALUES (?, ?, ?, 1)",
                projectCode, "CAS-03 事务集成测试", ownerId);
        Long projectId = jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, projectCode);
        if (memberRole != null) {
            jdbc.update("INSERT INTO project_member (project_id, user_id, member_role) VALUES (?, ?, ?)",
                    projectId, userId, memberRole);
            // 判权读的是 project_member_role（v4.4 多角色表），只写 project_member.member_role
            // 等于没授权 —— 之前这几条用例靠「自己就是 owner」蒙对，owner 一换就全 403。
            jdbc.update("INSERT INTO project_member_role (project_id, user_id, role_code) "
                    + "VALUES (?, ?, ?)", projectId, userId, memberRole);
        }
        userIds.add(userId);
        projectIds.add(projectId);
        return new long[]{userId, projectId};
    }

    private static List<CaseImportService.ImportRow> rows(String... codes) {
        List<CaseImportService.ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            rows.add(new CaseImportService.ImportRow(i + 2, codes[i], "事务测试用例 " + codes[i],
                    "功能", "P2", "", "", "", "", "", "LX"));
        }
        return rows;
    }

    private long count(long projectId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM test_case WHERE project_id = ?",
                Long.class, projectId);
        return total == null ? 0 : total;
    }
}
