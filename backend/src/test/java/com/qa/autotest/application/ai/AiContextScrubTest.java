package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.ai.AiActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-AI-09：脱敏复用 ContextPacker；上下文、stdout、响应均无凭据原文。 */
class AiContextScrubTest {

    private Runnable restore;
    private AiPanelService service;

    @BeforeEach
    void setUp() {
        restore = AiTestSupport.applyProperties(Map.of(
                "FEATURE_AI_REAL_CLI", "true",
                "AI_CLI_PATH", AiTestSupport.fakeCli().toString()));
        service = AiTestSupport.service(AiTestSupport.stubProbe(true),
                AiFeatureGate.permissive(), new AiProcessManager(), null);
    }

    @AfterEach
    void tearDown() {
        if (restore != null) {
            restore.run();
        }
    }

    private static Map<String, String> sensitiveContext() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("command", "ssh root@10.202.54.106 password=abc123 token: 'ghp_secret'");
        context.put("dut", "10.202.54.106");
        context.put("log", "apiKey=ABCDEFGHIJKLMNOP");
        return context;
    }

    @Test
    void packedContextHasNoCredentials() {
        ContextPacker packer = new ContextPacker();
        String packed = packer.pack(sensitiveContext(), ContextPacker.DEFAULT_MAX_CHARS);
        assertThat(packed).doesNotContain("10.202.54.106", "password=abc123", "ghp_secret");
        assertThat(packer.containsSensitive(packed)).isFalse();
    }

    @Test
    void fakeCliStdoutAndSummaryNeverContainCredentials() {
        long session = service.open(1L, 2L);
        AiActionResult result = service.suggest(session, sensitiveContext());
        assertThat(result.stdout()).contains("FAKE_CLI_OK");
        assertThat(result.stdout()).doesNotContain("10.202.54.106", "password=abc123",
                "ghp_secret", "apiKey=");
        assertThat(result.summary()).doesNotContain("password=abc123", "ghp_secret");
        assertThat(result.stderr()).isEmpty();
    }
}
