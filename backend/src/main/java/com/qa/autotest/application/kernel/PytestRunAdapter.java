package com.qa.autotest.application.kernel;

import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.PabotCommandBuilder;
import com.qa.autotest.application.execution.PabotOrchestrator;
import com.qa.autotest.application.execution.ProcessLauncher;
import com.qa.autotest.application.execution.ProcessRegistry;
import com.qa.autotest.domain.kernel.KernelRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * pytest 内核的执行适配器（v2.7 FR-01）。
 *
 * <p>RF 路径（{@code PabotCommandBuilder} + {@code PabotOrchestrator}）逐字等价不动；
 * pytest 走本适配器：单命令（并行由 xdist 承担，ADR-0007）、无分片合并语义，
 * 复用 {@link ProcessLauncher} / {@link ProcessRegistry}（取消与超时杀树共用一套设施），
 * 产物校验直接用 {@link PytestKernel#normalize}——产物可归一即执行成功。
 *
 * <p>进度走简化两态（started → finished），不接 {@code RunProgressRegistry} 的
 * pabot 行格式解析；日志写 {@code run.log}，与 RF 路径同一查看入口。
 */
@Service
public class PytestRunAdapter {

    private final PytestKernel kernel;
    private final ExecutionProperties props;
    private final ProcessLauncher launcher;
    private final ProcessRegistry registry;
    private final Clock clock;

    public PytestRunAdapter(PytestKernel kernel, ExecutionProperties props,
                            ProcessLauncher launcher, ProcessRegistry registry, Clock clock) {
        this.kernel = kernel;
        this.props = props;
        this.launcher = launcher;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 构造 pytest 命令。argv 来自 {@link PytestKernel#command}（venv pytest + json-report
     * + junitxml + RUN_ID metadata），env 四件套与 {@code PabotCommandBuilder} 同规则。
     */
    public PabotCommandBuilder.PabotCommand build(long projectId, String runId,
                                                  java.util.List<String> suitePaths,
                                                  int processes, Path runDir, Path workDir) {
        KernelRequest request = new KernelRequest(projectId, runId, 0, processes,
                suitePaths, java.util.List.of(), null, "run-" + runId);
        java.util.List<String> argv = kernel.command(request, runDir);
        Path outputDir = runDir.resolve("kernel-pytest-0");

        Map<String, String> env = new LinkedHashMap<>();
        String venvBin = props.getVenvBin() == null ? "" : props.getVenvBin();
        String original = System.getenv().getOrDefault("PATH", "");
        env.put("PATH", venvBin + java.io.File.pathSeparator + original);
        env.put("TZ", "Asia/Shanghai");
        env.put("LANG", "C.UTF-8");
        env.put("PYTHONUNBUFFERED", "1");
        return new PabotCommandBuilder.PabotCommand(java.util.List.copyOf(argv),
                Map.copyOf(env), workDir, outputDir);
    }

    /**
     * 执行并校验。成功 = 退出码 0 且产物可归一（{@code pytest-report.json} 存在、tests 节点可解析）。
     */
    public PabotOrchestrator.ShardResult run(String runId,
                                             PabotCommandBuilder.PabotCommand command,
                                             Duration timeout) {
        Path outputDir = command.outputDir();
        Path runLog = outputDir.getParent() == null
                ? outputDir.resolve("run.log") : outputDir.getParent().resolve("run.log");
        Path stdoutFile = outputDir.resolve("stdout.log");
        Path stderrFile = outputDir.resolve("pytest_stderr.out");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(stdoutFile, "", StandardCharsets.UTF_8);
            Files.writeString(stderrFile, "", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return new PabotOrchestrator.ShardResult(false, "artifact_dir_failed", 0, -1,
                    outputDir.resolve("pytest-report.json"));
        }
        emit(runLog, "[system] run " + runId + " 启动（pytest 内核）");

        ProcessLauncher.ExecutionHandle handle;
        try {
            handle = launcher.launch(command);
        } catch (Exception ex) {
            emit(runLog, "[system] launch failed: " + ex.getMessage());
            return new PabotOrchestrator.ShardResult(false, "launch_failed", 0, -1,
                    outputDir.resolve("pytest-report.json"));
        }
        emit(runLog, "[system] 开始执行 pytest: " + String.join(" ", command.argv()));
        registry.register(runId, handle.process());
        Instant started = clock.instant();
        Thread outThread = pump(handle.stdout(), stdoutFile);
        Thread errThread = pump(handle.stderr(), stderrFile);
        int exitCode = handle.waitFor(timeout);
        registry.unregister(runId);
        if (exitCode == Integer.MIN_VALUE && handle.process() != null) {
            handle.process().descendants().forEach(ProcessHandle::destroyForcibly);
            handle.process().destroyForcibly();
            emit(runLog, "[system] 执行超时，已强制终止 PID=" + handle.pid());
        }
        join(outThread);
        join(errThread);
        emit(runLog, "[system] 进程退出码=" + exitCode
                + "，耗时=" + Duration.between(started, clock.instant()));

        int total;
        try {
            total = kernel.normalize(outputDir, runId).total();
        } catch (Exception ex) {
            emit(runLog, "[system] 产物归一失败: " + ex.getMessage());
            return new PabotOrchestrator.ShardResult(
                    false, "pytest_artifact_invalid: " + ex.getMessage(), 0, exitCode,
                    outputDir.resolve("pytest-report.json"));
        }
        if (exitCode != 0) {
            return new PabotOrchestrator.ShardResult(false, "pytest_exit_" + exitCode,
                    total, exitCode, outputDir.resolve("pytest-report.json"));
        }
        return new PabotOrchestrator.ShardResult(true, "ok", total, exitCode,
                outputDir.resolve("pytest-report.json"));
    }

    private static void emit(Path runLog, String line) {
        try {
            Files.createDirectories(runLog.getParent());
            Files.writeString(runLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // run.log 写不进去不影响执行
        }
    }

    private static Thread pump(InputStream in, Path target) {
        Thread thread = new Thread(() -> {
            try (in; var out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (Exception ignored) {
                // stdout/stderr 落盘失败不影响执行
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
