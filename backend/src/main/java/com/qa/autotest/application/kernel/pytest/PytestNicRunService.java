package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.execution.PabotCommandBuilder;
import com.qa.autotest.application.execution.PlanActualGap;
import com.qa.autotest.application.execution.ProcessLauncher;
import com.qa.autotest.application.execution.ProcessRegistry;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
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
import javax.xml.parsers.DocumentBuilderFactory;

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

    /**
     * 执行进度回调（v5.6.0 / FR-v5.6-12、13）。
     *
     * <p>接口放在这里而不是让 Service 依赖进度登记表：Service 只负责「跑到哪一步了」这件事
     * 的事实陈述，要不要记账、记到哪，是调用方（HTTP 层）的选择。单元测试因此不必构造登记表。
     */
    public interface ProgressListener {

        /** 什么都不做的监听器：同步执行的旧路径用它，行为与加这个接口之前完全一致。 */
        ProgressListener NOOP = new ProgressListener() {
            @Override
            public void stage(String stage) {
                // 同步执行不记账
            }

            @Override
            public void batchDone(int passed, int failed, int total) {
                // 同步执行不记账
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }
        };

        /** 阶段变化。取值与 {@code PytestRunProgressRegistry.Stage} 对齐，但不强绑枚举。 */
        void stage(String stage);

        /** 一批结束：本批通过 / 失败 / 总数。 */
        void batchDone(int passed, int failed, int total);

        /** 调用方是否已请求取消。每批之间检查一次，返回 true 时尽快停下。 */
        boolean cancelRequested();
    }

    /**
     * 执行请求。
     *
     * @param retry 复测轮数上限（v5.6.0 / FR-v5.6-24）。0 表示不复测；
     *              只有首轮失败且能反查到真实 nodeid 的用例才进复测
     */
    public record ExecuteRequest(long projectId,
                                 String runId,
                                 List<String> nodeIds,
                                 List<String> hostKeys,
                                 String mode,
                                 Path runDir,
                                 Duration timeout,
                                 Map<String, String> runMetadata,
                                 int retry) {

        /** 兼容 v5.5.0 的八参写法：不复测，与加这个字段之前完全一致。 */
        public ExecuteRequest(long projectId, String runId, List<String> nodeIds,
                              List<String> hostKeys, String mode, Path runDir,
                              Duration timeout, Map<String, String> runMetadata) {
            this(projectId, runId, nodeIds, hostKeys, mode, runDir, timeout, runMetadata, 0);
        }
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
        return execute(request, ProgressListener.NOOP);
    }

    /**
     * 带进度回调的执行。取消通过监听器的 {@code cancelRequested()} 在批次边界生效：
     * 进程内部无法安全中断（pytest 会留下半截 junit），所以取消的粒度是「批」。
     */
    public ExecuteResult execute(ExecuteRequest request, ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        Instant started = clock.instant();
        Map<String, String> config = PytestProjectSettings.load(settings, request.projectId());
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);

        progress.stage("RENDERING");
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

        List<List<Path>> rounds = new ArrayList<>();
        List<Path> firstRound = new ArrayList<>();
        rounds.add(firstRound);
        int batches = 0;
        int lastExit = -1;
        boolean canceled = false;
        for (PytestNicKernelCommand.Command plan : plans) {
            // 取消在批次边界生效：启动前先看一眼，跑完一批再看一眼。
            // 不在进程内部强杀，是因为 pytest 被 SIGKILL 后 junit 是半截文件，
            // 归一器读到半个 <testsuite> 会直接把整次运行判成产物损坏（比没跑更糟）。
            if (progress.cancelRequested()) {
                canceled = true;
                break;
            }
            progress.stage("RUNNING");
            lastExit = runOne(request, plan, firstRound);
            batches++;
            int[] counts = countJunit(plan.junitFile());
            progress.batchDone(counts[0], counts[1], counts[2]);
            if (lastExit != 0) {
                break;
            }
        }
        if (canceled) {
            emit(request.runDir().resolve("run.log"), "[cancel] 已按请求取消，后续批次未启动");
            return new ExecuteResult(false, "canceled", null, render, null, batches, -2,
                    Duration.between(started, clock.instant()), List.copyOf(firstRound), null);
        }

        progress.stage("FINALIZING");
        PytestResultNormalizer.NormalizeResult normalized;
        try {
            normalized = normalizer.normalizeRounds(request.projectId(), request.runId(), rounds,
                    request.runMetadata(), request.runDir());
        } catch (RuntimeException ex) {
            return new ExecuteResult(false, "pytest_artifact_invalid: " + ex.getMessage(), null,
                    render, null, batches, lastExit,
                    Duration.between(started, clock.instant()), List.copyOf(firstRound), null);
        }

        // FR-v5.6-24：首轮失败 → 只把失败的那几条按原参数再跑一遍，最多 retry 轮。
        // 复测产物落 junit-retry-N.xml，绝不覆盖首轮 junit：抖动判定靠两份对照，
        // 覆盖掉首轮就等于把「当时失败在哪」这条证据删了。
        int retryLimit = Math.max(0, request.retry());
        int retriesRun = 0;
        for (int round = 1; round <= retryLimit; round++) {
            List<String> retryTargets = retryTargets(normalized, request.runDir());
            if (retryTargets.isEmpty()) {
                break;
            }
            if (progress.cancelRequested()) {
                canceled = true;
                emit(request.runDir().resolve("run.log"),
                        "[cancel] 复测第 " + round + " 轮前收到取消，已停止");
                break;
            }
            List<PytestNicKernelCommand.Command> retryPlans;
            try {
                retryPlans = commands.buildRetry(request.projectId(), request.runId(),
                        retryTargets, request.mode(), request.runDir(), contract.contract(),
                        render.runEnvFile(), round);
            } catch (RuntimeException ex) {
                emit(request.runDir().resolve("run.log"),
                        "[retry] 第 " + round + " 轮命令构建失败，放弃复测: " + ex.getMessage());
                break;
            }
            List<Path> retryFiles = new ArrayList<>();
            progress.stage("RUNNING");
            for (PytestNicKernelCommand.Command plan : retryPlans) {
                lastExit = runOne(request, plan, retryFiles);
                batches++;
            }
            if (retryFiles.isEmpty()) {
                emit(request.runDir().resolve("run.log"),
                        "[retry] 第 " + round + " 轮没有产出 junit，放弃后续复测");
                break;
            }
            rounds.add(retryFiles);
            retriesRun = round;
            try {
                normalized = normalizer.normalizeRounds(request.projectId(), request.runId(), rounds,
                        request.runMetadata(), request.runDir());
            } catch (RuntimeException ex) {
                emit(request.runDir().resolve("run.log"),
                        "[retry] 第 " + round + " 轮归一失败: " + ex.getMessage());
                break;
            }
            emit(request.runDir().resolve("run.log"), "[retry] 第 " + round + " 轮复测完成：提交 "
                    + retryTargets.size() + " 条，剩余失败 "
                    + failures(normalized.run()).size() + " 条");
        }
        if (canceled) {
            return new ExecuteResult(false, "canceled", normalized.run(), render, normalized,
                    batches, -2, Duration.between(started, clock.instant()),
                    allJunit(rounds), null);
        }
        if (retriesRun > 0) {
            // 进度条分母是「计划条数」，复测不该把它翻倍：只把最终真实结论推一次。
            progress.batchDone((int) normalized.run().cases().stream()
                            .filter(one -> !one.status().isFailure()).count(),
                    failures(normalized.run()).size(), normalized.run().total());
        }

        // 计划 vs 实际（W54-07）：参数化用例缺参数时不会报错、只会少几条结果，
        // 这一句提示是唯一能让人发现「少跑了」的地方，必须落在日志里而不只在返回值里
        PlanActualGap.Gap gap = PlanActualGap.of(request.nodeIds(), actualKeys(normalized), "pytest");
        if (gap.hasGap()) {
            emit(request.runDir().resolve("run.log"), "[plan-gap] " + gap.notice());
        }

        // 复测把失败跑成了通过：对外的结论按最终结论算，退出码同理归 0——
        // 对外说「这次运行失败」却拿不出失败用例，比说「通过（含抖动）」更误导。
        String reason = lastExit == 0 ? "ok" : "pytest_exit_" + lastExit;
        boolean success = lastExit == 0;
        if (!success && retriesRun > 0 && failures(normalized.run()).isEmpty()) {
            success = true;
            reason = "ok_after_retry";
            lastExit = 0;
        }
        return new ExecuteResult(success, reason, normalized.run(), render, normalized,
                batches, lastExit, Duration.between(started, clock.instant()),
                allJunit(rounds), gap);
    }

    /**
     * 复测目标：本轮仍失败、且能反查到真实 pytest nodeid 的用例。
     *
     * <p>反查不到就不复测（绑定缺失），并把这条事实写进 run.log——静默跳过会让
     * 「复测过了还是失败」和「根本没复测」看起来完全一样。
     */
    private List<String> retryTargets(PytestResultNormalizer.NormalizeResult normalized,
                                      Path runDir) {
        if (normalized == null || normalized.run() == null) {
            return List.of();
        }
        Map<String, String> junitToRetry = new LinkedHashMap<>();
        for (PytestResultNormalizer.CaseMapping mapping : normalized.mappings()) {
            if (mapping.retryNodeId() != null) {
                junitToRetry.put(mapping.nodeId(), mapping.retryNodeId());
            }
        }
        List<String> targets = new ArrayList<>();
        for (NormalizedCaseResult one : failures(normalized.run())) {
            String retryId = junitToRetry.get(one.nodeId());
            if (retryId == null) {
                emit(runDir.resolve("run.log"), "[retry] 跳过无绑定的用例（无法构造真实 nodeid）："
                        + one.nodeId());
                continue;
            }
            targets.add(retryId);
        }
        return List.copyOf(targets);
    }

    /** 判定为失败的用例（FAIL / ERROR / XPASS），顺序沿用归一顺序。 */
    private static List<NormalizedCaseResult> failures(NormalizedRun run) {
        if (run == null) {
            return List.of();
        }
        List<NormalizedCaseResult> failed = new ArrayList<>();
        for (NormalizedCaseResult one : run.cases()) {
            if (one.status().isFailure()) {
                failed.add(one);
            }
        }
        return List.copyOf(failed);
    }

    /** 全部轮次的 junit 文件，按轮次顺序摊平（首轮在前）。 */
    private static List<Path> allJunit(List<List<Path>> rounds) {
        List<Path> files = new ArrayList<>();
        for (List<Path> round : rounds) {
            files.addAll(round);
        }
        return List.copyOf(files);
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

    /**
     * 数一份 junit 的通过 / 失败 / 总数，用于进度记账。
     *
     * <p>失败含 {@code failure} 与 {@code error}：对使用者来说「挂了」是同一件事，
     * 进度条没必要区分它们；真实分类由归一器负责，它读的是完整产物。
     * 文件还没写出来时返回全 0——进度不该因为「文件暂时不存在」而报错。
     */
    private static int[] countJunit(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new int[] {0, 0, 0};
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(file.toFile());
            org.w3c.dom.NodeList cases = doc.getElementsByTagName("testcase");
            int total = cases.getLength();
            int failed = 0;
            for (int i = 0; i < total; i++) {
                org.w3c.dom.Element one = (org.w3c.dom.Element) cases.item(i);
                if (one.getElementsByTagName("failure").getLength() > 0
                        || one.getElementsByTagName("error").getLength() > 0) {
                    failed++;
                }
            }
            return new int[] {total - failed, failed, total};
        } catch (Exception ex) {
            return new int[] {0, 0, 0};
        }
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
