package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.execution.PabotCommandBuilder;
import com.qa.autotest.application.execution.PlanActualGap;
import com.qa.autotest.application.execution.ProcessLauncher;
import com.qa.autotest.application.execution.ProcessRegistry;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * pytest 工程执行编排（FR-v5.1-01/02/07/08 的串接点）。
 *
 * <p>一次执行 = 渲染 → 探契约 → 构命令 → 起进程 → 归一。五步全部留证据：
 * <ul>
 *   <li>渲染产物 {@code inventory.yaml / run_env.yaml} 落 runDir 并记 sha256；</li>
 *   <li>契约结论落 {@code contract.json}，事后能回答「当时凭什么这么发命令」；</li>
 *   <li>每批一条 {@code stdout-batch-N.log} / {@code stderr-batch-N.log}；</li>
 *   <li>归一产物 {@code junit*.xml} 与 sidecar {@code run_metadata.json} 并列；</li>
 *   <li>过程写 {@code run.log}，与 RF 路径同一查看入口。</li>
 * </ul>
 *
 * <p><b>与 {@code PytestRunAdapter} 的分工</b>：那个类走的是「json-report + 无 env 渲染」
 * 的老路线，在自研用例库上起不来（实测 {@code unrecognized arguments: --json-report}）。
 * 本类是新路线，只在工程配置了 {@code pytest.repo_path} 时启用；两者并存，旧路线不删。
 */
@Service
public class PytestNicRunService {

    private final PytestEnvRenderer renderer;
    private final PytestNicKernelCommand commands;
    private final PytestContractProbe probe;
    private final PytestResultNormalizer normalizer;
    private final ProjectSettingRepository settings;
    private final ProcessLauncher launcher;
    private final ProcessRegistry registry;
    private final Clock clock;

    public PytestNicRunService(PytestEnvRenderer renderer, PytestNicKernelCommand commands,
                               PytestContractProbe probe, PytestResultNormalizer normalizer,
                               ProjectSettingRepository settings, ProcessLauncher launcher,
                               ProcessRegistry registry, Clock clock) {
        this.renderer = renderer;
        this.commands = commands;
        this.probe = probe;
        this.normalizer = normalizer;
        this.settings = settings;
        this.launcher = launcher;
        this.registry = registry;
        this.clock = clock;
    }

    /** 执行请求。 */
    public record ExecuteRequest(long projectId,
                                 String runId,
                                 List<String> nodeIds,
                                 List<String> hostKeys,
                                 String mode,
                                 Path runDir,
                                 Duration timeout,
                                 Map<String, String> runMetadata) {
    }

    /** 执行结果。 */
    public record ExecuteResult(boolean success,
                                String reason,
                                NormalizedRun run,
                                PytestEnvRenderer.RenderResult render,
                                PytestResultNormalizer.NormalizeResult normalize,
                                int batches,
                                int exitCode,
                                Duration elapsed,
                                List<Path> junitFiles,
                                PlanActualGap.Gap gap) {
    }

    /**
     * 执行。任一步失败立即返回，且 {@code reason} 用可读的原因码而不是堆栈。
     *
     * <p>失败不影响已产生的证据：渲染失败的 runDir 里通常什么都没有；
     * 执行失败时 stdout/stderr 与 junit（若生成）仍然在盘上，供归因使用。
     */
    public ExecuteResult execute(ExecuteRequest request) {
        Instant started = clock.instant();
        Map<String, String> config = PytestProjectSettings.load(settings, request.projectId());
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);

        PytestEnvRenderer.RenderResult render;
        try {
            render = renderer.render(new PytestEnvRenderer.RenderRequest(request.projectId(),
                    request.runId(), request.hostKeys(), List.of(), request.mode(),
                    request.runDir()));
        } catch (RuntimeException ex) {
            return failure("env_render_failed", ex.getMessage(), started);
        }

        // EHT-14：探测 cwd 必须是用例库仓库根。插件的 --mode / --env_config 由
        // pyproject.toml 的 addopts 注册，2 参重载在空临时目录里跑 help 会假阴性，
        // 直接把整条执行链判成「契约不健康」。与 PytestHealthService 保持同一口径。
        PytestContractProbe.ProbeResult contract =
                probe.probe(pythonBin, false, PytestProjectSettings.repoPath(config));
        writeJson(request.runDir(), "contract.json", Map.of(
                "python_bin", pythonBin == null ? "" : pythonBin,
                "healthy", contract.healthy(),
                "failure_reason", contract.failureReason() == null ? "" : contract.failureReason(),
                "found_options", contract.foundOptions(),
                "elapsed_ms", contract.elapsedMillis()));
        if (!contract.healthy()) {
            return new ExecuteResult(false, "pytest_contract_unhealthy: " + contract.failureReason(),
                    null, render, null, 0, -1,
                    Duration.between(started, clock.instant()), List.of(), null);
        }

        List<PytestNicKernelCommand.Command> plans;
        try {
            plans = commands.build(request.projectId(), request.runId(), request.nodeIds(),
                    request.mode(), request.runDir(), contract.contract(), render.runEnvFile());
        } catch (RuntimeException ex) {
            return new ExecuteResult(false, "command_build_failed: " + ex.getMessage(), null, render,
                    null, 0, -1, Duration.between(started, clock.instant()), List.of(), null);
        }

