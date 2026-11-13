package com.qa.autotest.api.kernel;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EHT-14 / FR-v5.5-08：pytest 用例库工程的 Fake 执行入口。
 *
 * <p>{@link PytestNicRunService} 此前是全仓零调用方——编排代码齐但没有 HTTP 通道，
 * 于是「从平台发起一次用例执行」无法验收。本端点把它接到项目下，且不改旧路线
 * （{@code /api/execution/quick-run} 仍走通用 PytestKernel，两者并存）。
 *
 * <p><b>只开放 fake 模式</b>：G-1 真实端点授权归大师，real 模式一律 400 拒绝，
 * 绝不靠「默认值」偷偷放行。响应里的三条件、通过率、绑定命中数都从真实
 * run.log / 归一结果现场计算，不写死常量结论。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/pytest")
public class PytestNicRunController {

    private final PytestNicRunService runs;
    private final ProjectSettingRepository settings;
    private final RunRepository runRepo;
    private final AccessControlService access;

    public PytestNicRunController(PytestNicRunService runs,
                                  ProjectSettingRepository settings,
                                  RunRepository runRepo,
                                  AccessControlService access) {
        this.runs = runs;
        this.settings = settings;
        this.runRepo = runRepo;
        this.access = access;
    }

    /** Fake 执行请求；nodeIds 为空表示整库全量收集（真源 fake 模式实测 70 tests）。 */
    public record FakeRunRequest(String runId, String mode, List<String> nodeIds,
                                 List<String> hostKeys, Integer timeoutSeconds,
                                 Map<String, String> metadata) {
    }

    /**
     * 发起一次 Fake 全链路执行：渲染 → 探契约 → 构命令 → 起进程 → junit 归一。
     * 产物目录由服务端固定落在 {@code java.io.tmpdir/eht-fake-runs/<runId>}，
     * 响应里带 runDir，供取证脚本原样归档；平台不写用例库真源。
     */
    @PostMapping("/runs/fake")
    public ApiResponse<Map<String, Object>> fake(@PathVariable long projectId,
                                                  @RequestBody(required = false) FakeRunRequest request) {
        access.requireProjectAccess(projectId);
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        String runId = req.runId() == null || req.runId().isBlank()
                ? "EHT-FAKE-" + System.currentTimeMillis() : req.runId().trim();
        String mode = req.mode() == null || req.mode().isBlank()
                ? "fake" : req.mode().trim().toLowerCase(Locale.ROOT);
        if (!"fake".equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "只开放 fake 模式（G-1 真实端点未授权）：" + mode);
        }
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        if (PytestProjectSettings.repoPath(config) == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未配置 pytest.repo_path");
        }
        Path runDir = Path.of(System.getProperty("java.io.tmpdir"), "eht-fake-runs", runId);
        int seconds = req.timeoutSeconds() == null ? 900 : Math.max(30, req.timeoutSeconds());

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

        PytestNicRunService.ExecuteResult result = runs.execute(
                new PytestNicRunService.ExecuteRequest(projectId, runId,
                        req.nodeIds() == null ? List.of() : req.nodeIds(),
                        req.hostKeys() == null ? List.of() : req.hostKeys(),
                        mode, runDir, Duration.ofSeconds(seconds),
                        req.metadata() == null ? Map.of() : req.metadata()));

        NormalizedRun normalized = result.run();
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
        runRepo.updateState(runId, RunState.RUNNING,
                result.success() ? RunState.FINISHED : RunState.FAILED,
                started, Instant.now(), result.success() ? null : "PYTEST_EXIT",
                result.reason(), null);
        return ApiResponse.ok(view(result, runId, runDir, mode));
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
        // 旧实现只在「全 SKIP」时不适用，真源里混进 ERROR / FAIL（资产池缺项、
        // 用例仍写死设备名）就会把环境噪声算成通过率，属于产品口径错误。
        // 修正后不掩盖偏差：非 SKIP 条数照实暴露，取证脚本单独判用例层。
        boolean planOnly = "fake".equals(mode);
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
