package com.qa.autotest.application.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * CLI 子进程真正起停（FR-AI-01 / FR-AI-08）。
 *
 * <p>只有 {@link AiFeatureGate#realCliFeatureEnabled()} 为真才调用 ProcessBuilder；
 * 默认关闭，绝不碰真实 CLI / 真实模型。stdout / stderr 分线程读取避免管道死锁，
 * 超时按进程树终止，返回结构化结果。启动失败 / 开关关闭都返回 executed=false，
 * 作为显式 DEGRADED 而不是抛异常阻断主功能。
 */
@Component
public class AiProcessManager {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final String TIMEOUT_PROPERTY = "AI_CLI_TIMEOUT_SECONDS";

    private final int timeoutSeconds;

    public AiProcessManager() {
        this.timeoutSeconds = readTimeoutSeconds();
    }

    private static int readTimeoutSeconds() {
        String raw = System.getProperty(TIMEOUT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(TIMEOUT_PROPERTY);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_SECONDS;
        } catch (NumberFormatException ex) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * 兼容既有单测 AiPanelTest.java:104 的历史 argv。
     * 仅用于断言「命令长什么样」，真实链路一律走 {@link AiCliAdapter#command}；
     * 注意这里不再拼任何 prompt 文件开关（真实 claude CLI 拒收）。
     */
    @Deprecated
    public List<String> buildCommand(String cli, String promptFile) {
        List<String> argv = new ArrayList<>();
        argv.add(cli);
        argv.add("--print");
        argv.add("--output-format");
        argv.add("text");
        argv.add(promptFile);
        return List.copyOf(argv);
    }

    public String encoding() {
        return StandardCharsets.UTF_8.name();
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 真实起子进程；开关未开或启动失败时返回 executed=false，不抛、不阻断主功能。 */
    public AiProcessResult execute(String cli, String prompt) {
        String name = cli == null || cli.isBlank() ? "claude" : cli;
        if (!AiFeatureGate.realCliFeatureEnabled()) {
            return AiProcessResult.notExecuted(name, "FEATURE_AI_REAL_CLI 未开启，不起子进程");
        }
        List<String> argv = AiCliAdapter.command(name, prompt);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(false);
        forwardFixtureControls(builder);

        long start = System.nanoTime();
        final Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            return AiProcessResult.notExecuted(name, "启动子进程失败: " + ex.getMessage());
        }
        ProcessHandle handle = process.toHandle();
        closeChildStdin(process);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = new Thread(() -> copy(process.getInputStream(), out), "ai-cli-stdout");
        Thread errReader = new Thread(() -> copy(process.getErrorStream(), err), "ai-cli-stderr");
        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        boolean finished = false;
        boolean interrupted = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            interrupted = true;
            Thread.currentThread().interrupt();
        }
        boolean timedOut = !finished;
        if (timedOut || interrupted) {
            terminate(handle);
        }
        int exitCode = finished ? process.exitValue() : -1;
        joinQuietly(outReader);
        joinQuietly(errReader);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        String reason = timedOut ? "超时，进程树已终止"
                : (exitCode == 0 ? "OK" : "CLI 退出码=" + exitCode);
        return new AiProcessResult(true, timedOut, name,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                exitCode, durationMs, handle, reason);
    }

    /**
     * 立刻关闭子进程的标准输入，给它一个 EOF。
     *
     * <p>非交互 CLI 仍可能读 stdin（真实 codex 会先打印
     * {@code Reading additional input from stdin...} 再等）。父进程不关 stdin 时，
     * 子进程会一直阻塞到超时——2026-11-08 实测：同一个 codex 直连 14s 成功，
     * 走本类 60s 超时降级，stderr 只有那一行。关闭后行为与 shell 里
     * {@code < /dev/null} 一致，回归见 {@code AiProcessManagerStdinTest}。
     */
    private static void closeChildStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 子进程可能已退出并关闭管道；此时已无等待可言，忽略
        }
    }

    /** 一键终止：先后代再自身，复用 W3 的进程树语义。null / 已死进程直接跳过。 */
    public void terminate(ProcessHandle root) {
        if (root == null || !root.isAlive()) {
            return;
        }
        root.descendants().forEach(child -> child.destroyForcibly());
        root.destroyForcibly();
    }

    /** 把 FAKE_CLI_* 系统属性透传成子进程环境变量，供 fixtures/ai/fake-cli.sh 注入测试。 */
    private static void forwardFixtureControls(ProcessBuilder builder) {
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("FAKE_CLI_")) {
                builder.environment().put(key, String.valueOf(entry.getValue()));
            }
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (InputStream source = in) {
            source.transferTo(out);
        } catch (IOException ignored) {
            // 进程被终止时管道会提前关闭；已读到的部分仍然有效
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