        List<Path> junitFiles = new ArrayList<>();
        int lastExit = -1;
        for (PytestNicKernelCommand.Command plan : plans) {
            lastExit = runOne(request, plan, junitFiles);
            if (lastExit != 0) {
                break;
            }
        }

        PytestResultNormalizer.NormalizeResult normalized;
        try {
            normalized = normalizer.normalize(request.projectId(), request.runId(), junitFiles,
                    request.runMetadata(), request.runDir());
        } catch (RuntimeException ex) {
            return new ExecuteResult(false, "pytest_artifact_invalid: " + ex.getMessage(), null,
                    render, null, plans.size(), lastExit,
                    Duration.between(started, clock.instant()), List.copyOf(junitFiles), null);
        }

        // 计划 vs 实际（W54-07）：参数化用例缺参数时不会报错、只会少几条结果，
        // 这一句提示是唯一能让人发现「少跑了」的地方，必须落在日志里而不只在返回值里
        PlanActualGap.Gap gap = PlanActualGap.of(request.nodeIds(), actualKeys(normalized), "pytest");
        if (gap.hasGap()) {
            emit(request.runDir().resolve("run.log"), "[plan-gap] " + gap.notice());
        }

        String reason = lastExit == 0 ? "ok" : "pytest_exit_" + lastExit;
        return new ExecuteResult(lastExit == 0, reason, normalized.run(), render, normalized,
                plans.size(), lastExit, Duration.between(started, clock.instant()),
                List.copyOf(junitFiles), gap);
    }

    /** 归一后实际产出结果的用例键（含未绑定脚本的结果：没绑定也是「跑了」）。 */
    private static List<String> actualKeys(PytestResultNormalizer.NormalizeResult normalized) {
        List<String> keys = new ArrayList<>();
        if (normalized == null || normalized.mappings() == null) {
            return keys;
        }
        for (PytestResultNormalizer.CaseMapping mapping : normalized.mappings()) {
            keys.add(mapping.nodeId());
        }
        return keys;
    }

    /** 跑一批；返回退出码。失败也把已完成批次的 junit 收集起来。 */
    private int runOne(ExecuteRequest request, PytestNicKernelCommand.Command plan,
                       List<Path> junitFiles) {
        Path outputDir = plan.outputDir();
        Path stdout = request.runDir().resolve("stdout-batch-" + plan.batchNo() + ".log");
        Path stderr = request.runDir().resolve("stderr-batch-" + plan.batchNo() + ".log");
        Path runLog = request.runDir().resolve("run.log");
        try {
            Files.createDirectories(outputDir);
            touch(stdout);
            touch(stderr);
        } catch (IOException ex) {
            emit(runLog, "[system] 产物目录不可写: " + ex.getMessage());
            return -1;
        }
        emit(runLog, "[system] 第 " + plan.batchNo() + " 批启动："
                + String.join(" ", plan.argv()) + "（cwd=" + plan.workDir() + "）");

        PabotCommandBuilder.PabotCommand command = new PabotCommandBuilder.PabotCommand(
                plan.argv(), plan.environment(), plan.workDir(), plan.outputDir());
        long pid;
        int exitCode;
        try {
            ProcessLauncher.ExecutionHandle handle = launcher.launch(command);
            pid = handle.pid();
            registry.register(request.runId(), handle.process());
            Thread out = pump(handle.stdout(), stdout);
            Thread err = pump(handle.stderr(), stderr);
            exitCode = handle.waitFor(request.timeout() == null
                    ? Duration.ofHours(1) : request.timeout());
            registry.unregister(request.runId());
            if (exitCode == Integer.MIN_VALUE && handle.process() != null) {
                handle.process().descendants().forEach(ProcessHandle::destroyForcibly);
                handle.process().destroyForcibly();
                emit(runLog, "[system] 第 " + plan.batchNo() + " 批超时，已强制终止 PID=" + pid);
            }
            join(out);
            join(err);
        } catch (Exception ex) {
            emit(runLog, "[system] 第 " + plan.batchNo() + " 批启动失败: " + ex.getMessage());
            return -1;
        }
        emit(runLog, "[system] 第 " + plan.batchNo() + " 批退出码=" + exitCode + "，产物="
                + plan.junitFile());
        if (Files.isRegularFile(plan.junitFile())) {
            junitFiles.add(plan.junitFile());
        }
        return exitCode;
    }

    private static ExecuteResult failure(String reason, String message, Instant started) {
        return new ExecuteResult(false, reason + ": " + message, null, null, null, 0, -1,
                Duration.between(started, Instant.now()), List.of(), null);
    }

    private static void writeJson(Path dir, String name, Map<String, Object> payload) {
        try {
            Files.createDirectories(dir);
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve(name).toFile(), payload);
        } catch (IOException ignored) {
            // 证据文件写不进不阻断执行
        }
    }

    private static void touch(Path file) throws IOException {
        Files.writeString(file, "", StandardCharsets.UTF_8);
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
                // 落盘失败不影响执行
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
