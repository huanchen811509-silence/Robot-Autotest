package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.CancelService;
import com.qa.autotest.application.execution.CaseRunService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.LeaseWaitRegistry;
import com.qa.autotest.application.execution.QuickRunService;
import com.qa.autotest.application.execution.RunDispatcher;
import com.qa.autotest.application.execution.RunLogFileService;
import com.qa.autotest.application.execution.RunEtaCalculator;
import com.qa.autotest.application.execution.RunIdempotency;
import com.qa.autotest.application.execution.RunLogService;
import com.qa.autotest.application.execution.RunProgressRegistry;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.execution.RunService.CreateRunCommand;
import com.qa.autotest.application.execution.TestSetRunService;
import com.qa.autotest.application.repository.RunRetryOrigin;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 运行记录接口（EXE-01 + EXEC-01）。
 *
 * <p>触发入口有三个：
 * <ul>
 *   <li>{@code POST /api/execution/quick-run}：页面「手动执行」，EXECUTION:EXECUTE，立即派发；</li>
 *   <li>{@code POST /api/execution/test-sets/{id}/run}：用例集「立即执行」，按用例编号解析套件；</li>
 *   <li>{@code POST /api/execution/cases/run}：勾选台账用例「执行所选」，按标签/测试名解析到测试级；</li>
 *   <li>{@code POST /api/execution/runs}：<b>CI 专用</b>直建端点，落库后同样交给公共派发器，
 *       不再留下永久 queued 的僵尸 run。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/execution")
public class RunController {

    private final RunService runs;
    private final CancelService cancels;
    private final RunLogService logs;
    private final RunLogFileService logFiles;
    private final ExecutionProperties props;
    private final QuickRunService quickRuns;
    private final TestSetRunService testSetRuns;
    private final CaseRunService caseRuns;
    private final RunDispatcher dispatcher;
    private final LeaseWaitRegistry leaseWaits;
    private final RunProgressRegistry progresses;

    public RunController(RunService runs, CancelService cancels, RunLogService logs,
                         ExecutionProperties props, QuickRunService quickRuns,
                         TestSetRunService testSetRuns, CaseRunService caseRuns,
                         RunLogFileService logFiles, RunDispatcher dispatcher,
                         LeaseWaitRegistry leaseWaits, RunProgressRegistry progresses) {
        this.runs = runs;
        this.cancels = cancels;
        this.logs = logs;
        this.props = props;
        this.quickRuns = quickRuns;
        this.testSetRuns = testSetRuns;
        this.caseRuns = caseRuns;
        this.logFiles = logFiles;
        this.dispatcher = dispatcher;
        this.leaseWaits = leaseWaits;
        this.progresses = progresses;
    }

    /** 页面手动执行（EXEC-01 / FR-v0.2-59~60）。 */
    @PostMapping("/quick-run")
    public ApiResponse<RunRecord> quickRun(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody QuickRunRequest request) {
        String requestId = RunIdempotency.resolve(idempotencyKey, request.runRequestId(),
                "quick-run", Instant.now(), props.isIdempotencyFingerprintEnabled(),
                props.getIdempotencyWindowSeconds(),
                request.projectId(), request.testSetId(), request.suitePaths(),
                request.environmentId(), request.deviceKeys(), request.concurrency(),
                request.timeoutSeconds(), request.testTimeoutSeconds(), request.includeTag());
        return ApiResponse.ok(quickRuns.trigger(new QuickRunService.QuickRunCommand(
                request.projectId(),
                request.testSetId(),
                request.suitePaths(),
                request.environmentId(),
                request.deviceKeys(),
                request.concurrency(),
                request.timeoutSeconds(),
                request.testTimeoutSeconds(),
                request.includeTag(),
                requestId,
                null,
                request.source() == null || request.source().isBlank()
                        ? "runs-view" : request.source())));
    }

    /** 用例集立即执行（EXEC-01 / FR-v0.2-63）。 */
    @PostMapping("/test-sets/{testSetId}/run")
    public ApiResponse<RunRecord> runTestSet(@PathVariable long testSetId,
                                              @RequestParam long projectId,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              @RequestBody(required = false) TestSetRunRequest request) {
        TestSetRunRequest body = request == null
                ? new TestSetRunRequest(null, null, null, null, null, null, null)
                : request;
        String requestId = RunIdempotency.resolve(idempotencyKey, body.runRequestId(),
                "test-sets/run", Instant.now(), props.isIdempotencyFingerprintEnabled(),
                props.getIdempotencyWindowSeconds(),
                projectId, testSetId, body.environmentId(), body.deviceKeys(),
                body.concurrency(), body.timeoutSeconds(), body.testTimeoutSeconds(),
                body.includeTag());
        return ApiResponse.ok(testSetRuns.run(new TestSetRunService.TestSetRunCommand(
                projectId, testSetId, body.environmentId(), body.deviceKeys(),
                body.concurrency(), body.timeoutSeconds(), body.testTimeoutSeconds(),
                body.includeTag(), requestId)));
    }

