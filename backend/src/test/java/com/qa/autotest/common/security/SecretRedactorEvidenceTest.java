package com.qa.autotest.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 脱敏证据产出（FR-v4.0-01 / TC-v4.0-03）。
 *
 * <p>不只断言「脱敏结果对」，还把三类真实写法 + 两条不误伤样本写成
 * {@code target/evidence/redaction-cases.json}，由 {@code scripts/redaction-evidence.sh}
 * 拷到 {@code docs/evidence/v4.0.0/EW40-01/}，门禁第 28 步直接读这份证据。
 *
 * <p>为什么证据要从测试里出：手写 JSON 只能证明「我以为它会这样」，测试里出的是
 * 「实现当下确实这样」。改了规则却没重跑，证据就停在旧版本 —— 门禁读到的是过期事实。
 */
class SecretRedactorEvidenceTest {

    private record Case(String id, String kind, String surface, String input, String secret,
                        String expectedMasked, boolean innocent) {
    }

    private static final List<Case> CASES = List.of(
            new Case("kv-password", "kv", "run-log", "mysql -uroot password=hunter2 -e 'select 1'",
                    "hunter2", "mysql -uroot password=<redacted> -e 'select 1'", false),
            new Case("kv-json", "kv", "api-json", "{\"token\":\"tk_9f8e7d6c\"}",
                    "tk_9f8e7d6c", "{\"token\":\"<redacted>\"}", false),
            new Case("bearer", "bearer", "http", "Authorization: Bearer eyJhbGciOi.eyJzdWIi.c2ln",
                    "eyJhbGciOi.eyJzdWIi.c2ln", "Authorization: Bearer <redacted>", false),
            new Case("url-embedded", "url", "report", "jdbc:mysql://root:s3cretP@10.0.0.1:3306/qa",
                    "s3cretP", "jdbc:mysql://root:<redacted>@10.0.0.1:3306/qa", false),
            new Case("cli-user", "cli", "console", "robot -u admin:Adm1nPw -t smoke.robot",
                    "Adm1nPw", "robot -u admin:<redacted> -t smoke.robot", false),
            new Case("cli-sshpass", "cli", "console", "sshpass -p Tp9xQ ssh root@10.0.0.1",
                    "Tp9xQ", "sshpass -p <redacted> ssh root@10.0.0.1", false),
            new Case("cli-long-opt", "cli", "console", "mysql --password=Pg9wX -h 10.0.0.2",
                    "Pg9wX", "mysql --password=<redacted> -h 10.0.0.2", false),
            new Case("ok-ssh-port", "innocent", "console", "ssh -p 22 -u root@10.0.0.1 uptime",
                    "", "ssh -p 22 -u root@10.0.0.1 uptime", true),
            new Case("ok-rf-variable", "innocent", "console", "robot --variable TIMEOUT:30s smoke.robot",
                    "", "robot --variable TIMEOUT:30s smoke.robot", true));

    @Test
    void 证据与断言同源() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Case c : CASES) {
            String masked = SecretRedactor.redact(c.input());
            assertThat(masked).as("%s 脱敏结果", c.id()).isEqualTo(c.expectedMasked());
            boolean leakedBefore = SecretRedactor.hasLeak(c.input());
            boolean leakedAfter = SecretRedactor.hasLeak(masked);
            assertThat(leakedBefore).as("%s 原文应判定含凭据", c.id()).isEqualTo(!c.innocent());
            assertThat(leakedAfter).as("%s 脱敏后不应再判含凭据（幂等）", c.id()).isFalse();
            if (!c.innocent()) {
                assertThat(masked).as("%s 脱敏后不得残留口令原文", c.id()).doesNotContain(c.secret());
            }
            if (c.innocent()) {
                assertThat(masked).as("%s 不应被误脱敏", c.id()).isEqualTo(c.input());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id());
            row.put("class", c.kind());
            row.put("surface", c.surface());
            row.put("input", c.input());
            row.put("secret", c.secret());
            row.put("masked", masked);
            row.put("leakedBefore", leakedBefore);
            row.put("leakedAfter", leakedAfter);
            row.put("innocent", c.innocent());
            rows.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", java.time.OffsetDateTime.now().toString());
        payload.put("maskedToken", SecretRedactor.MASK);
        payload.put("source", "SecretRedactorEvidenceTest");
        payload.put("cases", rows);

        Path out = Path.of("target", "evidence", "redaction-cases.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), payload);
        assertThat(out).exists();
    }
}
