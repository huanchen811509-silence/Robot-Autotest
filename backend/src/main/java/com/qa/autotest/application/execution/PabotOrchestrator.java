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

        // v5.24.0：把「执行器跑起来了没有」与「用例是否全通过」拆成两个正交结论。
        //   executionOk —— 无整体超时、无启动失败、产物可解析、分片完整：只回答「这一轮有效吗」。
        //   success     —— executionOk 且退出码 0：回答「是否全绿」，语义与旧版一致，
        //                  不动 PytestNicRunController 等既有调用方。
        // 旧版把「退出码非 0」（= 至少一条用例失败）也算进 success，导致用例失败被
        // 当成执行器故障，run 被记成 INFRA，且 reason 明明解析成功却报 "ok"。
        boolean executionOk = !timeoutHit && verified.success() && shards.ok();
        boolean success = executionOk && exitCode == 0 && !testTimedOut;
        String reason;
        if (timeoutHit) {
            reason = "timeout";
        } else if (!shards.ok()) {
            reason = shards.reason();
        } else if (!verified.success()) {
            reason = verified.reason();
        } else if (testTimedOut) {
            reason = "test_timeout";
        } else if (exitCode != 0) {
            // 执行器正常、产物完整，只是有用例没过——必须给一个真原因。
            // 旧版这里沿用 verified.reason()="ok"，于是 run 变成「分片执行失败: ok」。
            reason = "tests_failed";
        } else {
            reason = "ok";
        }
        if (!shards.ok()) {
            emit(runId, runLog, logSink, "[system] 分片不完整: reason=" + shards.reason()
                    + ", 空分片=" + shards.emptyShards());
        }
        if (testTimedOut) {
            emit(runId, runLog, logSink,
                    "[system] 存在单用例超时（Test Timeout 命中），run 记为 test_timeout");
        }
        if (success) {
            emit(runId, runLog, logSink, "[system] 产物校验通过，totalTests=" + verified.totalTests()
                    + "，passed=" + verified.stats().passed()
                    + "，failed=" + verified.stats().failed()
                    + "，skipped=" + verified.stats().skipped());
        } else if (executionOk) {
            // 这一支是新增的：执行事实有效，只是有用例没过。以前它和「跑不起来」混在一起。
            emit(runId, runLog, logSink,
                    "[system] 执行器正常结束，用例未全通过: passed=" + verified.stats().passed()
                            + "，failed=" + verified.stats().failed()
                            + "，skipped=" + verified.stats().skipped()
                            + "，exit=" + exitCode + "，reason=" + reason);
        } else {
            emit(runId, runLog, logSink,
                    "[system] 执行器故障: reason=" + reason + ", exit=" + exitCode);
            diagnose(outputDir, runId, runLog, logSink);
        }
        if (progress != null) {
            progress.phase(runId, "done");
        }
        return new ShardResult(success, reason, verified.totalTests(), exitCode, outputXml,
                executionOk, verified.stats());
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

    /**
     * 一次分片执行的结果。
     *
     * <p><b>{@code success} 与 {@code executionOk} 是两个正交结论，不要混用</b>（v5.24.0）：
     *
     * <ul>
     *   <li>{@code executionOk}：执行器是否正常完成 —— 无整体超时、无启动失败、
     *       产物可解析、分片完整。它只回答「这一轮的执行事实可信吗」。</li>
     *   <li>{@code success}：整单是否<b>全绿</b> —— {@code executionOk} 且退出码 0 且无单用例超时。
     *       语义与 v5.23 之前一致，既有调用方（FINISHED/FAILED 判定）无需改动。</li>
     * </ul>
     *
     * <p>两者组合的含义：
     * <table border="1">
     *   <caption>结果组合</caption>
     *   <tr><th>executionOk</th><th>success</th><th>含义</th><th>failure_class</th></tr>
     *   <tr><td>true</td><td>true</td><td>全部通过</td><td>—（FINISHED）</td></tr>
     *   <tr><td>true</td><td>false</td><td>执行事实有效，有用例未通过</td><td>ASSERT</td></tr>
     *   <tr><td>false</td><td>false</td><td>执行器/环境故障，结果不可信</td><td>INFRA / ENV</td></tr>
     * </table>
     *
     * @param stats 逐用例三态统计；旧构造器给的 {@code unknown} 表示「没读到」，
     *              调用方不得把它的 0 当成「全过」
     */
    public record ShardResult(boolean success, String reason, int totalTests, int exitCode,
                              Path outputXml, boolean executionOk, OutputXmlProbe.Stats stats) {

        public ShardResult {
            stats = stats == null ? OutputXmlProbe.Stats.unknown(totalTests) : stats;
        }

        /**
         * 兼容旧调用：未显式给出执行器结论与统计时，按「success 即全绿」保守推导。
         *
         * <p>只应出现在测试桩与历史调用点；生产链路必须用全参构造，否则统计会退化成
         * 「全过 or 全不过」的旧口径。
         */
        public ShardResult(boolean success, String reason, int totalTests, int exitCode,
                           Path outputXml) {
            this(success, reason, totalTests, exitCode, outputXml, success,
                    OutputXmlProbe.Stats.unknown(totalTests));
        }

        /** 执行器正常但有用例未通过 —— 「用例失败」，不是「跑不起来」。 */
        public boolean testsFailed() {
            return executionOk && !success;
        }
    }

    public static final class ExecutionHandleCodes {
        public static final int TIMEOUT = Integer.MIN_VALUE;

        private ExecutionHandleCodes() {
        }
    }
}
