package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.ai.AiActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-AI-06：L2 建议输出含假 CLI 模型回显，模板串不再是唯一输出。 */
class AiSuggestContentTest {

    private static final String ECHO = "FAKE_CLI_OK:L2-MODEL-REPLY-42";

    private Runnable restore;
    private AiPanelService service;

    @BeforeEach
    void setUp() {
        restore = AiTestSupport.applyProperties(Map.of(
                "FEATURE_AI_REAL_CLI", "true",
                "AI_CLI_PATH", AiTestSupport.fakeCli().toString(),
                "FAKE_CLI_STDOUT", ECHO));
        service = AiTestSupport.service(AiTestSupport.stubProbe(true),
                AiFeatureGate.permissive(), new AiProcessManager(), null);
    }

    @AfterEach
    void tearDown() {
        if (restore != null) {
            restore.run();
        }
    }

    @Test
    void suggestSummaryContainsModelEchoNotOnlyTemplate() {
        long session = service.open(1L, 2L);
        AiActionResult result = service.suggest(session, Map.of("runId", "RUN-1"));
        assertThat(result.level()).isEqualTo("L2");
        assertThat(result.degraded()).isFalse();
        assertThat(result.summary()).contains(ECHO);
        assertThat(result.summary()).doesNotContain("已显式降级");
        assertThat(result.stdout()).isEqualTo(ECHO + "\n");
    }

    @Test
    void echoIsIndependentOfPackedContextLength() {
        long session = service.open(1L, 2L);
        Map<String, String> small = Map.of("k", "v");
        Map<String, String> large = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            large.put("key-" + i, "value-" + i);
        }
        String echoSmall = service.suggest(session, small).stdout();
        String echoLarge = service.suggest(session, large).stdout();
        assertThat(echoSmall).contains(ECHO);
        assertThat(echoLarge).contains(ECHO);
        assertThat(echoSmall).isEqualTo(echoLarge);
    }

    @Test
    void chatAnswerIsTheModelEcho() {
        Map<String, Object> reply = service.chat(1L, 0L, "请解释这条失败用例");
        assertThat(String.valueOf(reply.get("answer"))).contains(ECHO);
        assertThat(reply.get("mode")).isEqualTo("NORMAL");
    }
}
