package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.infrastructure.ProjectRepositoryJdbc;
import java.time.Instant;
import java.util.Optional;
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
 * owner 转移的**真实库 + 真事务代理**测试（v5.9.0 / FR-v5.9-11，W59-09 判据 ②③）。
 *
 * <p><b>为什么不能直接 new ProjectService</b>：{@code @Transactional} 只在 Spring 代理上生效。
 * 手工 new 出来的对象没有代理，所谓「事务回滚」根本不存在，测试会假绿。本类用最小
 * {@code @EnableTransactionManagement} 容器拿代理 bean —— 不启整个 Boot 上下文，
 * 但事务是真 DataSourceTransactionManager 提供的。
 *
 * <p><b>回滚怎么造</b>：owner 转移成功后紧接一步写 {@code project.name}，故意给 200 字符
 * （列宽 128 + STRICT_TRANS_TABLES）让 SQL 真实失败。若事务没开，刚插入的成员行会留在库里。
 *
 * <p>库名守卫：{@code DB_NAME} 必须以 {@code _it} 结尾，防止误清开发库。
 */
@Tag("it")
class ProjectTransferTxIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");
    private static final long OPERATOR = 1L;

    /** 最小事务容器：只装配本测试需要的东西，避免拉整个 Boot 上下文。 */
    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
        @Bean
        DataSource dataSource() {
            return ProjectTransferTxIT.dataSource();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ProjectRepository projectRepository(DataSource dataSource) {
            return new ProjectRepositoryJdbc(new JdbcTemplate(dataSource));
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        AccessControlService accessControlService() {
            return mock(AccessControlService.class);
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }

        @Bean
        ProjectService projectService(ProjectRepository repository, UserRepository users,
                                      AccessControlService access, AuditService audit) {
            return new ProjectService(repository, users, access, audit);
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private JdbcTemplate jdbc;
    private ProjectRepository repo;
    private ProjectService service;
    private UserRepository users;
    private long projectId;
    private long newOwnerId;

    @BeforeEach
    void setUp() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");
        ctx = new AnnotationConfigApplicationContext(TxConfig.class);
        jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
        repo = ctx.getBean(ProjectRepository.class);
        service = ctx.getBean(ProjectService.class);
        users = ctx.getBean(UserRepository.class);

        TenantContext.set(OPERATOR, "it-transfer", null);
        String stamp = String.valueOf(System.nanoTime());

        // 两个真实用户：A 是现任 owner，B 是转移目标（B 一开始不是成员）
        long ownerA = insertUser("it-tf-a-" + stamp);
        newOwnerId = insertUser("it-tf-b-" + stamp);
        when(users.findById(newOwnerId)).thenReturn(Optional.of(new AppUser(newOwnerId,
                "it-tf-b-" + stamp, "转移目标", null, "x", null, true, null, 0, null,
                Instant.now())));

        jdbc.update("""
                INSERT INTO project (code, name, description, visibility, test_kernel, kind,
                                     status, owner_id, created_by, updated_by)
                VALUES (?, 'IT 转移项目', 'it', 'private', 'robot', 'standard', 1, ?, ?, ?)
                """, "IT-TF-" + stamp, ownerA, OPERATOR, OPERATOR);
        projectId = jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class,
                "IT-TF-" + stamp);
        repo.addMember(projectId, ownerA, "admin", OPERATOR);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (projectId > 0) {
            jdbc.update("DELETE FROM project_member_role WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM project WHERE id = ?", projectId);
        }
        if (ctx != null) {
            ctx.close();
        }
    }

    private long insertUser(String username) {
        jdbc.update("INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username, "it-not-a-real-hash");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class,
                username);
    }

    @Test
    @DisplayName("转移成功：project.owner_id / project_member / project_member_role 三表同步")
    void 转移后三表同步() {
        service.update(projectId, "IT 转移项目", "it", true, newOwnerId, "private", "robot");

        assertThat(ownerOfProject()).isEqualTo(newOwnerId);
        assertThat(jdbc.queryForObject(
                "SELECT member_role FROM project_member WHERE project_id = ? AND user_id = ?",
                String.class, projectId, newOwnerId)).isEqualTo("admin");
        assertThat(jdbc.queryForList(
                "SELECT role_code FROM project_member_role WHERE project_id = ? AND user_id = ?",
                String.class, projectId, newOwnerId)).containsExactly("admin");
    }

    @Test
    @DisplayName("事务回滚：owner 那步失败 → 刚插入的成员行一并消失，owner 保持原值")
    void 后一步失败时成员行回滚() {
        long ownerBefore = ownerOfProject();
        // name 超过 varchar(128)，STRICT_TRANS_TABLES 下必抛 SQL 异常
        String tooLong = "x".repeat(200);

        assertThatThrownBy(() -> service.update(projectId, tooLong, "it", true, newOwnerId,
                "private", "robot"))
                .isInstanceOf(RuntimeException.class);

        assertThat(ownerOfProject()).as("owner 不能在失败后变化").isEqualTo(ownerBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Long.class, projectId, newOwnerId))
                .as("transferOwner 已插入的成员行必须随事务回滚").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member_role WHERE project_id = ? AND user_id = ?",
                Long.class, projectId, newOwnerId))
                .as("角色行同样必须回滚").isZero();
    }

    private Long ownerOfProject() {
        return jdbc.queryForObject("SELECT owner_id FROM project WHERE id = ?", Long.class,
                projectId);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setUrl("jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME);
        ds.setUsername(System.getenv().getOrDefault("DB_USERNAME", "robot"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", ""));
        return ds;
    }
}