    /** 勾选台账用例直接执行（EXEC-02，v0.4 候选提前实现）：后端解析绑定，前端只传用例 id。 */
    @PostMapping("/cases/run")
    public ApiResponse<RunRecord> runCases(
            @RequestParam long projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CaseRunRequest request) {
        CaseRunRequest body = request == null
                ? new CaseRunRequest(null, null, null, null, null, null, null, null) : request;
        String requestId = RunIdempotency.resolve(idempotencyKey, body.runRequestId(),
                "cases/run", Instant.now(), props.isIdempotencyFingerprintEnabled(),
                props.getIdempotencyWindowSeconds(),
                projectId, body.caseIds(), body.environmentId(), body.deviceKeys(),
                body.concurrency(), body.timeoutSeconds(), body.testTimeoutSeconds(),
                body.includeTag());
        return ApiResponse.ok(caseRuns.run(new CaseRunService.CaseRunCommand(
                projectId, body.caseIds(), body.environmentId(), body.deviceKeys(),
                body.concurrency(), body.timeoutSeconds(), body.testTimeoutSeconds(),
                body.includeTag(), requestId)));
    }

    /**
     * 安全级预检（EXEC-15 残余 / ADR-0013）。
     *
     * <p>勾选是跨页的，当前页之外那些用例的安全级前端看不到；这里按 id 一次性判级，
     * 把被拦的编号与安全级返回，前端可提前禁用「执行所选」并写出原因，而不是点了才吃 403。
     */
    @PostMapping("/cases/safety-precheck")
    public ApiResponse<CaseRunService.SafetyPrecheck> precheckCases(
            @RequestParam long projectId,
            @RequestBody(required = false) CaseSafetyPrecheckRequest request) {
        return ApiResponse.ok(caseRuns.precheck(projectId, request == null ? null : request.caseIds()));
    }

