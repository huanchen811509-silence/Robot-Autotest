package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 项目级执行协调者（FR-v5.2-03 / 验收 B7–B9 / W52-04）。
 *
 * <p>把「一次用户操作要跑什么」翻译成「落哪几个 run、什么时候能跑、失败了怎么办」。
 * 三件事都在这里，因为它们是同一个决策的三个侧面：
 * <ul>
 *   <li><b>拆单</b>：混内核的用例集必须拆成多个 run（{@link RunSplitter}）；</li>
 *   <li><b>准入</b>：执行窗口（{@link ExecutionWindow}）与独占约束；</li>
 *   <li><b>台账</b>：今日跑了多少、成功率、均时长、在跑与排队——给协调者自己与页面看。</li>
 * </ul>
 *
 * <p><b>为什么窗口拒绝而不是排队</b>：排队到天亮看起来更「智能」，实际会让用户
 * 在第二天看到一个昨晚发起、今早才跑的结果，而中间那段时间的资产状态早已变化。
 * 明确拒绝 + 告知下一次窗口，用户才能决定是等到晚上还是走加急流程。
 */
@Service
public class ProjectRunCoordinator {

    /** 配置键前缀：执行域自己的配置，不借用任何内核的命名空间。 */
    public static final String PREFIX = "execution.";

    public static final String WINDOW_START = PREFIX + "window_start";
    public static final String WINDOW_END = PREFIX + "window_end";
    public static final String WINDOW_EXCLUSIVE = PREFIX + "window_exclusive";
    public static final String STOP_THRESHOLD = PREFIX + "stop_threshold";
    public static final String MIN_PASS_RATE = PREFIX + "min_pass_rate";
    /** 长跑阈值（秒）：预计耗时超过它就算长跑，只允许在窗口内发起。 */
    public static final String LONG_RUN_THRESHOLD = PREFIX + "long_run_threshold_s";
    /** 默认 30 分钟。低于 30 分钟的批量不值得为它排夜间窗口。 */
    public static final long DEFAULT_LONG_RUN_THRESHOLD_SECONDS = 1800;

    /** 时区固定 Asia/Shanghai：窗口是给本地运维看的，跟随服务器时区会漂。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final RunService runs;
    private final RunRepository runRepository;
    private final ProjectSettingRepository settings;
    private final AccessControlService access;
    private final DurationBaselineService baselines;

    public ProjectRunCoordinator(RunService runs, RunRepository runRepository,
                                 ProjectSettingRepository settings, AccessControlService access) {
        this(runs, runRepository, settings, access, null);
    }

    /**
     * @param baselines 时长基线服务，用于长跑识别（W53-08）。可为 null——
     *                  没有基线时退化为「全是短跑」，即长跑保护不生效，
     *                  而不是「所有 run 都被当成短跑放行」的假象：{@code longRun} 会如实标 false。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ProjectRunCoordinator(RunService runs, RunRepository runRepository,
                                 ProjectSettingRepository settings, AccessControlService access,
                                 DurationBaselineService baselines) {
        this.runs = runs;
        this.runRepository = runRepository;
        this.settings = settings;
        this.access = access;
        this.baselines = baselines;
    }

    /**
     * 准入结论。
     *
     * <p>{@code hint} 在 {@code allowed=false} 时是「下一次什么时候能跑」；
     * 在 {@code allowed=true} 时可能是**放行提示**（例如长跑但项目没配窗口）——
     * 两者靠 {@code allowed} 区分，不靠 {@code hint} 是否为空。
     */
    public record Admission(boolean allowed, String windowDescription, boolean exclusive,
                            String reason, String hint, long estimatedSeconds, boolean longRun) {
    }

    /**
     * 长跑判定（W53-08）：预计耗时超过 {@value #DEFAULT_LONG_RUN_THRESHOLD_SECONDS} 秒算长跑。
     *
     * <p>长跑与短跑的区别不在「多久」，而在「出错代价」：一个 40 分钟的批量跑到一半
     * 撞上白天的高峰，会长时间占住设备与租约，别人一整天都排不进来。因此长跑
     * <b>只在窗口内允许发起</b>；窗口外想跑就等到窗口开，而不是「先跑起来再说」。
     *
     * <p>估算用基线 P90 求和（见 {@code DurationBaselineService.estimateSeconds}），
     * 基线覆盖率低时估算基本靠兜底，此时仍然按估算判——宁可把短跑误判成长跑
     * （用户等到晚上），也不要把长跑误判成短跑（占满一整天）。
     */
    public record LongRunCheck(boolean longRun, long estimatedSeconds, long thresholdSeconds,
                               int covered, int fallback, double coveragePercent) {
    }

