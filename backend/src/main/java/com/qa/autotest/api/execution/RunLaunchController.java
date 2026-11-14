package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.CaseConclusionResolver;
import com.qa.autotest.application.execution.CaseRunService;
import com.qa.autotest.application.execution.CommandPreviewService;
import com.qa.autotest.application.execution.CriteriaIngestService;
import com.qa.autotest.application.execution.RunConfirmationService;
import com.qa.autotest.application.execution.SelectionResolver;
import com.qa.autotest.application.execution.SelectionSnapshotService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.execution.CommandDigest;
import com.qa.autotest.domain.execution.RunRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * v5.7.0 执行可信度 + 选择快照端点（W57-19 / 20 / 28）。
 *
 * <p>路径前缀与既有 {@code /api/execution} 不同：本控制器统一走
 * {@code /api/projects/{projectId}/...}，每个入口都由下游服务先
 * {@code access.bindProject(projectId)} + {@code access.require(...)}。
 *
 * <p><b>接线说明</b>：confirm-and-start 一致时接的是既有
 * {@link CaseRunService#run(CaseRunService.CaseRunCommand)}（按用例解析 + 安全级预检 + 派发），
 * 不是 {@code RunService.create + QuickRunService.dispatch} 直连；后者需要 suitePaths，
 * 从 caseIds 到 suitePaths 的转换在 CaseRunService 内部完成，重复接线会绕开安全级预检。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class RunLaunchController {

    /** 与 CaseRunService.MAX_CASES 一致；该常量是包级私有，跨包无法直接引用，此处显式对齐。 */
    private static final int CASE_RUN_LIMIT = 200;
    private static final int PREVIEW_ITEM_LIMIT = 200;

    private final CommandPreviewService commandPreview;
    private final RunConfirmationService runConfirmation;
    private final SelectionSnapshotService snapshots;
    private final SelectionResolver resolver;
    private final SelectionResolver.CaseScopeQuery scope;
    private final CriteriaIngestService criteriaIngest;
    private final CaseRunService caseRuns;
    private final AuditService audit;

    public RunLaunchController(CommandPreviewService commandPreview,
                               RunConfirmationService runConfirmation,
                               SelectionSnapshotService snapshots,
                               SelectionResolver resolver,
                               SelectionResolver.CaseScopeQuery scope,
                               CriteriaIngestService criteriaIngest,
                               CaseRunService caseRuns,
                               AuditService audit) {
        this.commandPreview = commandPreview;
        this.runConfirmation = runConfirmation;
        this.snapshots = snapshots;
        this.resolver = resolver;
        this.scope = scope;
        this.criteriaIngest = criteriaIngest;
        this.caseRuns = caseRuns;
        this.audit = audit;
    }

    /** 命令清单只读预览：条数、跳过原因、digest 前 12 位。不写 run / 不落确认 / 不启动进程。 */
    @PostMapping("/runs/preview-commands")
    public ApiResponse<CommandPreviewService.PreviewResult> previewCommands(
            @PathVariable long projectId,
            @RequestBody PreviewCommandsRequest request) {
        return ApiResponse.ok(commandPreview.preview(projectId,
                request.kernel(), request.caseIds()));
    }

    /**
     * 落确认摘要：服务端按同一规则复算命令摘要，不信任前端传入的 digest。
     *
     * <p>复核 P1-6：摘要同时绑定 {@code planId} 与选择快照摘要——只绑命令清单时，
     * 同一批脚本换一个计划就能复用同一个「已确认」。
     */
    @PostMapping("/runs/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable long projectId,
                                                 @RequestBody ConfirmRequest request) {
        CommandPreviewService.PreviewResult preview = commandPreview.preview(
                projectId, request.kernel(), request.caseIds());
        RunConfirmationService.Confirmation saved = runConfirmation.confirm(
                projectId, request.planId(), request.selectionSha256(), preview.digest());
        return ApiResponse.ok(new ConfirmResponse(saved.planId(), saved.digest(),
                saved.confirmedAt(), saved.confirmedBy()));
    }

    /**
     * 选择快照预览：只写 {@code execution_selection_snapshot} 一行，不创建 run、不派发。
     * 相同输入命中唯一键且未消费则复用既有快照。
     */
    @PostMapping("/runs/preview")
    public ApiResponse<SelectionPreviewResponse> preview(@PathVariable long projectId,
                                                          @RequestBody SelectionPreviewRequest request) {
        SelectionResolver.SelectionMode mode = SelectionResolver.parseMode(request.mode());
        SelectionSnapshotService.Frozen frozen = snapshots.freeze(
                projectId, mode, request.setIds(), request.caseIds(), request.filter());
        List<Long> head = frozen.caseIds().subList(0,
                Math.min(PREVIEW_ITEM_LIMIT, frozen.caseIds().size()));
        Map<Long, String> codes = scope.caseCodes(projectId, head);
        List<SelectionItem> items = new ArrayList<>(head.size());
        for (Long id : head) {
            items.add(new SelectionItem(id, codes.getOrDefault(id, "")));
        }
        return ApiResponse.ok(new SelectionPreviewResponse(frozen.snapshotId(),
                frozen.selectionSha256(), frozen.caseCount(), items, frozen.selectionSha256()));
    }

    /**
     * 确认并发起：只吃 snapshotId。重算摘要不一致 → 409 DIGEST_MISMATCH，
     * 响应体带 added/removed/reordered 编号差异，且<b>不创建 run、不启动进程</b>。
     */
    @PostMapping("/runs/confirm-and-start")
    public ResponseEntity<?> confirmAndStart(
            @PathVariable long projectId,
            @RequestBody ConfirmAndStartRequest request) {
        long snapshotId = request.snapshotId();
        SelectionSnapshotService.Recompute recompute = snapshots.recomputeSha(projectId, snapshotId);
        if (!recompute.match()) {
            DigestMismatchData data = new DigestMismatchData(
                    recompute.expectedSha(), recompute.recomputedSha(),
                    recompute.diff().added(), recompute.diff().removed(),
                    recompute.diff().reordered());
            // ErrorCode 枚举无 DIGEST_MISMATCH 且本主题不得改 common 包，故复用 CONFLICT 数值段，
            // 自定义 codeName 与 409 状态，并把结构化差异放进 data（BusinessException 无法带 data）。
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new ApiResponse<>(ErrorCode.CONFLICT.code(), "DIGEST_MISMATCH",
                            "选择摘要不一致，未创建 run、未启动进程", data));
        }

        // 复核 P1-7：第一道比对只覆盖「选择变化」。命令内容变了但用例集不变时摘要一致，
        // 防护形同虚设。这里补第二道：用当前选择重算命令清单，与已确认摘要逐位比对。
        if (request.planId() != null) {
            String expected = runConfirmation.expected(projectId, request.planId());
            if (expected == null) {
                audit.record("run.blocked-unconfirmed", "execution_plan",
                        String.valueOf(request.planId()),
                        "snapshotId=" + snapshotId + ", reason=无确认记录");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        new ApiResponse<>(ErrorCode.CONFLICT.code(), "UNCONFIRMED",
                                "该执行计划还没有确认摘要，已拒绝派发", null));
            }
            String kernel = request.kernel() == null || request.kernel().isBlank()
                    ? "robot" : request.kernel();
            String commandDigest = commandPreview
                    .preview(projectId, kernel, recompute.currentCaseIds()).digest();
            String actual = CommandDigest.confirmationDigest(
                    request.planId(), recompute.expectedSha(), commandDigest);
            if (!expected.equals(actual)) {
                audit.record("run.digest-mismatch", "execution_plan",
                        String.valueOf(request.planId()),
                        "snapshotId=" + snapshotId + ", expected12=" + expected.substring(0, 12)
                                + ", actual12=" + actual.substring(0, 12));
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        new ApiResponse<>(ErrorCode.CONFLICT.code(), "DIGEST_MISMATCH",
                                "确认摘要与当前计划/选择/命令不一致，未创建 run、未启动进程", null));
            }
        }

        // 派发链路可能落到异步线程，显式 runAs 包裹消费 + 派发，保证租户上下文不丢。
        Long userId = TenantContext.userId();
        String username = TenantContext.username();
        AtomicReference<RunRecord> created = new AtomicReference<>();
        TenantContext.runAs(userId, username, projectId, () -> {
            List<Long> frozen = snapshots.consume(projectId, snapshotId);
            if (frozen.size() > CASE_RUN_LIMIT) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "快照命中 " + frozen.size() + " 条，超过既有按用例执行链路单次上限 "
                                + CASE_RUN_LIMIT + " 条；本主题未改动 CaseRunService，未接线到派发，请缩小选择范围");
            }
            String runRequestId = request.runRequestId() == null || request.runRequestId().isBlank()
                    ? "SNAP-" + snapshotId
                    : request.runRequestId();
            created.set(caseRuns.run(new CaseRunService.CaseRunCommand(
                    projectId, frozen, request.environmentId(), List.of(),
                    request.concurrency(), request.timeoutSeconds(),
                    request.testTimeoutSeconds(), request.includeTag(), runRequestId)));
        });
        return ResponseEntity.ok(ApiResponse.ok(created.get()));
    }

    /** ALL 模式命中数（项目作用域 + 可选筛选）。 */
    @GetMapping("/cases/count")
    public ApiResponse<CountResponse> casesCount(
            @PathVariable long projectId,
            @RequestParam(required = false) Map<String, String> filter) {
        Map<String, Object> typed = filter == null ? Map.of() : new LinkedHashMap<>(filter);
        return ApiResponse.ok(new CountResponse(resolver.count(projectId, typed)));
    }

    /** 判据明细：结论由 CaseConclusionResolver 统一裁定，附覆盖率。 */
    @GetMapping("/runs/{runId}/criteria")
    public ApiResponse<CriteriaResponse> criteria(@PathVariable long projectId,
                                                   @PathVariable String runId) {
        List<CriteriaIngestService.CriteriaRow> rows = criteriaIngest.list(projectId, runId);
        List<CriteriaItem> items = new ArrayList<>(rows.size());
        List<CaseConclusionResolver.VerdictView> views = new ArrayList<>(rows.size());
        for (CriteriaIngestService.CriteriaRow row : rows) {
            String conclusion = CaseConclusionResolver
                    .resolve(row.processStatus(), row.criteriaVerdict()).name();
            items.add(new CriteriaItem(row.caseId(), row.caseCode(), row.verdict(),
                    row.processStatus(), row.criteriaJson(), row.criteriaVerdict(),
                    row.criteriaMissing(), conclusion));
            views.add(new CaseConclusionResolver.VerdictView(
                    row.processStatus(), row.criteriaVerdict()));
        }
        return ApiResponse.ok(new CriteriaResponse(runId, items,
                CaseConclusionResolver.coverage(views)));
    }

    // ---------- 请求 / 响应 DTO ----------

    public record PreviewCommandsRequest(String kernel, List<Long> caseIds) {
    }

    /**
     * @param selectionSha256 选择快照摘要；为空表示本次确认只绑命令清单
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmRequest(long planId, String kernel, List<Long> caseIds,
                                 String selectionSha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SelectionPreviewRequest(String mode,
                                          List<Long> setIds,
                                          List<Long> caseIds,
                                          Map<String, Object> filter) {
    }

    public record SelectionPreviewResponse(long snapshotId,
                                           String selectionSha256,
                                           int caseCount,
                                           List<SelectionItem> items,
                                           /** 与 selectionSha256 同值：兼容前端两种字段命名。 */
                                           String sha256) {
    }

    public record SelectionItem(long caseId, String caseCode) {
    }

    /**
     * 确认并发起。
     *
     * <p>{@code ignoreUnknown = false}：字段名写错必须 400，不许被 Jackson 静默丢弃
     * （v5.7.0 首版因未知字段被忽略，导致前端传 {@code token} 时快照恒 0，见审计 P0-1）。
     */
    /**
     * @param planId 非空时，派发前会用当前选择重算命令摘要并与已确认摘要比对（复核 P1-7）
     * @param kernel 重算命令清单用的执行内核，缺省 {@code robot}
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmAndStartRequest(long snapshotId,
                                         Long planId,
                                         String kernel,
                                         Long environmentId,
                                         Integer concurrency,
                                         Integer timeoutSeconds,
                                         Integer testTimeoutSeconds,
                                         String includeTag,
                                         String runRequestId) {
    }

    /** 409 差异体：added/removed/reordered 均为 1-based 位置编号数组。 */
    public record DigestMismatchData(String expectedSha,
                                     String recomputedSha,
                                     List<Integer> added,
                                     List<Integer> removed,
                                     List<Integer> reordered) {
    }

    public record ConfirmResponse(long planId,
                                  String digest,
                                  Instant confirmedAt,
                                  Long confirmedBy) {
    }

    public record CountResponse(long count) {
    }

    public record CriteriaResponse(String runId,
                                   List<CriteriaItem> items,
                                   CaseConclusionResolver.Coverage coverage) {
    }

    public record CriteriaItem(long caseId,
                               String caseCode,
                               String verdict,
                               String processStatus,
                               String criteriaJson,
                               String criteriaVerdict,
                               String criteriaMissing,
                               String conclusion) {
    }
}
