package com.qa.autotest.api.kernel;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestBindingSyncService;
import com.qa.autotest.application.kernel.pytest.PytestEvidenceService;
import com.qa.autotest.application.kernel.pytest.PytestHealthService;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.kernel.pytest.PytestReadinessService;
import com.qa.autotest.application.kernel.pytest.PytestSettingsService;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.caseasset.PytestCaseStep;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * pytest 工程接口（v5.1.0 · FR-v5.1-05 / FR-v5.1-12）。
 *
 * <p>四个端点对应界面上四件事：能不能跑（health）、接上了多少（bindings）、
 * 重新扫一遍（sync）、配置项读写（settings）。
 *
 * <p>路径统一挂在项目下，不新开顶层资源：pytest 配置天然属于某个工程，
 * 顶层 `/api/pytest` 会逼调用方每次都带 projectId 参数，反而多一层。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/pytest")
public class PytestProjectController {

    private final PytestHealthService health;
    private final PytestBindingSyncService sync;
    private final PytestEvidenceService evidence;
    private final PytestSettingsService settingsService;
    private final ProjectSettingRepository settings;
    private final PytestReadinessService readiness;
    private final AccessControlService access;

    public PytestProjectController(PytestHealthService health, PytestBindingSyncService sync,
                                   PytestEvidenceService evidence,
                                   PytestSettingsService settingsService,
                                   ProjectSettingRepository settings,
                                   PytestReadinessService readiness, AccessControlService access) {
        this.health = health;
        this.sync = sync;
        this.evidence = evidence;
        this.settingsService = settingsService;
        this.settings = settings;
        this.readiness = readiness;
        this.access = access;
    }

    /** 健康检查。{@code refresh=true} 时跳过 24h 缓存重新探测解释器。 */
    @GetMapping("/health")
    public ApiResponse<PytestHealthService.Health> health(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(health.health(projectId, refresh));
    }

    /**
     * 台架就绪度（v5.6.0 / FR-v5.6-04）：设备 / 凭据 / 环境 / 绑定四项。
     *
     * <p>独立于 {@code /health}：health 回答「解释器与契约对不对」，这里回答「台架齐不齐」。
     * 合成一个响应会让两件事的缓存一并失效——解释器版本一天不变，台架却可能每小时在变。
     */
    @GetMapping("/readiness")
    public ApiResponse<PytestReadinessService.Readiness> readiness(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(readiness.readiness(projectId));
    }

    /** 绑定列表与计数（只读，不扫盘）。 */
    @GetMapping("/bindings")
    public ApiResponse<PytestHealthService.BindingStats> bindings(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(health.bindingStats(projectId));
    }

    /** 全量重扫并重建绑定；返回四类计数。 */
    @PostMapping("/bindings/sync")
    public ApiResponse<PytestBindingSyncService.SyncReport> sync(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(sync.sync(projectId, null));
    }

    /** 工程配置（键已去掉 pytest. 前缀）。 */
    @GetMapping("/settings")
    public ApiResponse<Map<String, String>> getSettings(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(settings.findAll(projectId, PytestProjectSettings.PREFIX));
    }

    /**
     * 覆盖写配置项：只写请求里给出的键，未给出的保持原值；空值表示恢复未配置。
     *
     * <p>返回体带 {@code health}：保存后立刻探一次，页面不必再请求第二次，
     * 也不会出现「保存成功但健康状态还是旧的」。任一项非法时整体拒绝。
     */
    @PutMapping("/settings")
    public ApiResponse<PytestSettingsService.SaveResult> putSettings(
            @PathVariable long projectId, @RequestBody Map<String, String> body) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(settingsService.save(projectId, body));
    }

    /** 只校验不落库：页面「保存前试算」，也用于排查「为什么保存被拒」。 */
    @PostMapping("/settings/validate")
    public ApiResponse<List<PytestSettingsService.FieldError>> validateSettings(
            @PathVariable long projectId, @RequestBody Map<String, String> body) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(settingsService.check(body));
    }

    /**
     * 抽取并入库某次 run 的步骤与命令证据（FR-v5.1-08）。
     *
     * <p>POST 而不是 GET：要读 junit 与框架 artifacts 两个目录，属于有副作用的写操作
     * （覆盖该 run 的证据行）。页面打开时先 GET 已入库结果，用户点「重新抽取」再走这里。
     */
    @PostMapping("/runs/{runId}/evidence")
    public ApiResponse<PytestEvidenceService.ExtractResult> extractEvidence(
            @PathVariable long projectId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "true") boolean commands) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(evidence.extract(projectId, runId, commands));
    }

    /** 已入库的步骤证据（按 nodeId + seq 排序）。 */
    @GetMapping("/runs/{runId}/evidence")
    public ApiResponse<List<PytestCaseStep>> evidence(@PathVariable long projectId,
                                                      @PathVariable String runId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(evidence.list(projectId, runId));
    }

    /** 逐用例归因五层（FR-v5.2-06）：只读已入库证据，可复算。 */
    @GetMapping("/runs/{runId}/attribution")
    public ApiResponse<List<PytestEvidenceService.CaseAttribution>> attribution(
            @PathVariable long projectId, @PathVariable String runId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(evidence.attribute(projectId, runId));
    }
}
