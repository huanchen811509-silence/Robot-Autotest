package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.ai.AiActionResult;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TC-AI-01：假 CLI 真起 ProcessBuilder 子进程，stdout 进入 suggest / execute / chat 返回体。
 * 全程只注入 fixtures/ai/fake-cli.sh，绝不调用真实 CLI 或模型。
 */
class AiRealCliChainTest {

    private Runnable restore;
    private AiPanelService service;

    @BeforeEach
    void setUp() {
        String fakeCli = AiTestSupport.fakeCli().toString();
        restore = AiTestSupport.applyProperties(Map.of(
                "FEATURE_AI_REAL_CLI", "true",
                "AI_CLI_PATH", fakeCli,
                "FAKE_CLI_STDOUT", "FAKE_CLI_OK:chain-echo",
                "FAKE_CLI_EXIT", "0"));
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
    void suggestSpawnsRealSubprocessAndReturnsStdout() {
        long session = service.open(1L, 2L);
        AiActionResult result = service.suggest(session, Map.of("runId", "RUN-1"));
        assertThat(result.degraded()).isFalse();
        assertThat(result.level()).isEqualTo("L2");
        assertThat(result.stdout()).contains("FAKE_CLI_OK:chain-echo");
        assertThat(result.summary()).contains("FAKE_CLI_OK:chain-echo");
        assertThat(result.exitCode()).isZero();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.audited()).isTrue();
    }

    @Test
    void executeSpawnsRealSubprocessAndReturnsStdout() {
        long session = service.open(1L, 2L);
        AiActionResult result = service.execute(session, "run-1", true);
        assertThat(result.degraded()).isFalse();
        assertThat(result.level()).isEqualTo("L3");
        assertThat(result.stdout()).contains("FAKE_CLI_OK:chain-echo");
        assertThat(service.handleFor(session)).isNotNull();
    }

    @Test
    void chatReturnsModelAnswerAndSessionId() {
        Map<String, Object> reply = service.chat(1L, 0L, "请分析失败原因");
        assertThat(String.valueOf(reply.get("answer"))).contains("FAKE_CLI_OK:chain-echo");
        assertThat(((Number) reply.get("sessionId")).longValue()).isPositive();
        assertThat(reply.get("degraded")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(reply.get("stdout"))).contains("FAKE_CLI_OK:chain-echo");
    }

    @Test
    void exitCodeAndStderrAreCaptured() {
        Runnable local = AiTestSupport.applyProperties(Map.of(
                "FAKE_CLI_STDERR", "FAKE_CLI_WARN:stderr",
                "FAKE_CLI_EXIT", "3"));
        try {
            AiProcessResult result = new AiProcessManager().execute("claude", "probe");
            assertThat(result.executed()).isTrue();
            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stderr()).contains("FAKE_CLI_WARN:stderr");
        } finally {
            local.run();
        }
    }
}