    /** 按用例列表判定是否长跑。没有基线服务时返回「不是长跑 + 0 秒」。 */
    public LongRunCheck longRunOf(long projectId, List<RunSplitter.PlannedCase> cases) {
        long threshold = thresholdOf(projectId);
        if (baselines == null) {
            return new LongRunCheck(false, 0, threshold, 0, 0, 0.0);
        }
        DurationBaselineService.Estimate estimate = baselines.estimateSeconds(projectId, cases);
        return new LongRunCheck(estimate.seconds() >= threshold, estimate.seconds(), threshold,
                estimate.covered(), estimate.fallback(), estimate.coveragePercent());
    }

    /** 长跑阈值（秒），未配置或配错用默认 30 分钟。 */
    public long thresholdOf(long projectId) {
        Map<String, String> all = settings.findAll(projectId, PREFIX);
        String raw = all.get("long_run_threshold_s");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LONG_RUN_THRESHOLD_SECONDS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : DEFAULT_LONG_RUN_THRESHOLD_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_LONG_RUN_THRESHOLD_SECONDS;
        }
    }

    /** 拆单预览：不落库，页面可以先展示「这次会跑成 2 个 run」。 */
    public record SplitPlan(List<RunSplitter.SplitRun> runs, int totalCases, boolean mixedKernel,
                            String windowDescription, boolean windowOpen) {

        public int runCount() {
            return runs.size();
        }
    }

    /** 项目台账。{@code successRatePercent} 按用例级算（见 {@code RunLedgerStats}）。 */
    public record Ledger(int runsToday, int finishedRuns, int failedRuns, int running,
                         int queued, int totalTests, int passedTests, int failedTests,
                         double successRatePercent, long avgDurationSeconds,
                         String windowDescription, boolean windowOpen, boolean exclusive) {
    }

    /** 提交时的公共参数；{@code cases} 决定拆成几单。 */
    public record SubmitRequest(List<RunSplitter.PlannedCase> cases,
                                int concurrency,
                                Long environmentId,
                                List<String> deviceKeys,
                                int priority,
                                int timeoutSeconds,
                                String includeTag,
                                int testTimeoutSeconds,
                                Long nodeId,
                                String requestPrefix) {
    }

    /** 提交结果：每个内核一单。{@code runs} 的顺序即建议的执行顺序。 */
    public record SubmitResult(List<RunRecord> runs, SplitPlan plan, LongRunCheck longRun,
                               String notice) {
    }

    // ------------------------------------------------------------------ 准入

    /** 当前是否允许在该项目发起执行。 */
    public Admission admit(long projectId) {
        return admit(projectId, LocalTime.now(ZONE), null);
    }

    /**
     * 指定时刻的准入判定（时间可注入，便于单测与「演练某个时间点」）。
     *
     * <p>独占检查在窗口检查之后：窗口关了的时候，独占状态无关紧要，
     * 先报窗口能直接告诉用户「等到 23:00」。
     */
    public Admission admit(long projectId, LocalTime at) {
        return admit(projectId, at, null);
    }

    /**
     * 带长跑信息的准入（W53-08）。
     *
     * <p>三条判定顺序是刻意的：
     * <ol>
     *   <li><b>窗口</b>：关着就直接拒，理由里说清「是长跑所以更要等」；</li>
     *   <li><b>长跑 + 无窗口</b>：<b>放行但提示</b>。没配窗口说明用户本来就不想要窗口，
     *       此时拒绝长跑等于用一条他没开过的规则挡住他；提示让他知道风险即可；</li>
     *   <li><b>独占</b>：有 run 在跑就不放。</li>
     * </ol>
     *
     * @param longRun 长跑判定结果；null 表示不判定（等价于短跑）
     */
    public Admission admit(long projectId, LocalTime at, LongRunCheck longRun) {
        ExecutionWindow window = windowOf(projectId);
        boolean isLong = longRun != null && longRun.longRun();
        long estimated = longRun == null ? 0 : longRun.estimatedSeconds();
        String hhmm = at.withSecond(0).withNano(0).toString();

        if (!window.openAt(at)) {
            String reason = isLong
                    ? "当前 " + hhmm + " 不在执行窗口内（" + window.describe()
                            + "）；本次预计耗时 " + duration(estimated) + "，属长跑，只能在窗口内发起"
                    : "当前 " + hhmm + " 不在执行窗口内（" + window.describe() + "）";
            return new Admission(false, window.describe(), window.exclusive(), reason,
                    window.nextOpening(), estimated, isLong);
        }
        if (isLong && !window.timeBounded()) {
            return new Admission(true, window.describe(), window.exclusive(), null,
                    "本次预计耗时 " + duration(estimated)
                            + "，属长跑；项目未配置执行窗口，长跑会占用白天设备，建议配置窗口",
                    estimated, true);
        }
        if (window.exclusive()) {
            RunRepository.RunLedgerStats stats = runRepository.ledger(projectId,
                    todayStart().minusSeconds(1));
            int active = stats.running();
            if (active > 0) {
                return new Admission(false, window.describe(), true,
                        "项目配置了窗口内独占，但当前已有 " + active + " 个 run 在执行",
                        "等当前 run 结束后再发起，或改为非独占", estimated, isLong);
            }
        }
        return new Admission(true, window.describe(), window.exclusive(), null, null,
                estimated, isLong);
    }

    /** 人类可读的时长：{@code 3720} → {@code 1h 2m}。 */
    static String duration(long seconds) {
        if (seconds <= 0) {
            return "未知";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        return minutes > 0 ? minutes + "m" : seconds + "s";
    }

    /** 读取窗口配置（配置非法时抛 INVALID_ARGUMENT，不静默回落全天可跑）。 */
    public ExecutionWindow windowOf(long projectId) {
        Map<String, String> all = settings.findAll(projectId, PREFIX);
        try {
            return ExecutionWindow.of(all.get("window_start"), all.get("window_end"),
                    all.get("window_exclusive"));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 拆单

    /** 拆单预览：不校验权限、不落库，只算。 */
    public SplitPlan plan(long projectId, List<RunSplitter.PlannedCase> cases) {
        List<RunSplitter.SplitRun> split;
        try {
            split = RunSplitter.split(cases);
        } catch (RunSplitter.SplitMismatchException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "拆单结果与输入不一致（程序错误）：" + e.getMessage());
        }
        ExecutionWindow window = windowOf(projectId);
        boolean mixed = split.size() > 1;
        List<RunSplitter.SplitRun> ordered = order(split, cases);
        return new SplitPlan(ordered, cases == null ? 0 : cases.size(), mixed,
                window.describe(), window.openAt(LocalTime.now(ZONE)));
    }

    /**
     * 组间顺序。
     *
     * <p>{@link RunSplitter} 已给出「用例数降序 + 内核名」的稳定顺序；
     * 这里再叠加一条业务偏好：**pytest 单排在 RF 单之后**。
     * 理由是 RF 的用例通常承载主流程，先跑能让关键结论更早出来；
     * pytest 库当前实现率低，放后面不会拖慢主流程的可见结果。
     *
     * <p>该偏好只调整组间次序，不动组内用例顺序，也不会让任何用例落到别的单里。
     */
    private static List<RunSplitter.SplitRun> order(List<RunSplitter.SplitRun> split,
                                                    List<RunSplitter.PlannedCase> cases) {
        List<RunSplitter.SplitRun> ordered = new ArrayList<>(split);
        ordered.sort((a, b) -> {
            boolean aPytest = "pytest".equals(a.kernel());
            boolean bPytest = "pytest".equals(b.kernel());
            if (aPytest != bPytest) {
                return aPytest ? 1 : -1;
            }
            if (a.caseCount() != b.caseCount()) {
                return Integer.compare(b.caseCount(), a.caseCount());
            }
            return a.kernel().compareTo(b.kernel());
        });
        return List.copyOf(ordered);
    }

    /**
     * 提交执行：拆单 → 逐单建 run。
     *
     * <p>任一步失败不做回滚：已经建好的 run 会照常执行。理由是一次提交拆出的多单
     * 之间没有事务语义——把第一单取消掉再抛错，用户会看到「说失败但有一个 run 在跑」，
     * 比「建了两单、第三单失败」更难理解。宁可少建，不可建了又撤。
     */
    public SubmitResult submit(long projectId, SubmitRequest request) {
        access.bindProject(projectId);
        List<RunSplitter.PlannedCase> cases = request.cases() == null ? List.of() : request.cases();
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "没有可执行的用例");
        }
        LongRunCheck longRun = longRunOf(projectId, cases);
        Admission admission = admit(projectId, LocalTime.now(ZONE), longRun);
        if (!admission.allowed()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    admission.reason() + "；" + admission.hint());
        }
        SplitPlan plan = plan(projectId, cases);
        List<RunRecord> created = new ArrayList<>();
        for (RunSplitter.SplitRun one : plan.runs()) {
            String requestId = requestId(request.requestPrefix(), one.kernel());
            RunService.CreateRunCommand command = new RunService.CreateRunCommand(
                    projectId, null, one.suitePaths(), request.concurrency(),
                    request.environmentId(), request.deviceKeys(), requestId,
                    Math.max(request.priority(), one.priority()), request.timeoutSeconds(),
                    request.includeTag(), caseCodesOf(one), request.testTimeoutSeconds(),
                    request.nodeId());
            created.add(runs.create(command));
        }
        return new SubmitResult(List.copyOf(created), plan, longRun, admission.hint());
    }

    /**
     * 幂等键：同一次提交的每个内核一单，键为 {@code 前缀#内核}。
     *
     * <p>不带时间戳：重复点提交会命中同一个 key，被 {@code findByRequestId} 去重，
     * 而不是在两次点击之间跑出两批一模一样的 run。
     */
    private static String requestId(String prefix, String kernel) {
        String base = prefix == null || prefix.isBlank()
                ? "BATCH-" + LocalDate.now(ZONE) : prefix.trim();
        String value = base + "#" + kernel;
        if (value.length() > 64) {
            String suffix = "#" + kernel;
            value = base.substring(0, 64 - suffix.length()) + suffix;
        }
        return value;
    }

    private static List<String> caseCodesOf(RunSplitter.SplitRun run) {
        List<String> codes = new ArrayList<>();
        for (RunSplitter.PlannedCase item : run.cases()) {
            if (item.caseCode() != null && !item.caseCode().isBlank()) {
                codes.add(item.caseCode());
            }
        }
        return List.copyOf(codes);
    }

    // ------------------------------------------------------------------ 台账

    /** 今日台账（自然日，Asia/Shanghai）。 */
    public Ledger ledger(long projectId) {
        RunRepository.RunLedgerStats stats = runRepository.ledger(projectId, todayStart());
        ExecutionWindow window = windowOf(projectId);
        return new Ledger(stats.runs(), stats.finished(), stats.failed(), stats.running(),
                stats.queued(), stats.totalTests(), stats.passedTests(), stats.failedTests(),
                stats.successRatePercent(), stats.avgDurationSeconds(),
                window.describe(), window.openAt(LocalTime.now(ZONE)), window.exclusive());
    }

    // ------------------------------------------------------------------ 裁决

    /** 裁决输入：调用方补齐分类与计数，本类只读阈值配置。 */
    public record FailureContext(String category, int attemptNo, int maxAttempts,
                                 int consecutiveSameType, int passRatePercent) {
    }

    /** 按项目配置的阈值裁决（阈值未配 = 不启用对应止损）。 */
    public RunVerdictPolicy.Verdict judge(long projectId, FailureContext context) {
        Map<String, String> all = settings.findAll(projectId, PREFIX);
        int stopThreshold = intOrZero(all.get("stop_threshold"));
        int minPassRate = intOrZero(all.get("min_pass_rate"));
        return RunVerdictPolicy.decide(new RunVerdictPolicy.Input(context.category(),
                context.attemptNo(), context.maxAttempts(), context.consecutiveSameType(),
                stopThreshold, context.passRatePercent(), minPassRate));
    }

    private static int intOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // 配置错不该让裁决抛异常：止损阈值配错最多是「没止损」，
            // 而抛异常会让整批执行卡在裁决这一步。
            return 0;
        }
    }

    private static Instant todayStart() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** 审计用：当前操作者（可为 null，调用方自行决定要不要用）。 */
    static Long currentOperator() {
        return TenantContext.userId();
    }
}
