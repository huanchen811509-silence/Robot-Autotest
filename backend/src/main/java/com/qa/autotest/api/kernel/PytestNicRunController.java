package com.qa.autotest.api.kernel;

import com.qa.autotest.application.execution.ProcessRegistry;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.kernel.pytest.PytestReadinessService;
import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.application.kernel.pytest.PytestRunProgressRegistry;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * pytest 执行入口（v5.5.0 EHT-14 起，v5.6.0 扩异步 / 进度 / 取消 / 预检）。
 *
 * <p>五个端点对应界面上「发起一次执行」的完整动作序列：
 * <ol>
 *   <li>{@code GET /precheck} —— 提交前先问「能不能跑」（台架 / 配置 / 设备 / 安全级）；</li>
 *   <li>{@code POST /runs/async} —— 提交即返回 202 + runId，不把浏览器挂住；</li>
 *   <li>{@code GET /runs/{runId}/progress} —— 轮询阶段与计数；</li>
 *   <li>{@code POST /runs/{runId}/cancel} —— 运行中取消；</li>
 *   <li>{@code POST /runs} / {@code /runs/fake} —— 旧的同步入口，语义不变（NFR-1）。</li>
 * </ol>
 *
 * <p><b>为什么同步入口不删</b>：v5.5.0 的取证脚本（5 个）都是同步调用，
 * 改语义等于把它们全部改废。异步走新端点，旧端点行为一字不动。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/pytest")
public class PytestNicRunController {

    private static final String MODE_FAKE = "fake";
    private static final String MODE_REAL = "real";

    private final PytestNicRunService runs;
    private final ProjectSettingRepository settings;
    private final RunRepository runRepo;
    private final RunArtifactRepository artifacts;
    private final RunMetadataRepository runMetadata;
    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final PytestRunProgressRegistry progress;
    private final PytestReadinessService readiness;
    private final DeviceRepository devices;
    private final ProcessRegistry processes;
    private final AccessControlService access;
    private final com.qa.autotest.application.report.PytestCommandEnricher commandEnricher;

