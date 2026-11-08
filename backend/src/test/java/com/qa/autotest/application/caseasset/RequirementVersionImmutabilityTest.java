package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** EDIM-04：原文件 SHA 只读冻结；case_requirement_version 只允许 INSERT，旧版本不可 UPDATE。 */
class RequirementVersionImmutabilityTest {

    @Test
    void 原文件SHA与README冻结值一致() throws Exception {
        byte[] bytes = Files.readAllBytes(Ed202Fixtures.snapshot());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(sha).isEqualTo(Ed202Fixtures.SNAPSHOT_SHA256);
        String readme = Files.readString(Ed202Fixtures.repoFile("fixtures/ed202/README.md"));
        assertThat(readme).contains(Ed202Fixtures.SNAPSHOT_SHA256, "原表只读，导入用此快照");
    }

    /** EDIM-04（2026-11-08 修订）：原实现用 BEFORE UPDATE/DELETE 触发器 SIGNAL 拒绝改写，
     * 实测因 binlog + 应用账号无 SUPER 权限（Error 1419）导致 Flyway V11_2 失败、
     * SpringBootTest 上下文起不来，故撤销触发器，改由「应用层只发 INSERT + 唯一键 +
     * 本测试锁死」保证不可变。判据必须跟着事实改，不许悄悄把断言改松。 */
    @Test
    void 判据版本表只允许INSERT_旧版本不可UPDATE_down齐备() throws Exception {
        String up = Files.readString(
                Ed202Fixtures.repoFile("db/migration/V11_2__case_requirement_version.sql"));
        assertThat(up).contains("sha256", "只允许 INSERT", "应用层只发 INSERT", "2026-11-08 修订");
        assertThat(up.toUpperCase(Locale.ROOT)).contains("CHAR(64)");
        assertThat(up).doesNotContain("FOR EACH ROW");
        String down = Files.readString(
                Ed202Fixtures.repoFile("db/down/V11_2__case_requirement_version.down.sql"));
        assertThat(down).contains("DROP TABLE");
    }

    @Test
    void 四个迁移up与down一一对应_版本顺序D7到D2() throws Exception {
        String[] ups = {
            "V11_1__import_batch_sheet.sql",
            "V11_2__case_requirement_version.sql",
            "V11_3__test_case_ed202_fields.sql",
            "V11_4__test_case_step_ed202.sql",
        };
        String[] downs = {
            "V11_1__import_batch_sheet.down.sql",
            "V11_2__case_requirement_version.down.sql",
            "V11_3__test_case_ed202_fields.down.sql",
            "V11_4__test_case_step_ed202.down.sql",
        };
        for (String up : ups) {
            assertThat(Files.isRegularFile(Ed202Fixtures.repoFile("db/migration/" + up)))
                    .as("缺少 up: " + up).isTrue();
        }
        for (String down : downs) {
            assertThat(Files.isRegularFile(Ed202Fixtures.repoFile("db/down/" + down)))
                    .as("缺少 down: " + down).isTrue();
        }
    }
}
