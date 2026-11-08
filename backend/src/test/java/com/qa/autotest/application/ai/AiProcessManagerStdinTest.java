package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * FR-AI-08 / TC-AI-11 衍生回归：子进程的 stdin 必须由父进程显式关闭。
 *
 * <p>真实 codex CLI 在会话开始时会读 stdin（非交互模式也不跳过），父进程不关 stdin
 * 时它只打印 "Reading additional input from stdin..." 然后一直等到超时。2026-11-08
 * 用真实 CLI 跑后台链路时实测到这一行为：直连 14s 成功，走后端 60s 超时降级。
 *
 * <p>本用例用 {@code fixtures/ai/stdin-eof-probe.sh} 把「是否关 stdin」变成一个可判定的
 * 红/绿：父进程不关 stdin → 阻塞到超时 → timedOut=true；关了就立刻拿到 STDIN_EOF_OK。
 */
class AiProcessManagerStdinTest {

    private Runnable restore;

    @AfterEach
    void tearDown() {
        if (restore != null) {
            restore.run();
        }
    }

    private static Path stdinProbe() {
        for (Path candidate : new Path[] {
                Path.of("..", "fixtures", "ai", "stdin-eof-probe.sh"),
                Path.of("fixtures", "ai", "stdin-eof-probe.sh")}) {
            Path path = candidate.toAbsolutePath().normalize();
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        throw new IllegalStateException("stdin 探针夹具不存在或不可执行");
    }

    @Test
    void closesChildStdinSoNonInteractiveCliDoesNotBlock() {
        restore = AiTestSupport.applyProperties(Map.of(
                "FEATURE_AI_REAL_CLI", "true",
                "AI_CLI_PATH", stdinProbe().toString(),
                "AI_CLI_TIMEOUT_SECONDS", "10"));

        // 注意：超时值在构造时读取，必须在设置属性之后创建实例。
        AiProcessResult result = new AiProcessManager().execute("claude", "hello");

        assertThat(result.executed()).isTrue();
        assertThat(result.timedOut())
                .as("子进程阻塞在 stdin 上说明父进程没有关闭它的标准输入")
                .isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("STDIN_EOF_OK");
    }
}
