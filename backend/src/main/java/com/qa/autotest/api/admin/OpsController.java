package com.qa.autotest.api.admin;

import com.qa.autotest.application.execution.RunAdmissionService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.ops.AlertService;
import com.qa.autotest.application.ops.BackupStatusService;
import com.qa.autotest.application.ops.OpsSummaryService;
import com.qa.autotest.application.ops.ReleaseService;
import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.ops.AlertChannel;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.AlertRule;
import com.qa.autotest.domain.ops.ReleaseRecord;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运维中心 API（FR-3.3-09..11）。全部端点仅管理员可用。 */
@RestController
@RequestMapping("/api/admin")
public class OpsController {

    private static final Set<String> EVENT_TYPES = Set.of("RUN_FAILURE_RATE", "P95_LATENCY",
            "QUEUE_BACKLOG", "NODE_OFFLINE", "BACKUP_STALE");

    public record RuleRequest(long projectId, String eventType, double threshold,
                              int windowMinutes, int cooldownMinutes, String severity,
                              boolean enabled) {
    }

    public record ChannelRequest(long projectId, String kind, String target, String secret,
                                 boolean enabled) {
    }

    public record ReleaseRequest(String version, String status, String note) {
    }

    private final OpsSummaryService summaries;
    private final AlertService alerts;
    private final ReleaseService releases;
    private final BackupStatusService backups;
    private final OpsRepository repo;
    private final AccessControlService access;
    private final RunAdmissionService admission;

    public OpsController(OpsSummaryService summaries, AlertService alerts,
                         ReleaseService releases, BackupStatusService backups,
                         OpsRepository repo, AccessControlService access,
                         RunAdmissionService admission) {
        this.summaries = summaries;
        this.alerts = alerts;
        this.releases = releases;
        this.backups = backups;
        this.repo = repo;
        this.access = access;
        this.admission = admission;
    }

    @GetMapping("/ops/summary")
    public ApiResponse<OpsSummaryService.OpsSummary> summary(@RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(summaries.summary(projectId));
    }

    @GetMapping("/ops/backup")
    public ApiResponse<BackupStatusService.BackupStatus> backup(@RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(backups.latest());
    }

    /** 并发准入视图（FR-3.4-02）：解释「为什么被背压」，并给出三层上限的当前占用。 */
    @GetMapping("/ops/admission")
    public ApiResponse<RunAdmissionService.View> admission(@RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(admission.view());
    }

    @GetMapping("/ops/releases")
    public ApiResponse<List<ReleaseRecord>> releases(
            @RequestParam(defaultValue = "20") int limit) {
        requireAdminOnly();
        return ApiResponse.ok(releases.list(limit));
    }

    @PostMapping("/ops/releases")
    public ApiResponse<Long> recordRelease(@RequestBody ReleaseRequest request) {
        requireAdminOnly();
        return ApiResponse.ok(releases.record(request.version(), request.status(),
                request.note()));
    }

    @GetMapping("/alerts/rules")
    public ApiResponse<List<AlertRule>> rules(@RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(repo.rules(projectId));
    }

    @PostMapping("/alerts/rules")
    public ApiResponse<AlertRule> saveRule(@RequestBody RuleRequest request) {
        requireAdmin(request.projectId());
        if (!EVENT_TYPES.contains(request.eventType())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "未知告警类型：" + request.eventType());
        }
        if (request.windowMinutes() < 1 || request.windowMinutes() > 1440) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "窗口应在 1–1440 分钟");
        }
        return ApiResponse.ok(repo.saveRule(request.projectId(), request.eventType(),
                request.threshold(), request.windowMinutes(),
                Math.max(1, request.cooldownMinutes()),
                request.severity() == null ? "P2" : request.severity(), request.enabled(),
                TenantContext.userId()));
    }

    @DeleteMapping("/alerts/rules/{ruleId}")
    public ApiResponse<Boolean> deleteRule(@PathVariable long ruleId,
                                           @RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(repo.deleteRule(projectId, ruleId));
    }

    @GetMapping("/alerts/channels")
    public ApiResponse<List<AlertChannel>> channels(@RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(repo.channels(projectId));
    }

    @PostMapping("/alerts/channels")
    public ApiResponse<AlertChannel> saveChannel(@RequestBody ChannelRequest request) {
        requireAdmin(request.projectId());
        if (!"WEBHOOK".equalsIgnoreCase(request.kind()) && !"EMAIL".equalsIgnoreCase(request.kind())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "通道类型只支持 WEBHOOK / EMAIL");
        }
        return ApiResponse.ok(repo.saveChannel(request.projectId(),
                request.kind().toUpperCase(), request.target(), request.secret(),
                request.enabled(), TenantContext.userId()));
    }

    @DeleteMapping("/alerts/channels/{channelId}")
    public ApiResponse<Boolean> deleteChannel(@PathVariable long channelId,
                                              @RequestParam long projectId) {
        requireAdmin(projectId);
        return ApiResponse.ok(repo.deleteChannel(projectId, channelId));
    }

    @GetMapping("/alerts/events")
    public ApiResponse<List<AlertEvent>> events(@RequestParam long projectId,
                                                @RequestParam(defaultValue = "30") int limit) {
        requireAdmin(projectId);
        return ApiResponse.ok(alerts.events(projectId, limit));
    }

    /** 巡检入口：定时任务与演练都走这里；{@code detectedAt} 可注入真实发生时间以测 MTTD。 */
    @PostMapping("/alerts/evaluate")
    public ApiResponse<AlertService.Evaluation> evaluate(
            @RequestParam long projectId,
            @RequestParam(required = false) String detectedAt) {
        requireAdmin(projectId);
        Instant at = detectedAt == null || detectedAt.isBlank()
                ? Instant.now() : Instant.parse(detectedAt);
        return ApiResponse.ok(alerts.evaluate(projectId, at));
    }

    @PostMapping("/alerts/channels/{channelId}/test")
    public ApiResponse<Map<String, String>> testChannel(@PathVariable long channelId,
                                                        @RequestParam long projectId) {
        requireAdmin(projectId);
        var result = alerts.testChannel(projectId, channelId);
        return ApiResponse.ok(Map.of("status", result.status(),
                "error", result.error() == null ? "" : result.error()));
    }

    private void requireAdmin(long projectId) {
        requireAdminOnly();
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 必须为正数");
        }
    }

    private void requireAdminOnly() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以访问运维中心");
        }
    }
}
