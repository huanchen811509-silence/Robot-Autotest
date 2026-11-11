package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.infrastructure.AuditRepositoryJdbc;
import com.qa.autotest.infrastructure.ProjectRepositoryJdbc;
import com.qa.autotest.infrastructure.UserRepositoryJdbc;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 导入预览 {@code force} 语义的集成测试（v4.11.0 / FR-v4.11-18，W-E-02）。
 *
 * <p><b>为什么必须是真库</b>：这条判据的核心是「force 重算**不写库**」。用替身仓储测它等于
 * 自己证明自己——真正会出问题的是 SQL 层：一旦重算走了原来的 upsert 分支，
 * 已提交批次会被改成 {@code PREFLIGHT} 并拿到一组新计数，而 {@code uk_batch_checksum_kind}
 * 又让「同 checksum 新建批次」根本不可行。所以必须拿真表看「批次行有没有被动过」。
 *
 * <p><b>做法</b>：给台账固件真实算出 sha256，插一条 {@code status='COMMITTED'} 的
 * {@code import_batch} 假装「这份文件当初已经导入过」，然后：
 * <ol>
 *   <li>{@code force=false} → 必须走回放（{@code replayed=true}），批次不动；</li>
 *   <li>{@code force=true} → 必须重算（{@code replayed=false}，明细非空），且批次的状态与
 *       五个计数<b>一个都不许变</b>，{@code import_item} 行数也不许变；</li>
 *   <li>再跑一次 {@code force=false} → 仍然 {@code replayed=true}，证明上一步没把批次打回原形以外的状态。</li>
 * </ol>
 *
 * <p><b>本测试不做的事</b>：不起 Spring 上下文（{@code @Transactional} 不生效），
 * 因此自建的用户与批次在 {@code finally} 里显式清理；不验证权限拒绝路径、不验证并发。
 * 不涉及任何 SSH / 设备操作（ADR-0013 禁跑令）。
 */
