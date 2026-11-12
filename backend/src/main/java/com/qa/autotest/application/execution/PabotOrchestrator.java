package com.qa.autotest.application.execution;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 单分片执行：双日志边跑边写，退出码不可信，最终以产物校验为准。
 *
 * <p>实时推送与全量落盘分开：{@code run.log} 一行不落（不受 50 行/100ms 限流影响），
 * 推送走 {@link RunLogService}。
 */
@Service
public class PabotOrchestrator {

    private final ProcessLauncher launcher;
    private final ArtifactVerifier verifier;
    private final ProcessRegistry registry;
    private final RunLogService logs;
    private final Clock clock;
    private final RunProgressRegistry progress;
    private final com.qa.autotest.application.execution.monitor.MonitorRegistry monitors;

    public PabotOrchestrator(ProcessLauncher launcher, ArtifactVerifier verifier,
                             ProcessRegistry registry, RunLogService logs, Clock clock) {
        this(launcher, verifier, registry, logs, clock, null);
    }

    public PabotOrchestrator(ProcessLauncher launcher, ArtifactVerifier verifier,
                             ProcessRegistry registry, RunLogService logs, Clock clock,
                             RunProgressRegistry progress) {
        this(launcher, verifier, registry, logs, clock, progress, null);
    }

    /**
     * @param monitors 执行监视器注册表（W53-01）。可为 null —— 监视是观测手段，
     *                 不参与执行与判定；为 null 时一切照旧，只是没有监视数据。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PabotOrchestrator(ProcessLauncher launcher, ArtifactVerifier verifier,
                             ProcessRegistry registry, RunLogService logs, Clock clock,
                             RunProgressRegistry progress,
                             com.qa.autotest.application.execution.monitor.MonitorRegistry monitors) {
        this.launcher = launcher;
        this.verifier = verifier;
        this.registry = registry;
        this.logs = logs;
        this.clock = clock;
        this.progress = progress;
        this.monitors = monitors;
    }

    public ShardResult runShard(String runId,
                                PabotCommandBuilder.PabotCommand command,
                                Duration timeout,
                                Consumer<String> logSink) {
        Path outputDir = command.outputDir();
        Path runLog = outputDir.getParent() == null
                ? outputDir.resolve("run.log") : outputDir.getParent().resolve("run.log");
        Path stdoutFile = outputDir.resolve("stdout.log");
        Path stderrFile = outputDir.resolve("robot_stderr.out");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(stdoutFile, "", StandardCharsets.UTF_8);
            Files.writeString(stderrFile, "", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return new ShardResult(false, "artifact_dir_failed", 0, -1,
                    outputDir.resolve("output.xml"));
        }
        emit(runId, runLog, logSink, "[system] run " + runId + " 启动");

        ProcessLauncher.ExecutionHandle handle;
        try {
            handle = launcher.launch(command);
        } catch (Exception ex) {
            emit(runId, runLog, logSink, "[system] launch failed: " + ex.getMessage());
            return new ShardResult(false, "launch_failed", 0, -1, outputDir.resolve("output.xml"));
        }

        emit(runId, runLog, logSink, "[system] 开始执行 pabot: " + String.join(" ", command.argv()));
        registry.register(runId, handle.process());
        if (progress != null) {
            progress.begin(runId);
            progress.phase(runId, "running");
        }
        Instant started = clock.instant();
        // G-B2-1：worker 的 Log To Console 只写 pabot_results/<n>/robot_stdout.out，
        // 单靠 pabot 主进程 stdout 会漏掉全部用例输出，这里并行跟踪并合入同一频道。
        WorkerLogTailer workerTail = new WorkerLogTailer(outputDir,
                line -> emit(runId, runLog, logSink, line));
        workerTail.start();
        Thread outThread = pump(runId, runLog, handle.stdout(), stdoutFile, "stdout", logSink);
        Thread errThread = pump(runId, runLog, handle.stderr(), stderrFile, "stderr", logSink);
        int exitCode = handle.waitFor(timeout);
        registry.unregister(runId);
        boolean timeoutHit = exitCode == ExecutionHandleCodes.TIMEOUT;
        if (timeoutHit && handle.process() != null) {
            handle.process().descendants().forEach(ProcessHandle::destroyForcibly);
            handle.process().destroyForcibly();
            emit(runId, runLog, logSink, "[system] 执行超时，已强制终止 PID=" + handle.pid());
        }
        join(outThread);
        join(errThread);
        workerTail.close();
        emit(runId, runLog, logSink, "[system] 进程退出码=" + exitCode
                + "，耗时=" + Duration.between(started, clock.instant()));

        if (progress != null) {
            progress.phase(runId, "reporting");
        }
        Path outputXml = outputDir.resolve("output.xml");
        ArtifactVerifier.VerifyResult verified = verifier.verify(outputXml);
        // 分片完整性：被杀分片的 output.xml 是 0 字节，合并后的 totalTests 只会变小、
        // 不会变错，所以必须单独查一遍分片，否则「用例凭空消失」会被判成全绿。
        ArtifactVerifier.ShardIntegrity shards = verifier.checkShards(outputDir);
        // FR-v0.4-24：单用例超时（套件级 Test Timeout）不是整体超时，但 run 原因要能区分。
        boolean testTimedOut = !timeoutHit && verifier.testTimedOut(outputXml);
        String reason = timeoutHit ? "timeout"
                : (!shards.ok() ? shards.reason()
                : (testTimedOut ? "test_timeout" : verified.reason()));
        boolean success = !timeoutHit && !testTimedOut && exitCode == 0
                && verified.success() && shards.ok();
        if (!shards.ok()) {
            emit(runId, runLog, logSink, "[system] 分片不完整: reason=" + shards.reason()
                    + ", 空分片=" + shards.emptyShards());
        }
        if (testTimedOut) {
            emit(runId, runLog, logSink,
                    "[system] 存在单用例超时（Test Timeout 命中），run 记为 test_timeout");
        }
        if (success) {
            emit(runId, runLog, logSink, "[system] 产物校验通过，totalTests=" + verified.totalTests());
        } else {
            emit(runId, runLog, logSink,
                    "[system] 产物校验失败: reason=" + reason + ", exit=" + exitCode);
            diagnose(outputDir, runId, runLog, logSink);
        }
        if (progress != null) {
            progress.phase(runId, "done");
        }
        return new ShardResult(success, reason, verified.totalTests(), exitCode, outputXml);
    }

    private void diagnose(Path outputDir, String runId, Path runLog, Consumer<String> logSink) {
        Path manager = outputDir.resolve("pabot_results").resolve("pabot_manager.log");
        if (Files.exists(manager)) {
            emit(runId, runLog, logSink, "[system] 诊断顺序 1/3: pabot_manager.log 存在 " + manager);
        }
        Path robotErr = outputDir.resolve("robot_stderr.out");
        if (Files.exists(robotErr)) {
            emit(runId, runLog, logSink, "[system] 诊断顺序 2/3: robot_stderr.out 存在 " + robotErr);
        }
        emit(runId, runLog, logSink,
                "[system] 诊断顺序 3/3: stdout.log 存在 " + outputDir.resolve("stdout.log"));
    }

    private Thread pump(String runId, Path runLog, InputStream stream, Path target,
                        String source, Consumer<String> logSink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
                 BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    emit(runId, runLog, logSink, "[" + source + "] " + line);
                }
            } catch (Exception ex) {
                emit(runId, runLog, logSink, "[" + source + "] 读取失败: " + ex.getMessage());
            }
        }, "pabot-" + source);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void join(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一条日志三处落点：实时频道、run.log 全量、调用方 sink；顺带喂进度解析。 */
    private void emit(String runId, Path runLog, Consumer<String> logSink, String line) {
        if (progress != null) {
            progress.onLine(runId, line);
        }
        // W53-01：同一行也喂监视器（内部吞掉一切异常，绝不影响执行）
        if (monitors != null) {
            monitors.feed(runId, "robot", line);
        }
        if (logs != null) {
            logs.publish(runId, "pabot", line);
        }
        if (logSink != null) {
            logSink.accept(line);
        }
        append(runLog, line);
    }

    private void append(Path runLog, String line) {
        try {
            Files.writeString(runLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 全量落盘失败不应中断执行；实时频道仍可用。
        }
    }

    public record ShardResult(boolean success, String reason, int totalTests, int exitCode,
                              Path outputXml) {
    }

    public static final class ExecutionHandleCodes {
        public static final int TIMEOUT = Integer.MIN_VALUE;

        private ExecutionHandleCodes() {
        }
    }
}
