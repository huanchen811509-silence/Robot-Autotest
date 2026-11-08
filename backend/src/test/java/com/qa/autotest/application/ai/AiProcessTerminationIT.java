package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.ai.AiActionResult;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * TC-AI-08：假 CLI sleep 超时后进程树被杀且不留孤儿；terminate 拿到真实进程句柄。
 * 需要 -Pit 显式运行；只接 fixtures/ai/fake-cli.sh，不碰真实 CLI / 模型。
 */
@Tag("it")
class AiProcessTerminationIT {

    @Test
    void timeoutKillsProcessTreeWithoutOrphans() throws Exception {
        Runnable restore = AiTestSupport.applyProperties(Map.of(
                "FEATURE_AI_REAL_CLI", "true",
                "AI_CLI_PATH", AiTestSupport.fakeCli().toString(),
                "AI_CLI_TIMEOUT_SECONDS", "1",
                "FAKE_CLI_SLEEP", "5",
                "FAKE_CLI_STDOUT", "FAKE_CLI_OK:should-not-arrive"));
        try {
            AiProcessResult result = new AiProcessManager().execute("claude", "timeout-probe");
            assertThat(result.executed()).isTrue();
            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(-1);
            assertThat(result.handle()).isNotNull();

            ProcessHandle handle = result.handle();
            long deadline = System.currentTimeMillis() + 3000L;
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertThat(handle.isAlive()).isFalse();
            assertThat(handle.descendants().anyMatch(ProcessHandle::isAlive)).isFalse();
        } finally {
            restore.run();
        }
    }

    @Test
    void terminateUsesRealProcessHandle() throws Exception {
        Process sleeping = new ProcessBuilder("bash", "-c", "sleep 30").start();
        ProcessHandle handle = sleeping.toHandle();
        AiPanelService service = AiTestSupport.service(AiTestSupport.stubProbe(false),
                AiFeatureGate.permissive(), new AiProcessManager(), null);
        long session = service.open(1L, 2L);

        AiActionResult result = service.terminate(session, handle);
        assertThat(result.action()).isEqualTo("TERMINATE");
        assertThat(result.audited()).isTrue();

        long deadline = System.currentTimeMillis() + 3000L;
        while (handle.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(handle.isAlive()).isFalse();
    }
}