@Tag("it")
class GlobalCasePreviewForceIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    /** 台账固件行数；改这个值等于改判据。 */
    private static final int EXPECTED_ROWS = 226;

    @Test
    @DisplayName("force=false 回放 / force=true 重算且不改历史批次")
    void force重算不写库() throws Exception {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误改开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ProjectRepositoryJdbc projects = new ProjectRepositoryJdbc(jdbc);
        UserRepositoryJdbc users = new UserRepositoryJdbc(jdbc);
        AccessControlService access = new AccessControlService(users, projects);
        AuditService audit = new AuditService(new AuditRepositoryJdbc(jdbc));
        GlobalCaseService service = new GlobalCaseService(jdbc, new ObjectMapper(),
                new PlatformProjectService(projects), access, audit);

        long projectId = platformProjectId(jdbc);
        String sha = sha256(ledger());

        String stamp = String.valueOf(System.currentTimeMillis());
        String username = "it-we02-" + stamp;
        Long userId = null;
        Long batchId = null;
        boolean createdBatch = false;
        boolean[] createdRole = {false};
        try {
            userId = seedAdmin(jdbc, username, createdRole);
            TenantContext.set(userId, username, null);

            // ---- 0. 造一个「这份文件当初已提交」的假批次 ----
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id FROM import_batch WHERE project_id = ? AND kind = 'global' "
                            + "AND checksum = ?", projectId, sha);
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO import_batch (project_id, source_type, source_name, checksum,
                                                  version, status, kind, total_rows, new_count,
                                                  updated_count, unchanged_count, deprecated_count,
                                                  committed_at)
                        VALUES (?, 'excel', 'ledger-sample-226.xlsx', ?, 1, 'COMMITTED', 'global',
                                ?, 226, 0, 0, 0, NOW(3))
                        """, projectId, sha, EXPECTED_ROWS);
                batchId = jdbc.queryForObject(
                        "SELECT id FROM import_batch WHERE project_id = ? AND kind = 'global' "
                                + "AND checksum = ?", Long.class, projectId, sha);
                createdBatch = true;
            } else {
                batchId = ((Number) existing.get(0).get("id")).longValue();
            }
            Map<String, Object> before = snapshot(jdbc, batchId);
            long itemsBefore = itemCount(jdbc, batchId);

            // ---- 1. force=false：命中已提交批次 → 回放 ----
            GlobalCaseService.PreviewResult replay;
            try (InputStream in = Files.newInputStream(ledger())) {
                replay = service.preview(in, "ledger-sample-226.xlsx", false);
            }
            assertThat(replay.replayed()).as("同文件命中已提交批次必须走回放").isTrue();
            assertThat(replay.batchId()).isEqualTo(batchId);
            assertThat(snapshot(jdbc, batchId)).as("回放不得改动批次").isEqualTo(before);

            // ---- 2. force=true：重算，但一个字段都不许落库 ----
            GlobalCaseService.PreviewResult forced;
            try (InputStream in = Files.newInputStream(ledger())) {
                forced = service.preview(in, "ledger-sample-226.xlsx", true);
            }
            assertThat(forced.replayed()).as("force 走重算分支，不是回放").isFalse();
            assertThat(forced.total()).as("重算要给出当前库的真实差异").isEqualTo(EXPECTED_ROWS);
            assertThat(forced.rows()).as("重算必须返回明细行").isNotEmpty();
            assertThat(forced.batchId()).isEqualTo(batchId);

            assertThat(snapshot(jdbc, batchId))
                    .as("force 重算不得改批次状态/计数（否则历史记录被篡改）")
                    .isEqualTo(before);
            assertThat(itemCount(jdbc, batchId))
                    .as("force 重算不得写 import_item")
                    .isEqualTo(itemsBefore);

            // ---- 3. 再回放一次：证明确实没被改成 PREFLIGHT ----
            GlobalCaseService.PreviewResult again;
            try (InputStream in = Files.newInputStream(ledger())) {
                again = service.preview(in, "ledger-sample-226.xlsx", false);
            }
            assertThat(again.replayed()).as("批次仍是 COMMITTED").isTrue();
            assertThat(again.total()).isEqualTo(replay.total());
            assertThat(snapshot(jdbc, batchId)).isEqualTo(before);

            System.out.println("[WE-02] batch=" + batchId + " status=" + before.get("status")
                    + " total=" + forced.total() + " new=" + forced.newCount()
                    + " update=" + forced.updateCount() + " unchanged=" + forced.unchangedCount()
                    + " deprecate=" + forced.deprecateCount()
                    + " | 回放 replayed=" + replay.replayed()
                    + " | force 后批次计数未变=" + before.equals(snapshot(jdbc, batchId))
                    + " | import_item 未变=" + (itemCount(jdbc, batchId) == itemsBefore));
        } finally {
            TenantContext.clear();
            if (createdBatch && batchId != null) {
                jdbc.update("DELETE FROM import_item WHERE batch_id = ?", batchId);
                jdbc.update("DELETE FROM import_batch WHERE id = ?", batchId);
            }
            cleanupUser(jdbc, userId, username);
            if (createdRole[0]) {
                jdbc.update("DELETE FROM role WHERE code = 'admin'");
            }
        }
    }

    /** 抓批次的可比快照：状态 + 五个计数 + 提交时间。 */
    private static Map<String, Object> snapshot(JdbcTemplate jdbc, long batchId) {
        return jdbc.queryForMap("SELECT status, total_rows, new_count, updated_count, "
                + "unchanged_count, deprecated_count, committed_at FROM import_batch WHERE id = ?",
                batchId);
    }

    private static long itemCount(JdbcTemplate jdbc, long batchId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM import_item WHERE batch_id = ?",
                Long.class, batchId);
        return count == null ? 0 : count;
    }

    private static long platformProjectId(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class,
                PlatformProjectService.CODE);
        assertThat(id).as("平台库项目 %s 不存在，先跑 flyway + 平台初始化",
                PlatformProjectService.CODE).isNotNull();
        return id;
    }

    /**
     * 造一个全局 admin。
     *
     * <p>robot_it 里 {@code role} 表可能是空的（角色字典由开发库/初始化脚本写入，不随迁移落 IT 库），
     * 所以这里按需补一条 {@code code='admin'} 的角色行；如果本来就有就复用，退出时只删自己建的那条。</p>
     */
    private static Long seedAdmin(JdbcTemplate jdbc, String username, boolean[] createdRole) {
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, 1)", username, "$2a$10$it-not-a-real-hash", "WE-02 IT");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?",
                Long.class, username);
        List<Long> roleIds = jdbc.queryForList("SELECT id FROM role WHERE code = 'admin'", Long.class);
        Long roleId;
        if (roleIds.isEmpty()) {
            jdbc.update("INSERT INTO role (code, name, description, built_in) "
                    + "VALUES ('admin', '管理员', 'WE-02 IT seed', 1)");
            roleId = jdbc.queryForObject("SELECT id FROM role WHERE code = 'admin'", Long.class);
            createdRole[0] = true;
        } else {
            roleId = roleIds.get(0);
        }
        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return userId;
    }

    private static void cleanupUser(JdbcTemplate jdbc, Long userId, String username) {
        if (userId == null) {
            return;
        }
        try {
            jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        } catch (RuntimeException ex) {
            System.out.println("[WE-02] 清理用户失败（不影响判定）：" + username + " " + ex.getMessage());
        }
    }

    private static javax.sql.DataSource dataSource() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        // 驱动是 MariaDB Java client，协议前缀必须是 jdbc:mariadb://（ADR-0002：不用 H2）。
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
        return new DriverManagerDataSource(url, user, password);
    }

    private static Path ledger() {
        List<Path> candidates = List.of(
                Path.of("..", "fixtures", "case-ledger", "ledger-sample-226.xlsx"),
                Path.of("fixtures", "case-ledger", "ledger-sample-226.xlsx"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到台账固件，试过：" + candidates
                + "；cwd=" + Path.of("").toAbsolutePath());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