    /** 异步执行线程池。固定 2 线程：同时跑两个 pytest 批次已经是这台机器的上限。 */
    private final ExecutorService worker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "pytest-async-run");
        thread.setDaemon(true);
        return thread;
    });

    public PytestNicRunController(PytestNicRunService runs,
                                  ProjectSettingRepository settings,
                                  RunRepository runRepo,
                                  RunArtifactRepository artifacts,
                                  RunMetadataRepository runMetadata,
                                  CaseScriptBindingRepository bindings,
                                  TestCaseRepository cases,
                                  PytestRunProgressRegistry progress,
                                  PytestReadinessService readiness,
                                  DeviceRepository devices,
                                  ProcessRegistry processes,
                                  AccessControlService access,
                                  com.qa.autotest.application.report.PytestCommandEnricher
                                          commandEnricher) {
        this.runs = runs;
        this.settings = settings;
        this.runRepo = runRepo;
        this.artifacts = artifacts;
        this.runMetadata = runMetadata;
        this.bindings = bindings;
        this.cases = cases;
        this.progress = progress;
        this.readiness = readiness;
        this.devices = devices;
        this.processes = processes;
        this.access = access;
        this.commandEnricher = commandEnricher;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /** 执行请求；nodeIds 为空表示整库全量收集。{@code confirmReal} 是 real 模式的显式授权位。 */
    public record FakeRunRequest(String runId, String mode, List<String> nodeIds,
                                 List<String> hostKeys, Integer timeoutSeconds,
                                 Map<String, String> metadata, Boolean confirmReal) {

        /** 老调用方 6 参形态（只跑 fake），保持源码兼容。 */
        public FakeRunRequest(String runId, String mode, List<String> nodeIds,
                              List<String> hostKeys, Integer timeoutSeconds,
                              Map<String, String> metadata) {
            this(runId, mode, nodeIds, hostKeys, timeoutSeconds, metadata, null);
        }
    }

    /** 校验通过、run 记录已落库的一次执行。同步与异步两条路径共用它。 */
    private record Prepared(long projectId, String runId, String mode, List<String> nodeIds,
                            List<String> hostKeys, Integer timeoutSeconds,
                            Map<String, String> metadata, Path runDir, int seconds,
                            Instant started, Path commandRoot) {
    }

    /**
     * 发起一次全链路执行：渲染 → 探契约 → 构命令 → 起进程 → junit 归一（同步）。
     *
     * <p>{@code /runs/fake} 强制 Plan-only；{@code /runs} 支持 real，但必须带
     * {@code confirmReal=true}，且目标用例全部通过 ADR-0013 安全闸（LX/L2 一律拒跑）。
     */
    @PostMapping("/runs/fake")
    public ApiResponse<Map<String, Object>> fake(@PathVariable long projectId,
                                                  @RequestBody(required = false) FakeRunRequest request) {
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        // 显式要 real 却打到 fake 端点，必须报错而不是悄悄降级成 fake——
        // 静默降级会让人以为跑的是真机，实际一条命令都没发出去。
        if (req.mode() != null && !req.mode().isBlank()
                && !MODE_FAKE.equals(req.mode().trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "此端点只跑 fake；real 请用 POST /runs 并带 confirmReal=true");
        }
        return execute(projectId, new FakeRunRequest(req.runId(), MODE_FAKE, req.nodeIds(),
                req.hostKeys(), req.timeoutSeconds(), req.metadata(), Boolean.FALSE));
    }

    /** 统一同步执行入口；real 模式需要显式授权位 + 安全闸双通过。 */
    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> run(@PathVariable long projectId,
                                                 @RequestBody(required = false) FakeRunRequest request) {
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        return execute(projectId, req);
    }

    /**
     * 异步发起（v5.6.0 / FR-v5.6-12）：202 + runId，执行在后台线程里跑。
     *
     * <p>「提交即返回」的前提是**所有会拒跑的校验都在提交前做完**：安全闸、runId 判重、
     * 配置缺失、run 记录落库。否则页面拿到 202 却过一会儿在 run 列表里看到一条
     * 从未启动的 FAILED，比同步的 400 更难排查。
     *
     * <p>run 记录必须在**提交前**落库（RK-2）：孤儿清扫器按 DB 活跃 run 对账，
     * 异步线程还没登记的话，60 秒后 pytest 会被当成孤儿 SIGTERM。
     */
    @PostMapping("/runs/async")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> runAsync(
            @PathVariable long projectId,
            @RequestBody(required = false) FakeRunRequest request) {
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        Prepared prepared = prepare(projectId, req);
        int total = plannedTotal(projectId, prepared.nodeIds());
        progress.begin(prepared.runId(), total);
        progress.stage(prepared.runId(), PytestRunProgressRegistry.Stage.PREPARING);

        worker.submit(() -> {
            try {
                runPrepared(prepared, new ProgressBridge(prepared.runId()));
            } catch (RuntimeException ex) {
                // 后台线程的异常没人接，必须自己落终态，否则页面永远转圈。
                progress.complete(prepared.runId(), PytestRunProgressRegistry.Stage.FAILED);
                runRepo.updateState(prepared.runId(), RunState.RUNNING, RunState.FAILED,
                        prepared.started(), Instant.now(), "PYTEST_ASYNC", String.valueOf(ex), null);
            }
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", prepared.runId());
        payload.put("mode", prepared.mode());
        payload.put("total", total);
        payload.put("accepted", true);
        payload.put("progressUrl", "/api/projects/" + projectId + "/pytest/runs/"
                + prepared.runId() + "/progress");
        return ApiResponse.ok(payload);
    }

    /**
     * 进度（v5.6.0 / FR-v5.6-12）。
     *
     * <p>三级回退：内存登记表 → run 表终态 → 404。
     * 第三级不能省：进程重启后内存表是空的，此时 run 表里已经结束的历史 run
     * 仍应能问到「它结束了，结论如何」，而不是一句「没有这个运行」。
     */
    @GetMapping("/runs/{runId}/progress")
    public ApiResponse<Map<String, Object>> progress(@PathVariable long projectId,
                                                     @PathVariable String runId) {
        access.requireProjectAccess(projectId);
        Optional<PytestRunProgressRegistry.Snapshot> live = progress.snapshot(runId);
        if (live.isPresent()) {
            PytestRunProgressRegistry.Snapshot snapshot = live.get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("stage", snapshot.stage());
            out.put("total", snapshot.total());
            out.put("finished", snapshot.finished());
            out.put("passed", snapshot.passed());
            out.put("failed", snapshot.failed());
            out.put("elapsedMs", snapshot.elapsedMs());
            out.put("cancelRequested", snapshot.cancelRequested());
            out.put("terminal", snapshot.terminal());
            out.put("source", "live");
            return ApiResponse.ok(out);
        }
        Optional<RunRecord> record = runRepo.findByRunId(projectId, runId);
        if (record.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "没有这个运行：" + runId);
        }
        RunRecord row = record.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("stage", row.state() == null ? "UNKNOWN" : row.state().name());
        out.put("total", row.totalTests());
        out.put("finished", row.totalTests());
        out.put("passed", row.passedTests());
        out.put("failed", row.failedTests());
        out.put("elapsedMs", row.startedAt() == null || row.finishedAt() == null
                ? 0 : Duration.between(row.startedAt(), row.finishedAt()).toMillis());
        out.put("cancelRequested", row.state() == RunState.CANCELED);
        out.put("terminal", row.state() != null && row.state().terminal());
        out.put("source", "db");
        return ApiResponse.ok(out);
    }

    /**
     * 取消（v5.6.0 / FR-v5.6-13）。
     *
     * <p>两件事一起做：① 置取消标记，让执行线程在下一个批次边界停下；
     * ② 直接销毁已登记的子进程——pytest 单批可能跑几分钟，等它自己到边界
     * 等于没取消。已完成的批次产物保留，不删。
     */
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long projectId,
                                                   @PathVariable String runId) {
        access.requireProjectAccess(projectId);
        boolean marked = progress.requestCancel(runId);
        int killed = 0;
        for (Process process : processes.processes(runId)) {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                killed++;
            } catch (RuntimeException ignored) {
                // 进程可能刚好自己退出；取消失败不阻断状态更新
            }
        }
        boolean known = marked || killed > 0
                || runRepo.findByRunId(projectId, runId).isPresent();
        if (!known) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "没有这个运行：" + runId);
        }
        Optional<RunRecord> record = runRepo.findByRunId(projectId, runId);
        boolean terminal = record.map(row -> row.state() != null && row.state().terminal())
                .orElse(false);
        if (!terminal) {
            runRepo.updateState(runId, RunState.RUNNING, RunState.CANCELED,
                    null, Instant.now(), "USER_CANCEL", "用户取消", null);
            // 内存表里如果没有这条（同步执行路径），补一条终态快照，
            // 否则页面取消后轮询会看到「404」而不是「已取消」。
            progress.complete(runId, PytestRunProgressRegistry.Stage.CANCELED);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("canceled", true);
        out.put("killedProcesses", killed);
        out.put("alreadyTerminal", terminal);
        return ApiResponse.ok(out);
    }

    /**
     * 执行预检（v5.6.0 / FR-v5.6-14）：台架 / 配置 / 设备 / 安全级四查。
     *
     * <p>顺序就是页面上的顺序，**台架居首**：先确认「有没有设备、有没有凭据、采过环境没有」，
     * 再谈配置项填得对不对。反过来会让人把「没配台架」误诊成「配置写错了」。
     *
     * <p>只读：不落库、不起进程、不探 SSH。任何一项的结论都是「现在数据够不够」，
     * 不是「执行会不会成功」——真正的执行结论只能由执行给出。
     */
    @GetMapping("/precheck")
    public ApiResponse<Map<String, Object>> precheck(
            @PathVariable long projectId,
            @RequestParam(defaultValue = MODE_FAKE) String mode,
            @RequestParam(required = false) List<String> nodeIds) {
        access.requireProjectAccess(projectId);
        String effective = mode == null || mode.isBlank()
                ? MODE_FAKE : mode.trim().toLowerCase(Locale.ROOT);
        List<String> targets = nodeIds == null ? List.of() : nodeIds;

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        int requiredMissing = 0;

        // ① 台架
        PytestReadinessService.Readiness bench = readiness.readiness(projectId);
        Object benchDetail = bench.missing();
        String benchAction = bench.missing().isEmpty() ? "" : "/assets?tab=devices";
        checks.add(check("bench", true, bench.ready(), bench.ready()
                ? "设备 / 凭据 / 环境 / 绑定四项齐备"
                : "缺：" + String.join(", ", bench.missing()), benchDetail, benchAction));
        if (!bench.ready()) {
            requiredMissing++;
        }

        // ② 配置
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        List<String> missingConfig = PytestProjectSettings.missingForExecute(config);
        checks.add(check("config", true, missingConfig.isEmpty(), missingConfig.isEmpty()
                ? "执行所需配置项齐备"
                : "缺配置：" + String.join(", ", missingConfig),
                missingConfig, "/pytest-workbench"));
        if (!missingConfig.isEmpty()) {
            requiredMissing++;
        }

        // ③ 设备
        int deviceCount = devices.listAll(projectId).size();
        checks.add(check("devices", true, deviceCount > 0,
                deviceCount > 0 ? "已登记 " + deviceCount + " 台设备" : "尚未登记任何设备",
                Map.of("count", deviceCount), "/assets?tab=devices"));
        if (deviceCount == 0) {
            requiredMissing++;
        }

        // ④ 安全级：只在 real 模式下是硬门槛；fake 不碰 DUT，列出但不拦
        List<String> blocked = blockedCases(projectId, targets);
        List<String> unmapped = unmappedNodes(projectId, targets);
        List<String> safetyItems = new ArrayList<>(blocked);
        safetyItems.addAll(unmapped);
        boolean safetyOk = safetyItems.isEmpty();
        boolean safetyRequired = MODE_REAL.equals(effective);
        checks.add(check("safety", safetyRequired, safetyOk, safetyOk
                ? "目标用例安全级可执行"
                : "需补判：" + String.join(", ", safetyItems),
                safetyItems, "/cases"));
        if (safetyRequired && !safetyOk) {
            requiredMissing++;
        }

        boolean ready = requiredMissing == 0;
        out.put("mode", effective);
        out.put("ready", ready);
        out.put("checks", checks);
        out.put("blocking", requiredMissing);
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> check(String key, boolean required, boolean ok,
                                             String detail, Object items, String action) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("key", key);
        one.put("required", required);
        one.put("ok", ok);
        one.put("detail", detail);
        one.put("items", items);
        one.put("action", action);
        return one;
    }

    // ------------------------------------------------------------------
    // 执行主流程
    // ------------------------------------------------------------------

    private ApiResponse<Map<String, Object>> execute(long projectId, FakeRunRequest req) {
        Prepared prepared = prepare(projectId, req);
        // 同步路径不记账：进度端点会退回 run 表读终态，行为与加进这个字段之前一致。
        return ApiResponse.ok(runPrepared(prepared, null));
    }

    /** 所有「会拒跑」的校验与副作用（run 记录落库）都在这里，提交前一次性做完。 */
    private Prepared prepare(long projectId, FakeRunRequest req) {
        access.requireProjectAccess(projectId);
        String mode = req.mode() == null || req.mode().isBlank()
                ? MODE_FAKE : req.mode().trim().toLowerCase(Locale.ROOT);
        if (!MODE_FAKE.equals(mode) && !MODE_REAL.equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的执行模式：" + mode + "（仅 fake / real）");
        }
        List<String> nodeIds = req.nodeIds() == null ? List.of() : req.nodeIds();
        if (MODE_REAL.equals(mode)) {
            // real 会真的 SSH 到 DUT 执行命令。两个条件缺一不可：
            // ① 调用方显式声明已获授权（不给默认值放行）；② 目标用例安全级可执行。
            if (!Boolean.TRUE.equals(req.confirmReal())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "real 模式需要显式确认：请求体加 confirmReal=true（真实执行会登录 DUT）");
            }
            List<String> blocked = blockedCases(projectId, nodeIds);
            if (!blocked.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "目标用例安全级未判定或属高危，按 ADR-0013 拒跑（先人工补判）："
                                + String.join(", ", blocked));
            }
            // fail-closed：点名了却映射不到用例，就等于「不知道它是什么风险」。
            // 放行的话，任何拼错/未绑定的 nodeid 都能绕过安全闸直接登录 DUT。
            List<String> unmapped = unmappedNodes(projectId, nodeIds);
            if (!unmapped.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "以下 nodeId 未绑定用例，无法判定安全级，按 fail-closed 拒跑："
                                + String.join(", ", unmapped));
            }
        }
        String runId = req.runId() == null || req.runId().isBlank()
                ? "EHT-" + mode.toUpperCase(Locale.ROOT) + "-" + System.currentTimeMillis()
                : req.runId().trim();
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        if (PytestProjectSettings.repoPath(config) == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未配置 pytest.repo_path");
        }
        Path runDir = Path.of(System.getProperty("java.io.tmpdir"),
                MODE_FAKE.equals(mode) ? "eht-fake-runs" : "eht-real-runs", runId);
        // 重复 runId 直接 409：底层唯一键冲突会抛 500，看上去像服务出错，
        // 实际是「这个名字已经用过」，调用方重试时应带新 runId。
        if (runRepo.findByRunId(projectId, runId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "runId 已存在：" + runId + "（换一个 runId 重试）");
        }
        int seconds = req.timeoutSeconds() == null ? 1800 : Math.max(30, req.timeoutSeconds());

        // 孤儿清扫器按 DB 活跃运行对账 ProcessRegistry：不先落一条 RUNNING 的 run，
        // 60 秒后 pytest 会被当孤儿 SIGTERM（实测 exitCode=143，junit 一个字没写）。
        // 这里走平台仓储而不是绕过治理，顺带让 run 表留下真实执行事实。
        Instant started = Instant.now();
        runRepo.insert(new RunRecord(0L, runId, projectId, null, RunState.QUEUED,
                        0, 1, 1, 0, 0, 0, null, null, null,
                        runDir.toString(), started, null, null, null, null),
                runId, null, TenantContext.userId());
        runRepo.updateState(runId, RunState.QUEUED, RunState.RUNNING,
                started, null, null, null, null);
        runRepo.updateKernel(runId, "pytest");

        // FR-v5.6-21：命令索引根在框架产物目录（<repo>/artifacts/<runId>），
        // 与 junit 不同源。这里一次算好塞进 Prepared，避免执行后再查一遍配置。
        Path commandRoot = com.qa.autotest.application.kernel.pytest.PytestNicKernelCommand
                .artifactRoot(PytestProjectSettings.repoPath(config), runId);
        return new Prepared(projectId, runId, mode, nodeIds,
                req.hostKeys() == null ? List.of() : req.hostKeys(),
                req.timeoutSeconds(), req.metadata() == null ? Map.of() : req.metadata(),
                runDir, seconds, started, commandRoot);
    }

    /** 跑一次已准备好的执行，并落状态、metadata、产物。 */
    private Map<String, Object> runPrepared(Prepared prepared, ProgressBridge bridge) {
        String runId = prepared.runId();
        String mode = prepared.mode();
        Path runDir = prepared.runDir();
        Instant started = prepared.started();

        PytestNicRunService.ExecuteRequest request = new PytestNicRunService.ExecuteRequest(
                prepared.projectId(), runId, prepared.nodeIds(), prepared.hostKeys(), mode,
                runDir, Duration.ofSeconds(prepared.seconds()), prepared.metadata());
        // 同步路径走单参重载：旧调用方（含单测）的桩只需实现一个方法，不会被新接口带崩。
        PytestNicRunService.ExecuteResult result =
                bridge == null ? runs.execute(request) : runs.execute(request, bridge);

        NormalizedRun normalized = result.run();
        // FR-v5.6-21：把框架命令索引补进归一结果（report 层做，适配层不反向依赖报告域）。
        // 补录放在这里而不是归一器里，是为了让两个入口（同步 / 异步）走同一条补录路径。
        if (result.normalize() != null) {
            var enriched = commandEnricher.enrich(prepared.commandRoot(), result.normalize());
            if (enriched != null && enriched.run() != null) {
                normalized = enriched.run();
            }
        }
        int total = normalized == null ? 0 : normalized.cases().size();
        int failed = 0;
        if (normalized != null) {
            for (NormalizedCaseResult one : normalized.cases()) {
                if (one.status().isFailure()) {
                    failed++;
                }
            }
        }
        runRepo.updateCounts(runId, total, 0, failed, runDir.toString());
        boolean canceled = "canceled".equals(result.reason());
        RunState terminal = canceled ? RunState.CANCELED
                : result.success() ? RunState.FINISHED : RunState.FAILED;
        runRepo.updateState(runId, RunState.RUNNING, terminal,
                started, Instant.now(), canceled ? "USER_CANCEL" : "PYTEST_EXIT",
                result.reason(), null);
        if (bridge != null) {
            progress.complete(runId, canceled ? PytestRunProgressRegistry.Stage.CANCELED
                    : result.success() ? PytestRunProgressRegistry.Stage.FINISHED
                    : PytestRunProgressRegistry.Stage.FAILED);
        }

        List<String> notes = new ArrayList<>();
        // 请求里的 metadata 必须落 run_metadata：报告解析读的是 run_artifact 侧的 junit，
        // 请求参数不会自己长进报告里；不落库的结果就是十项门禁全红、导出 400。
        // 走 HRD-02 补录机制（读取时 MetadataOverlay 叠加），不伪造 junit 内容。
        if (!prepared.metadata().isEmpty()) {
            try {
                runMetadata.save(prepared.projectId(), runId, prepared.metadata(), List.of(),
                        TenantContext.userId());
                notes.add("metadata 已落 run_metadata：" + prepared.metadata().size() + " 项");
            } catch (RuntimeException ex) {
                notes.add("metadata 补录失败：" + ex.getMessage());
            }
        }
        // 报告链路只认 run_artifact：不登记产物，执行完就只剩一个临时目录，
        // 「生成报告」按钮永远报「找不到 pytest 产物」。
        notes.addAll(registerArtifacts(prepared.projectId(), runId, runDir, result.junitFiles()));
        Map<String, Object> payload = view(result, runId, runDir, mode);
        payload.put("artifactNotes", notes);
        return payload;
    }

    /** 把 Service 的进度回调转成登记表写入。{@code runId} 为空表示同步执行、不记账。 */
    private final class ProgressBridge implements PytestNicRunService.ProgressListener {

        private final String runId;

        private ProgressBridge(String runId) {
            this.runId = runId;
        }

        @Override
        public void stage(String stage) {
            if (runId == null) {
                return;
            }
            try {
                progress.stage(runId, PytestRunProgressRegistry.Stage.valueOf(stage));
            } catch (IllegalArgumentException ignored) {
                // 未知阶段名不记；宁缺不错
            }
        }

        @Override
        public void batchDone(int passed, int failed, int total) {
            if (runId != null) {
                progress.batchDone(runId, passed, failed, total);
            }
        }

        @Override
        public boolean cancelRequested() {
            return runId != null && progress.cancelRequested(runId);
        }
    }

    /** 计划条数：点名用点名数，整库用已绑定脚本数。进度分母只能这么来。 */
    private int plannedTotal(long projectId, List<String> nodeIds) {
        if (!nodeIds.isEmpty()) {
            return nodeIds.size();
        }
        return (int) bindings.countByProject(projectId, "pytest");
    }

    /** 登记 junit 产物；多批执行先合并成 runDir/junit.xml，报告解析才有单一入口。 */
    private List<String> registerArtifacts(long projectId, String runId, Path runDir,
                                           List<Path> junitFiles) {
        List<String> notes = new ArrayList<>();
        Optional<RunRecord> record = runRepo.findByRunId(projectId, runId);
        if (record.isEmpty()) {
            notes.add("run 记录缺失，跳过产物登记");
            return notes;
        }
        long runDbId = record.get().id();
        Path merged = runDir.resolve("junit.xml");
        try {
            if (junitFiles != null && !junitFiles.isEmpty() && !Files.isRegularFile(merged)) {
                mergeJunit(junitFiles, merged);
                notes.add("合并 " + junitFiles.size() + " 个批次 junit → junit.xml");
            }
        } catch (Exception ex) {
            notes.add("合并 junit 失败：" + ex.getMessage());
        }
        for (Path file : junitFiles == null ? List.<Path>of() : junitFiles) {
            register(projectId, runDbId, "junit_xml", file, notes);
        }
        if (Files.isRegularFile(merged)) {
            // 报告服务按 kind=pytest_report_json 定位产物目录，再在目录里找 junit.xml。
            register(projectId, runDbId, "pytest_report_json", merged, notes);
        } else {
            notes.add("未找到可登记的 junit 产物");
        }
        return notes;
    }

    private void register(long projectId, long runDbId, String kind, Path file, List<String> notes) {
        if (file == null || !Files.isRegularFile(file)) {
            notes.add(kind + " 不存在：" + file);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String sha = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            artifacts.upsert(projectId, runDbId, kind, file.toString(), sha, bytes.length, 0, null);
            notes.add(kind + " 已登记 " + file.getFileName());
        } catch (Exception ex) {
            notes.add(kind + " 登记失败：" + ex.getMessage());
        }
    }

    /** 多批 junit 合并：保留每个 testsuite，计数重新累加。单批直接拷贝，不做 XML 往返。 */
    private static void mergeJunit(List<Path> files, Path target) throws Exception {
        if (files.size() == 1) {
            Files.copy(files.get(0), target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document merged = builder.newDocument();
        Element root = merged.createElement("testsuites");
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        double time = 0;
        for (Path file : files) {
            Document doc = builder.parse(file.toFile());
            Node suites = doc.getElementsByTagName("testsuites").item(0);
            if (suites != null) {
                for (Node child = suites.getFirstChild(); child != null;
                     child = child.getNextSibling()) {
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        Element imported = (Element) merged.importNode(child, true);
                        root.appendChild(imported);
                    }
                }
            }
            Node one = doc.getElementsByTagName("testsuite").item(0);
            if (one instanceof Element suite) {
                tests += intOf(suite, "tests");
                failures += intOf(suite, "failures");
                errors += intOf(suite, "errors");
                skipped += intOf(suite, "skipped");
                time += doubleOf(suite, "time");
            }
        }
        root.setAttribute("tests", String.valueOf(tests));
        root.setAttribute("failures", String.valueOf(failures));
        root.setAttribute("errors", String.valueOf(errors));
        root.setAttribute("skipped", String.valueOf(skipped));
        root.setAttribute("time", String.format(Locale.ROOT, "%.3f", time));
        merged.appendChild(root);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(new DOMSource(merged), new StreamResult(target.toFile()));
    }

    private static int intOf(Element element, String name) {
        try {
            return Integer.parseInt(element.getAttribute(name));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double doubleOf(Element element, String name) {
        try {
            return Double.parseDouble(element.getAttribute(name));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * ADR-0013 安全闸：把 nodeId 映射到用例，LX（未判定）/ L2（高危）一律拒跑。
     *
     * <p>nodeIds 为空表示整库，此时按项目已绑定用例全量核对——不能因为「没点名」
     * 就绕过闸门。未绑定脚本的用例不在这里判，它根本不会被本次 run 执行。
     */
    private List<String> blockedCases(long projectId, List<String> nodeIds) {
        Set<String> blocked = new LinkedHashSet<>();
        List<CaseScriptBinding> targets = new ArrayList<>();
        if (nodeIds.isEmpty()) {
            targets.addAll(bindings.listByProject(projectId, "pytest"));
        } else {
            for (String raw : nodeIds) {
                String base = raw == null ? "" : raw.trim();
                int bracket = base.indexOf('[');
                if (bracket > 0) {
                    base = base.substring(0, bracket);
                }
                Optional<CaseScriptBinding> found = bindings.findByNodeId(projectId, "pytest", base);
                if (found.isEmpty()) {
                    continue;
                }
                targets.add(found.get());
            }
        }
        for (CaseScriptBinding binding : targets) {
            Optional<TestCase> testCase = cases.find(projectId, binding.caseId());
            if (testCase.isEmpty()) {
                continue;
            }
            SafetyLevel level = SafetyLevel.from(testCase.get().safetyLevel());
            if (level.blocksExecution()) {
                blocked.add(testCase.get().caseCode().trim() + "(" + level.name() + ")");
            }
        }
        return new ArrayList<>(blocked);
    }

    /**
     * 点名但未绑定到用例的 nodeId 列表。
     *
     * <p>real 模式下这份清单必须为空：绑定关系是安全级的唯一入口，找不到绑定就
     * 无从判断 L0/L1/L2，只能按最保守的方式拒跑（fail-closed），不能默认放行。
     * nodeIds 为空（整库执行）时返回空——此时按项目已绑定用例全量核对。
     */
    private List<String> unmappedNodes(long projectId, List<String> nodeIds) {
        List<String> unmapped = new ArrayList<>();
        for (String raw : nodeIds) {
            String base = raw == null ? "" : raw.trim();
            int bracket = base.indexOf('[');
            if (bracket > 0) {
                base = base.substring(0, bracket);
            }
            if (base.isEmpty()) {
                continue;
            }
            if (bindings.findByNodeId(projectId, "pytest", base).isEmpty()) {
                unmapped.add(base);
            }
        }
        return unmapped;
    }

    /** 把执行结果摊平成可取证的结构；null 字段一律显式留空，不抛 NPE。 */
    private static Map<String, Object> view(PytestNicRunService.ExecuteResult result,
                                            String runId, Path runDir, String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("mode", mode);
        out.put("success", result.success());
        out.put("reason", result.reason());
        out.put("batches", result.batches());
        out.put("exitCode", result.exitCode());
        out.put("elapsedMs", result.elapsed().toMillis());
        out.put("runDir", runDir.toString());
        out.put("junitFiles", result.junitFiles().stream().map(Path::toString).toList());

        NormalizedRun run = result.run();
        out.put("totalCases", run == null ? 0 : run.cases().size());
        out.put("metadata", run == null ? Map.of() : run.metadata());
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        if (run != null) {
            for (NormalizedCaseResult one : run.cases()) {
                statusCounts.merge(one.status().name(), 1, Integer::sum);
            }
        }
        out.put("statusCounts", statusCounts);

        int matched = 0;
        int unbound = 0;
        if (result.normalize() != null && result.normalize().mappings() != null) {
            for (PytestResultNormalizer.CaseMapping mapping : result.normalize().mappings()) {
                if (mapping.matched()) {
                    matched++;
                } else {
                    unbound++;
                }
            }
        }
        out.put("boundMatched", matched);
        out.put("unboundResults", unbound);
        // FR-v5.5-08：Fake / Plan-only 不产生功能判定，通过率恒「不适用」。
        boolean planOnly = MODE_FAKE.equals(mode);
        int nonSkipped = 0;
        for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
            if (!"SKIP".equals(entry.getKey()) && !"XFAIL".equals(entry.getKey())) {
                nonSkipped += entry.getValue();
            }
        }
        boolean applicable = !planOnly && result.normalize() != null
                && result.normalize().passRateApplicable();
        out.put("planOnly", planOnly);
        out.put("nonSkippedCases", nonSkipped);
        out.put("passRateApplicable", applicable);
        out.put("passRateDisplay", applicable ? "按比例计算" : "不适用");
        out.put("gap", result.gap() == null || result.gap().notice() == null
                ? "" : result.gap().notice());

        String command = commandOf(runDir);
        Map<String, Boolean> conditions = new LinkedHashMap<>();
        conditions.put("--capture=fd", command.contains("--capture=fd"));
        conditions.put("junit_logging=out-err", command.contains("junit_logging=out-err"));
        conditions.put("junit_family=xunit2", command.contains("junit_family=xunit2"));
        out.put("command", command);
        out.put("junitConditions", conditions);
        return out;
    }

    /** 从真实 run.log 取第 1 批命令原文；取不到返回空串，三条件随即全 false。 */
    private static String commandOf(Path runDir) {
        Path log = runDir.resolve("run.log");
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(log)) {
                int at = line.indexOf("批启动：");
                if (at < 0) {
                    continue;
                }
                String command = line.substring(at + "批启动：".length()).trim();
                int cwd = command.indexOf("（cwd=");
                return (cwd > 0 ? command.substring(0, cwd) : command).trim();
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }
}
