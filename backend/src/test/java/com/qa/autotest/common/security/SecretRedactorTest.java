package com.qa.autotest.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-v4.0-01 的三类样本：键值 / 命令行短参 / URL 内嵌。
 *
 * <p>每个用例既断言「被抹掉」，也断言「正常参数没被误伤」——后者才是回归里最容易翻车的地方。
 */
class SecretRedactorTest {

    @Test
    @DisplayName("键值形态：password=xxx / JSON / 环境变量前缀")
    void masksKeyValue() {
        assertThat(SecretRedactor.redact("mysql -uroot password=Hunter2 -e 'select 1'"))
                .isEqualTo("mysql -uroot password=<redacted> -e 'select 1'");
        assertThat(SecretRedactor.redact("{\"token\":\"abc.def\"}"))
                .isEqualTo("{\"token\":\"<redacted>\"}");
        assertThat(SecretRedactor.redact("MYSQL_PWD=Hunter2 robot run"))
                .isEqualTo("MYSQL_PWD=<redacted> robot run");
        assertThat(SecretRedactor.redact("Authorization: Bearer abc.def.ghi"))
                .contains("Bearer <redacted>");
        // 审计历史里的中文键名也归本类管（W40-01 收敛后不留第二份正则）
        assertThat(SecretRedactor.redact("口令=mm; 密码=pw; 密钥=k"))
                .isEqualTo("口令=<redacted>; 密码=<redacted>; 密钥=<redacted>");
    }

    @Test
    @DisplayName("命令行短参：-u root:pass 保留用户名，sshpass / --password 一并覆盖")
    void masksCliForms() {
        assertThat(SecretRedactor.redact("mysql -u root:Hunter2 -h db"))
                .isEqualTo("mysql -u root:<redacted> -h db");
        assertThat(SecretRedactor.redact("sshpass -p Hunter2 ssh root@host"))
                .isEqualTo("sshpass -p <redacted> ssh root@host");
        assertThat(SecretRedactor.redact("tool --password=Hunter2 --token abc"))
                .isEqualTo("tool --password=<redacted> --token <redacted>");
        assertThat(SecretRedactor.redact("tool --user root --password Hunter2"))
                .isEqualTo("tool --user root --password <redacted>");
    }

    @Test
    @DisplayName("URL 内嵌：scheme://user:pass@host")
    void masksUrlCredential() {
        assertThat(SecretRedactor.redact("jdbc:mysql://root:Hunter2@10.0.0.1:3306/db"))
                .isEqualTo("jdbc:mysql://root:<redacted>@10.0.0.1:3306/db");
    }

    @Test
    @DisplayName("正常参数不误伤：-p 22 是端口，-u root 无口令")
    void keepsInnocentArgs() {
        assertThat(SecretRedactor.redact("ssh -p 22 -u root 10.0.0.1"))
                .isEqualTo("ssh -p 22 -u root 10.0.0.1");
        assertThat(SecretRedactor.redact("robot --variable TIMEOUT:30s run.robot"))
                .isEqualTo("robot --variable TIMEOUT:30s run.robot");
        assertThat(SecretRedactor.hasLeak("ssh -p 22 -u root 10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("幂等：已脱敏文本再跑一次不变")
    void idempotent() {
        String once = SecretRedactor.redact("mysql -u root:Hunter2 --password=Hunter2");
        assertThat(SecretRedactor.redact(once)).isEqualTo(once);
        assertThat(SecretRedactor.hasLeak(once)).isFalse();
    }

    @Test
    @DisplayName("导出闸门：残留明文直接阻断，且错误消息不回显凭据")
    void assertCleanBlocks() {
        assertThatThrownBy(() -> SecretRedactor.assertClean(
                "mysql -u root:Hunter2@10.0.0.1", "docx"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("docx")
                .hasMessageNotContaining("Hunter2");
    }
}
