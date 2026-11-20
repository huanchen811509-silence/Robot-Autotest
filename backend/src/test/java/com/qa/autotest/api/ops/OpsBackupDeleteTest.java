package com.qa.autotest.api.ops;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.ops.OpsTaskService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v5.33.0 / FR-v5.33-06：备份删除。
 *
 * <p>核心不是「能删」，而是**只能删备份目录里那一份**：文件名是拼路径用的，
 * 不挡住 `../` 就等于开了一个删任意文件的口子。三条负例在这里钉死。
 */
class OpsBackupDeleteTest {

    @TempDir
    Path home;

    private OpsConsoleController controller;
    private AuditService audit;
    private final java.util.List<String> auditActions = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty("app.home", home.toString());
        Files.createDirectories(home.resolve("backups"));

        AccessControlService access = mock(AccessControlService.class);
        when(access.isAdmin()).thenReturn(true);
        audit = mock(AuditService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            auditActions.add(String.valueOf((Object) inv.getArgument(0)));
            return null;
        }).when(audit).record(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        controller = new OpsConsoleController(mock(OpsTaskService.class), access,
                mock(JdbcTemplate.class));
        controller.setAudit(audit);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("app.home");
    }

    @Test
    @DisplayName("正常删除：.gz 与 .sha256 一起消失，写审计")
    void deleteBothFiles() throws IOException {
        Path gz = Files.writeString(home.resolve("backups/robot-20261120.sql.gz"), "dump");
        Path sha = Files.writeString(home.resolve("backups/robot-20261120.sql.gz.sha256"), "abc");

        List<String> deleted = controller.deleteBackup("robot-20261120.sql.gz", 7L).data();

        assertEquals(List.of("robot-20261120.sql.gz", "robot-20261120.sql.gz.sha256"), deleted);
        assertFalse(Files.exists(gz));
        assertFalse(Files.exists(sha));
        assertTrue(auditActions.contains("OPS_BACKUP_DELETE"));
    }

    @Test
    @DisplayName("清单不存在时只删 .gz，不报错")
    void deleteWithoutManifest() throws IOException {
        Files.writeString(home.resolve("backups/only.sql.gz"), "dump");

        List<String> deleted = controller.deleteBackup("only.sql.gz", 7L).data();

        assertEquals(List.of("only.sql.gz"), deleted);
    }

    @Test
    @DisplayName("路径穿越与非 .gz 文件名一律 400，且不碰文件系统")
    void rejectTraversalAndWrongExtension() throws IOException {
        Path outside = Files.writeString(home.resolve("secret.gz"), "kill-me");

        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> controller.deleteBackup("../secret.gz", 7L)).errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> controller.deleteBackup("robot.sql", 7L)).errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> controller.deleteBackup("a/b.gz", 7L)).errorCode());
        assertTrue(Files.exists(outside), "被拒的请求不得删除任何文件");
    }

    @Test
    @DisplayName("文件不存在 → 404，不静默成功")
    void notFound() {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> controller.deleteBackup("missing.sql.gz", 7L)).errorCode());
    }

    @Test
    @DisplayName("保留策略：超出 retainCount 的按「旧的先清」列出")
    void retention() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Files.writeString(home.resolve("backups/b" + i + ".sql.gz"), "x");
        }
        // retain-count 是 @Value 注入的字段，测试里直接赋值以避免起 Spring 上下文
        var field = OpsConsoleController.class.getDeclaredField("backupRetainCount");
        field.setAccessible(true);
        field.set(controller, 2);

        var view = controller.retention(7L).data();
        assertEquals(2, view.retainCount());
        assertEquals(3, view.total());
        assertEquals(1, view.overRetention().size(), "3 份保留 2 份，1 份超期");
    }

    @Test
    @DisplayName("非管理员 403，连文件名合法性都不进入")
    void adminOnly() throws IOException {
        Files.writeString(home.resolve("backups/x.sql.gz"), "x");
        AccessControlService denied = mock(AccessControlService.class);
        when(denied.isAdmin()).thenReturn(false);
        OpsConsoleController guarded = new OpsConsoleController(mock(OpsTaskService.class),
                denied, mock(JdbcTemplate.class));

        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> guarded.deleteBackup("x.sql.gz", 7L)).errorCode());
        assertTrue(Files.exists(home.resolve("backups/x.sql.gz")));
    }
}
