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
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 用例批量操作端到端集成测试（UX-02 的真判据：TC-BAT-03/05/06/07/08/09），必须连真实 MySQL。
 *
 * <p>对应 FR-v0.2-36~39。验收口径（DEV-PLAN §10.5 契约硬规则）：
 * <ul>
 *   <li>部分成功返回 200 语义（服务层不抛异常，返回逐条结果）；</li>
 *   <li>逐条独立事务——本测试用真 {@link DataSourceTransactionManager} 驱动
 *       {@link TransactionTemplate}，单条冲突不影响其他条落库；</li>
 *   <li>仅 ids 空 / 超 200 / action 未知 / 动作专属参数缺失才 400；</li>
 *   <li>每条审计带 {@code batch=} 标识，同批次可反查。</li>
 * </ul>
 *
 * <p>连库方式与 {@code CaseImportIT} 一致（ADR-0002，库名必须以 {@code _it} 结尾）。
 * 每次运行用带时间戳的唯一项目编号，跑完清理，可重复执行。
 */
@Tag("it")
class CaseBatchIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    @Test
    @DisplayName("六动作全链路 + 部分成功（TC-BAT-03/09）+ 批量审计（TC-BAT-07）")
    void 六动作与部分成功() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        TestCaseRepository cases = new TestCaseRepositoryJdbc(jdbc);
        AccessControlService access = new AccessControlService(
                new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc));
        // 同步执行器：审计写完立即落库，测试可马上查（生产走异步，不改变只增不改纪律）
        AuditService audit = new AuditService(new AuditRepositoryJdbc(jdbc), Runnable::run);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource());
        TestCaseService service = new TestCaseService(cases, access, audit, txManager);

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-bat-" + stamp;
        String projectCode = "IT-BAT-" + stamp;
        Long userId = null;
        Long projectId = null;
        try {
            userId = seedUser(jdbc, username);
            projectId = seedProject(jdbc, projectCode, userId);
            seedMember(jdbc, projectId, userId);
            TenantContext.set(userId, username, null);

            long draftA = seedCase(jdbc, projectId, "BAT-" + stamp + "-A", "draft");
            long draftB = seedCase(jdbc, projectId, "BAT-" + stamp + "-B", "draft");
            long approvedC = seedCase(jdbc, projectId, "BAT-" + stamp + "-C", "approved");
            long draftD = seedCase(jdbc, projectId, "BAT-" + stamp + "-D", "draft");

            // ---- 改优先级 / 改技术域：任意状态可改 ----
            TestCaseService.BatchResult pr = service.batch(projectId,
                    new TestCaseService.BatchRequest("setPriority",
                            List.of(draftA, draftB), null, "P1", null, null));
            assertThat(pr.succeeded()).as("setPriority 两条都成功").isEqualTo(2);
            assertThat(priorityOf(jdbc, draftA)).isEqualTo("P1");
            assertThat(priorityOf(jdbc, draftB)).isEqualTo("P1");

            TestCaseService.BatchResult dm = service.batch(projectId,
                    new TestCaseService.BatchRequest("setDomainTag",
                            List.of(draftA), null, null, "roce", null));
            assertThat(dm.succeeded()).isEqualTo(1);
            assertThat(domainOf(jdbc, draftA)).isEqualTo("roce");

            // ---- 改安全级（G2-3 / ADR-0013）：人工补判 LX → L2 落库 ----
            TestCaseService.BatchResult sf = service.batch(projectId,
                    new TestCaseService.BatchRequest("setSafetyLevel",
                            List.of(draftD), null, null, null, "L2"));
            assertThat(sf.succeeded()).as("setSafetyLevel 一条成功").isEqualTo(1);
            assertThat(safetyOf(jdbc, draftD)).isEqualTo("L2");

            // ---- 提交评审：draft 可提交，approved 冲突（TC-BAT-03 形态）----
            TestCaseService.BatchResult sb = service.batch(projectId,
                    new TestCaseService.BatchRequest("submit",
                            List.of(draftA, draftB, approvedC), null, null, null, null));
            assertThat(sb.total()).isEqualTo(3);
            assertThat(sb.succeeded()).isEqualTo(2);
            assertThat(sb.failed()).isEqualTo(1);
            TestCaseService.BatchItemResult conflict = sb.items().stream()
                    .filter(i -> !i.ok()).findFirst().orElseThrow();
            assertThat(conflict.id()).as("冲突条就是那条 approved").isEqualTo(approvedC);
            assertThat(conflict.code()).isEqualTo(ErrorCode.CONFLICT.codeName());
            assertThat(reviewStatusOf(jdbc, draftA)).isEqualTo("pending");
            assertThat(reviewStatusOf(jdbc, draftB)).isEqualTo("pending");
            assertThat(reviewStatusOf(jdbc, approvedC)).as("approved 不被波及").isEqualTo("approved");

            // ---- 驳回必填意见：pending → rejected，意见落库 ----
            TestCaseService.BatchResult rj = service.batch(projectId,
                    new TestCaseService.BatchRequest("reject",
                            List.of(draftB), "批量驳回：步骤缺预期", null, null, null));
            assertThat(rj.succeeded()).isEqualTo(1);
            assertThat(reviewStatusOf(jdbc, draftB)).isEqualTo("rejected");
            assertThat(commentOf(jdbc, draftB)).contains("批量驳回");

            // ---- 批量删除：approved 不可批删（TC-BAT-09），draft 删除成功且审计带批次 ----
            long draftE = seedCase(jdbc, projectId, "BAT-" + stamp + "-E", "draft");
            TestCaseService.BatchResult del = service.batch(projectId,
                    new TestCaseService.BatchRequest("delete",
                            List.of(approvedC, draftA, draftE), null, null, null, null));
            assertThat(del.succeeded()).as("approvedC 冲突，draftA/draftE 删除；items=%s", del.items())
                    .isEqualTo(2);
            TestCaseService.BatchItemResult delFail = del.items().stream()
                    .filter(i -> !i.ok()).findFirst().orElseThrow();
            assertThat(delFail.id()).isEqualTo(approvedC);
            assertThat(delFail.code()).isEqualTo(ErrorCode.CONFLICT.codeName());
            assertThat(delFail.message()).contains("驳回");
            assertThat(caseCount(jdbc, projectId, approvedC)).as("approved 仍在库").isEqualTo(1);
            assertThat(caseCount(jdbc, projectId, draftA)).as("draftA 已删").isZero();
            assertThat(caseCount(jdbc, projectId, draftE)).as("draftE 已删").isZero();

            // ---- 批量审计（TC-BAT-07）：同一批次同 batch 标识 + 操作人可查 ----
            List<Map<String, Object>> delAudits = jdbc.queryForList(
                    "SELECT operator, detail FROM audit_log "
                    + "WHERE project_id = ? AND action = 'case.batch.delete' ORDER BY id",
                    projectId);
            assertThat(delAudits).as("批删 2 条 → 审计 2 条").hasSize(2);
            String batchId = batchIdOf((String) delAudits.get(0).get("detail"));
            assertThat(batchId).as("审计 detail 必须带 batch= 标识").isNotBlank();
            assertThat((String) delAudits.get(1).get("detail"))
                    .as("同批次两条审计共享同一 batch 标识").contains("batch=" + batchId);
            assertThat(delAudits.get(0).get("operator")).as("审计可查操作人").isEqualTo(username);

            System.out.println("[UX-02] setPriority=2/0 setDomain=1/0 submit=" + sb.succeeded()
                    + "/" + sb.failed() + " delete=" + del.succeeded() + "/" + del.failed()
                    + " | 审计 batch=" + batchId + " 条数=" + delAudits.size()
                    + " | 操作人=" + delAudits.get(0).get("operator"));
        } finally {
            TenantContext.clear();
            cleanup(jdbc, projectId, userId, projectCode, username);
        }
    }

    @Test
    @DisplayName("400 路径：空 ids / 超 200 / 未知 action / 驳回缺意见 / 非法优先级（TC-BAT-05/06）")
    void 参数校验与上限() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        TestCaseRepository cases = new TestCaseRepositoryJdbc(jdbc);
        AccessControlService access = new AccessControlService(
                new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc));
        AuditService audit = new AuditService(new AuditRepositoryJdbc(jdbc), Runnable::run);
        TestCaseService service = new TestCaseService(cases, access, audit);

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-bat400-" + stamp;
        String projectCode = "IT-BAT400-" + stamp;
        Long userId = null;
        Long projectId = null;
        try {
            userId = seedUser(jdbc, username);
            projectId = seedProject(jdbc, projectCode, userId);
            seedMember(jdbc, projectId, userId);
            TenantContext.set(userId, username, null);
            final Long pid = projectId;

            long before = cases.countByProject(projectId);

            // TC-BAT-06：空 ids → 400
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("approve", List.of(), null, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

            // TC-BAT-05：201 条 → 400，提示分批
            List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("approve", tooMany, null, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                        assertThat(ex.getMessage()).contains("分批");
                    });

            // 未知 action → 400
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("purge", List.of(1L), null, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

            // 驳回缺意见 → 400
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("reject", List.of(1L), " ", null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

            // 非法优先级 → 400
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("setPriority", List.of(1L), null, "P9", null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

            // 非法安全级 → 400（G2-3：值域只认 L0/L1/L2/LX）
            assertThatThrownBy(() -> service.batch(pid,
                    new TestCaseService.BatchRequest("setSafetyLevel", List.of(1L), null, null,
                            null, "L9")))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                        assertThat(ex.getMessage()).contains("安全级");
                    });

            assertThat(cases.countByProject(projectId))
                    .as("400 路径不产生任何写操作").isEqualTo(before);
        } finally {
            TenantContext.clear();
            cleanup(jdbc, projectId, userId, projectCode, username);
        }
    }

    @Test
    @DisplayName("越权批处理（TC-BAT-08）：A 项目身份批改 B 项目用例 → 逐条 NOT_FOUND，零改动")
    void 越权与跨项目() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        TestCaseRepository cases = new TestCaseRepositoryJdbc(jdbc);
        AccessControlService access = new AccessControlService(
                new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc));
        AuditService audit = new AuditService(new AuditRepositoryJdbc(jdbc), Runnable::run);
        TestCaseService service = new TestCaseService(cases, access, audit);

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-batx-" + stamp;
        Long userId = null;
        Long projectA = null;
        Long projectB = null;
        try {
            userId = seedUser(jdbc, username);
            projectA = seedProject(jdbc, "IT-BATXA-" + stamp, userId);
            projectB = seedProject(jdbc, "IT-BATXB-" + stamp, userId);
            seedMember(jdbc, projectA, userId);   // 只加入 A，未加入 B
            TenantContext.set(userId, username, null);

            long b1 = seedCase(jdbc, projectB, "BATX-" + stamp + "-B1", "draft");
            long b2 = seedCase(jdbc, projectB, "BATX-" + stamp + "-B2", "draft");

            TestCaseService.BatchResult result = service.batch(projectA,
                    new TestCaseService.BatchRequest("approve",
                            List.of(b1, b2), null, null, null, null));
            assertThat(result.failed()).as("两条都逐条失败").isEqualTo(2);
            assertThat(result.items()).allSatisfy(i -> {
                assertThat(i.ok()).isFalse();
                assertThat(i.code()).isEqualTo(ErrorCode.NOT_FOUND.codeName());
            });
            assertThat(reviewStatusOf(jdbc, b1)).as("B 项目用例零改动").isEqualTo("draft");
            assertThat(reviewStatusOf(jdbc, b2)).isEqualTo("draft");
        } finally {
            TenantContext.clear();
            cleanup(jdbc, projectA, userId, "IT-BATXA-" + stamp, username);
            cleanup(jdbc, projectB, userId, "IT-BATXB-" + stamp, username);
        }
    }

    // ---- 基建：与 CaseImportIT 同款 ----

    private static javax.sql.DataSource dataSource() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
        return new DriverManagerDataSource(url, user, password);
    }

    private static Long seedUser(JdbcTemplate jdbc, String username) {
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, 1)", username, "$2a$10$it-not-a-real-hash", "UX-02 IT");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private static Long seedProject(JdbcTemplate jdbc, String code, Long ownerId) {
        jdbc.update("INSERT INTO project (code, name, owner_id, status) VALUES (?, ?, ?, 1)",
                code, "UX-02 批量集成测试", ownerId);
        return jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, code);
    }

    private static void seedMember(JdbcTemplate jdbc, Long projectId, Long userId) {
        jdbc.update("INSERT INTO project_member (project_id, user_id, member_role) "
                + "VALUES (?, ?, 'admin')", projectId, userId);
    }

    private static long seedCase(JdbcTemplate jdbc, Long projectId, String code, String reviewStatus) {
        jdbc.update("INSERT INTO test_case (project_id, case_code, title, review_status) "
                + "VALUES (?, ?, ?, ?)", projectId, code, code + " 标题", reviewStatus);
        return jdbc.queryForObject(
                "SELECT id FROM test_case WHERE project_id = ? AND case_code = ?",
                Long.class, projectId, code);
    }

    private static String reviewStatusOf(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject(
                "SELECT review_status FROM test_case WHERE id = ?", String.class, id);
    }

    private static String priorityOf(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT priority FROM test_case WHERE id = ?", String.class, id);
    }

    private static String domainOf(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT domain_tag FROM test_case WHERE id = ?", String.class, id);
    }

    private static String safetyOf(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject(
                "SELECT safety_level FROM test_case WHERE id = ?", String.class, id);
    }

    private static String commentOf(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject(
                "SELECT review_comment FROM test_case WHERE id = ?", String.class, id);
    }

    private static int caseCount(JdbcTemplate jdbc, Long projectId, long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_case WHERE project_id = ? AND id = ?",
                Integer.class, projectId, id);
        return count == null ? 0 : count;
    }

    /** 从审计 detail 提取 batch 标识（形态：batch=<16 位>）。 */
    private static String batchIdOf(String detail) {
        if (detail == null) {
            return "";
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("batch=([0-9a-f]+)").matcher(detail);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void cleanup(JdbcTemplate jdbc, Long projectId, Long userId,
                                String projectCode, String username) {
        try {
            if (projectId != null) {
                jdbc.update("DELETE FROM test_case WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM audit_log WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            }
            if (userId != null) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        } catch (RuntimeException ex) {
            System.err.println("[CaseBatchIT] 清理失败，残留 project=" + projectCode
                    + " user=" + username + "：" + ex.getMessage());
        }
    }
}