    /** CI 专用直建端点（FR-v0.2-61）：落库后立即派发，不再停留 queued。 */
    @PostMapping("/runs")
    public ApiResponse<RunRecord> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateRunRequest request) {
        int timeout = request.timeoutSeconds() == null || request.timeoutSeconds() <= 0
                ? props.getRunTimeoutSeconds() : request.timeoutSeconds();
        String requestId = RunIdempotency.resolve(idempotencyKey, request.runRequestId(),
                "runs", Instant.now(), props.isIdempotencyFingerprintEnabled(),
                props.getIdempotencyWindowSeconds(),
                request.projectId(), request.testSetId(), request.suitePaths(),
                request.concurrency(), request.environmentId(), request.deviceKeys(),
                request.priority(), timeout, request.includeTag(),
                request.testTimeoutSeconds(), request.nodeId());
        CreateRunCommand command = new CreateRunCommand(
                request.projectId(),
                request.testSetId(),
                request.suitePaths() == null ? List.of() : request.suitePaths(),
                request.concurrency() == null ? 1 : request.concurrency(),
                request.environmentId(),
                request.deviceKeys() == null ? List.of() : request.deviceKeys(),
                requestId,
                request.priority() == null ? 0 : request.priority(),
                timeout,
                request.includeTag(),
                null,
                RunService.normalizeTestTimeout(request.testTimeoutSeconds()),
                request.nodeId());
        RunRecord created = runs.create(command, new RunOrigin(RunOrigin.CI, null, null));
        quickRuns.dispatch(created, command);
        return ApiResponse.ok(created);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunRecord> get(@RequestParam long projectId,
                                      @PathVariable String runId) {
        return ApiResponse.ok(runs.get(projectId, runId));
    }

    /** 排序白名单（FR-3.2-02）：只允许这些列，未登记字段返回 400。 */
    private static final Map<String, String> SORTS = Map.of(
            "id", "id",
            "status", "run_status",
            "enqueued_at", "enqueued_at",
            "started_at", "started_at");

    @GetMapping("/runs")
    public ApiResponse<PageResult<RunRecord>> page(@RequestParam long projectId,
                                                   @RequestParam(required = false) String state,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String sort,
                                                   @RequestParam(required = false) Long beforeId) {
        RunState filter = state == null || state.isBlank() ? null : RunState.fromDb(state);
        PageQuery paging = beforeId == null
                ? PageQuery.of(page, size, sort, SORTS, "id")
                : PageQuery.keyset(size, beforeId);
        return ApiResponse.ok(runs.page(projectId, filter, paging));
    }

    /**
     * 进度快照（N15）：数据来自执行期控制台行解析（{@link RunProgressRegistry}），
     * 未跟踪或进程重启后按 run 状态推断阶段；计数器终态回落 DB 落库值。
     */
    @GetMapping("/runs/{runId}/progress")
    public ApiResponse<RunProgress> progress(@RequestParam long projectId,
                                             @PathVariable String runId) {
        RunRecord run = runs.get(projectId, runId);
        RunProgressRegistry.Progress tracked = progresses.snapshot(runId);
        int total = tracked != null && tracked.total() > 0 ? tracked.total() : run.totalTests();
        int passed = Math.max(tracked == null ? 0 : tracked.passed(), run.passedTests());
        int failed = Math.max(tracked == null ? 0 : tracked.failed(), run.failedTests());
        int completed = Math.max(tracked == null ? 0 : tracked.completed(), passed + failed);
        int skipped = tracked == null ? 0 : tracked.skipped();
        String phase = tracked != null ? tracked.phase() : phaseOf(run.state());
        long elapsed = elapsedSeconds(run, tracked);
        Instant lastActivity = tracked == null ? null : progresses.lastActivityAt(runId);
        Long heartbeatAge = RunEtaCalculator.heartbeatAgeSeconds(lastActivity, Instant.now());
        boolean finished = run.state().terminal();
        Long eta = RunEtaCalculator.etaSeconds(total, completed, elapsed, finished);
        boolean stalled = RunEtaCalculator.stalled(run.state(), heartbeatAge,
                props.getProgressStalledSeconds());
        return ApiResponse.ok(new RunProgress(runId, run.state().name(), phase, total,
                completed, passed, failed, skipped,
                tracked == null ? null : tracked.lastTest(),
                tracked == null ? null : tracked.lastVerdict(), elapsed,
                eta, heartbeatAge, stalled, finished));
    }

    private static String phaseOf(RunState state) {
        return switch (state) {
            case QUEUED -> "queued";
            case PREPARING -> "preparing";
            case RUNNING -> "running";
            default -> "done";
        };
    }

    private static long elapsedSeconds(RunRecord run, RunProgressRegistry.Progress tracked) {
        if (run.startedAt() == null) {
            return 0;
        }
        Instant end = run.finishedAt() != null ? run.finishedAt() : Instant.now();
        long seconds = Math.max(0, Duration.between(run.startedAt(), end).toSeconds());
        return seconds > 0 || tracked == null ? seconds : Math.max(0, tracked.elapsedSeconds());
    }

    @GetMapping("/runs/{runId}/log")
    public ApiResponse<List<RunLogService.LogLine>> log(@RequestParam long projectId,
                                                        @PathVariable String runId,
                                                        @RequestParam(defaultValue = "200") int limit) {
        runs.get(projectId, runId);
        int capped = Math.min(Math.max(limit, 1), RunLogService.RING_CAPACITY);
        return ApiResponse.ok(logs.recent(runId, capped));
    }

    /** 下载全量日志（EXEC-06 / FR-v0.4-21）：返回 run.log 原始文件，不走 JSON 信封。 */
    @GetMapping("/runs/{runId}/log/download")
    public ResponseEntity<Resource> downloadLog(@RequestParam long projectId,
                                                @PathVariable String runId) {
        RunRecord run = runs.get(projectId, runId);
        Resource resource = logFiles.download(run);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("run-" + runId + ".log", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(resource);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<RunRecord> cancel(@PathVariable String runId,
                                         @RequestParam long projectId,
                                         @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(cancels.cancel(projectId, runId, reason));
    }

    /**
     * 排队位次（EXEC-10 残余 / FR-v0.4-14）。
     *
     * <p>先按 {@code projectId} 校验 run 归属（越权直接 403/404，由 {@link RunService#get} 抛错），
     * 再问派发器。派发器只在进程内知道位次，重启或 run 已结束时会查不到——此时按 DB 状态回答：
     * {@code queued} 表示「排队但位次未知（position=0）」，{@code running} 表示在执行，
     * {@code terminal} 表示已结束。
     */
    @GetMapping("/runs/{runId}/pending-activity")
    public ApiResponse<RunDispatcher.QueueStatus> pendingActivity(@PathVariable String runId,
                                                                  @RequestParam long projectId) {
        RunRecord run = runs.get(projectId, runId);
        int maxParallel = Math.max(1, props.getMaxParallel());
        // FR-08：正在等设备租约时优先返回 waiting，避免被派发器的 running 盖住。
        if (leaseWaits.waiting(runId)) {
            return ApiResponse.ok(new RunDispatcher.QueueStatus(runId, "waiting", 0, 0, 0, maxParallel));
        }
        return ApiResponse.ok(dispatcher.status(runId).orElseGet(() -> {
            String state = switch (run.state()) {
                case QUEUED -> "queued";
                case PREPARING, RUNNING -> "running";
                default -> "terminal";
            };
            return new RunDispatcher.QueueStatus(runId, state, 0, 0, 0, maxParallel);
        }));
    }

    /**
     * 上一次尝试（EXEC-13 残余 / FR-v0.4-29）。
     *
     * <p>返回 {@code retry_of_run_id} 指向的原运行及其失败结论，运行详情据此显示
     * 「重试自 … · 上次失败原因」。没有重试来源时 {@code data = null}（不是 404：
     * 首次尝试本来就没有上一次）。
     */
    @GetMapping("/runs/{runId}/retry-origin")
    public ApiResponse<RunRetryOrigin> retryOrigin(@PathVariable String runId,
                                                   @RequestParam long projectId) {
        return ApiResponse.ok(runs.retryOrigin(projectId, runId));
    }

    /**
     * 租约等待耗时（EXEC-09 残余 / FR-08）。
     *
     * <p>等待中的实时秒数取自进程内登记（{@code LeaseWaitRegistry}），落库总耗时取自
     * {@code run.lease_wait_ms}：还在等待时 {@code totalMs} 为 {@code null}，界面按
     * {@code elapsedMs} 显示「已等待 N 秒」；拿到租约后 {@code state} 转 {@code settled}，
     * {@code elapsedMs} 回落到落库总耗时，两者不再打架。
     */
    @GetMapping("/runs/{runId}/lease-wait")
    public ApiResponse<RunService.LeaseWait> leaseWait(@PathVariable String runId,
                                                       @RequestParam long projectId) {
        Long totalMs = runs.leaseWaitTotalMs(projectId, runId);
        boolean waiting = leaseWaits.waiting(runId);
        long elapsedMs = waiting
                ? leaseWaits.elapsed(runId)
                : (totalMs == null ? 0 : totalMs);
        return ApiResponse.ok(new RunService.LeaseWait(waiting ? "waiting" : "settled",
                elapsedMs, totalMs));
    }

    /**
     * 进度快照（N15）。
     *
     * <p>字段口径：{@code phase} 是执行阶段（queued / preparing / running / reporting / done）；
     * {@code total} 为 0 表示总数未知（前端按不确定进度渲染，不伪造百分比）；
     * {@code lastTest}/{@code lastVerdict} 是最近一条完成用例的读数，不是「此刻正在跑哪一条」
     * ——RF 控制台只在用例结束时打印结果行，因此界面文案按「最近完成」表述。
     */
    public record RunProgress(String runId,
                              String state,
                              String phase,
                              int total,
                              int completed,
                              int passed,
                              int failed,
                              int skipped,
                              String lastTest,
                              String lastVerdict,
                              long elapsedSeconds,
                              Long etaSeconds,
                              Long heartbeatAgeSeconds,
                              boolean stalled,
                              boolean finished) {
    }

    public record CancelRequest(String reason) {
    }

    public record QuickRunRequest(Long projectId,
                                  Long testSetId,
                                  List<String> suitePaths,
                                  Long environmentId,
                                  List<String> deviceKeys,
                                  Integer concurrency,
                                  Integer timeoutSeconds,
                                  Integer testTimeoutSeconds,
                                  String includeTag,
                                  String runRequestId,
                                  String source) {
    }

    public record TestSetRunRequest(Long environmentId,
                                    List<String> deviceKeys,
                                    Integer concurrency,
                                    Integer timeoutSeconds,
                                    Integer testTimeoutSeconds,
                                    String includeTag,
                                    String runRequestId) {
    }

    public record CaseRunRequest(List<Long> caseIds,
                                 Long environmentId,
                                 List<String> deviceKeys,
                                 Integer concurrency,
                                 Integer timeoutSeconds,
                                 Integer testTimeoutSeconds,
                                 String includeTag,
                                 String runRequestId) {
    }

    /** 安全级预检请求：只需用例 id（跨页选中时可能来自不同页）。 */
    public record CaseSafetyPrecheckRequest(List<Long> caseIds) {
    }

    public record CreateRunRequest(Long projectId,
                                   Long testSetId,
                                   List<String> suitePaths,
                                   Integer concurrency,
                                   Long environmentId,
                                   List<String> deviceKeys,
                                   String runRequestId,
                                   Integer priority,
                                   Integer timeoutSeconds,
                                   Integer testTimeoutSeconds,
                                   String includeTag,
                                   /** 指定执行节点；null = 本机（默认）。节点非 ONLINE 直接拒绝。 */
                                   Long nodeId) {
    }
}
