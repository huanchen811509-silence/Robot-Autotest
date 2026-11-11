package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.application.repository.RunRetryOrigin;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.execution.RunStateMachine;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.node.ExecutionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行记录服务：创建（幂等）、查询、状态跃迁。
 */
@Service
public class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);
    /** run.device_scope 列宽；与迁移 V7_4 一致。 */
    static final int MAX_DEVICE_SCOPE_LENGTH = 512;

    private final RunRepository runs;
    private final AccessControlService access;
    private final AuditService audit;
    private final RunIdGenerator ids;
    private final ExecutionProperties props;
    private final Clock clock;
    private final List<RunCompletionListener> completionListeners;
    /** 节点发起校验用（NODX-02）；旧构造默认装配「无节点」桩，行为 = 任何 nodeId 都不存在。 */
    private final ExecutionNodeRepository nodeRepo;
    /** 并发准入（FR-3.4-02）；旧构造/单测为 null 表示不限流。 */
    private final RunAdmissionService admission;

    /** 兼容既有装配与单测：默认没有完成监听器。 */
    public RunService(RunRepository runs,
                      AccessControlService access,
                      AuditService audit,
                      RunIdGenerator ids,
                      ExecutionProperties props,
                      Clock clock) {
        this(runs, access, audit, ids, props, clock, List.of());
    }

    public RunService(RunRepository runs,
                      AccessControlService access,
                      AuditService audit,
                      RunIdGenerator ids,
                      ExecutionProperties props,
                      Clock clock,
                      List<RunCompletionListener> completionListeners) {
        this(runs, access, audit, ids, props, clock, completionListeners, NO_NODES);
    }

    /** 兼容既有单测装配：不装配准入（不限流）。 */
    public RunService(RunRepository runs,
                      AccessControlService access,
                      AuditService audit,
                      RunIdGenerator ids,
                      ExecutionProperties props,
                      Clock clock,
                      List<RunCompletionListener> completionListeners,
                      ExecutionNodeRepository nodeRepo) {
        this(runs, access, audit, ids, props, clock, completionListeners, nodeRepo, null);
    }

    @Autowired
    public RunService(RunRepository runs,
                      AccessControlService access,
                      AuditService audit,
                      RunIdGenerator ids,
                      ExecutionProperties props,
                      Clock clock,
                      List<RunCompletionListener> completionListeners,
                      ExecutionNodeRepository nodeRepo,
                      RunAdmissionService admission) {
        this.runs = runs;
        this.access = access;
        this.audit = audit;
        this.ids = ids;
        this.props = props;
        this.clock = clock;
        this.completionListeners = completionListeners == null
                ? List.of() : List.copyOf(completionListeners);
        this.nodeRepo = nodeRepo;
        this.admission = admission;
    }

    /** 「无节点」桩：旧构造（未装配节点域）下任何 nodeId 都判不存在，等价拒绝。 */
    private static final ExecutionNodeRepository NO_NODES = new ExecutionNodeRepository() {
        @Override
        public java.util.Optional<ExecutionNode> find(long id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ExecutionNode> findByName(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean nameExists(String name) {
            return false;
        }

        @Override
        public List<ExecutionNode> list() {
            return List.of();
        }

        @Override
        public ExecutionNode insert(String name, String tokenHash, boolean rfAvailable,
                                    boolean pytestAvailable, int maxConcurrency, Long operatorId,
                                    String agentBaseUrl) {
            throw new UnsupportedOperationException("NO_NODES 桩不支持注册");
        }

        @Override
        public void updateAgentBaseUrl(long id, String agentBaseUrl) {
            throw new UnsupportedOperationException("NO_NODES 桩不支持地址更新");
        }

        @Override
        public void heartbeat(long id, boolean rfAvailable, boolean pytestAvailable) {
            throw new UnsupportedOperationException("NO_NODES 桩不支持心跳");
        }

        @Override
        public int markStaleOffline(java.time.Instant staleBefore) {
            return 0;
        }

        @Override
        public void updateStatus(long id, String status, Long operatorId) {
            throw new UnsupportedOperationException("NO_NODES 桩不支持状态变更");
        }

        @Override
        public java.util.Map<Long, Integer> runningRunsByNode() {
            return java.util.Map.of();
        }

        @Override
        public List<Long> findStaleOnlineIds(java.time.Instant staleBefore) {
            return List.of();
        }

        @Override
        public int failRunningRunsByNodes(List<Long> nodeIds, String reason) {
            return 0;
        }

        @Override
        public java.util.Map<Long, ExecutionNodeRepository.NodeLastRun> latestRunsByNode() {
            return java.util.Map.of();
        }
    };

    @Transactional
    public RunRecord create(CreateRunCommand cmd) {
        return create(cmd, RunOrigin.manual());
    }

    /**
     * 手工/计划触发统一入口：origin 决定 run.trigger_type / plan_id / triggered_by_plan_at。
     * 仍走 EXECUTION:CREATE 权限校验，供页面 manual 触发与 API 使用。
     */
    @Transactional
    public RunRecord create(CreateRunCommand cmd, RunOrigin origin) {
        validate(cmd);
        access.bindProject(cmd.projectId());
        access.require(PermissionResource.EXECUTION, PermissionAction.CREATE);
        return createInternal(cmd, origin);
    }

    /**
     * 计划调度专用创建（PLN-01）：不校验当前请求身份。
     *
     * <p>计划本身已由有 EXECUTION 管理权限的人创建，暂停/删除才是管理动作；
     * 定时触发时若再按调度线程的租户上下文判权，会把计划绑死在创建者的角色上。
     * 调用方仍应在 {@code TenantContext.runAs(plan.createdBy(), "planner", projectId)} 中执行，
     * 以保证审计与租户守卫拿到正确 project_id。
     */
    @Transactional
    public RunRecord createForScheduler(CreateRunCommand cmd, RunOrigin origin) {
        validate(cmd);
        return createInternal(cmd, origin);
    }

    private RunRecord createInternal(CreateRunCommand cmd, RunOrigin origin) {
        String requestId = cmd.runRequestId() == null || cmd.runRequestId().isBlank()
                ? ids.nextRequestId() : cmd.runRequestId().trim();
        if (requestId.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runRequestId 超过 64 字符");
        }
        return runs.findByRequestId(cmd.projectId(), requestId)
                .orElseGet(() -> insertRun(cmd, requestId, origin));
    }

    private RunRecord insertRun(CreateRunCommand cmd, String requestId, RunOrigin origin) {
        Long nodeId = requireLaunchableNode(cmd.nodeId());
        Instant now = clock.instant();
        String runId = ids.next(resolveCasePrefix(cmd), now);
        String scope = deviceScope(cmd);
        RunRecord draft = new RunRecord(0L, runId, cmd.projectId(), cmd.testSetId(),
                RunState.QUEUED, cmd.priority(), cmd.concurrency(), 0,
                0, 0, 0, null, null, null, null, now, null, null, scope, requestId);
        try {
            RunRecord inserted = runs.insert(draft, runId, requestId, TenantContext.userId(), origin);
            if (nodeId != null) {
                runs.assignNode(cmd.projectId(), runId, nodeId);
            }
            audit.record("RUN_CREATE", "run", runId,
                    "suite=" + cmd.suitePaths().size() + ", concurrency=" + cmd.concurrency()
                            + ", trigger=" + origin.triggerType()
                            + (origin.planId() == null ? "" : ", plan=" + origin.planId())
                            + (nodeId == null ? "" : ", node=" + nodeId));
            if (admission != null) {
                admission.admit(cmd.projectId(), nodeId, runId);
            }
            return inserted;
        } catch (DataIntegrityViolationException duplicate) {
            return runs.findByRequestId(cmd.projectId(), requestId)
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * 节点发起校验（NODX-02）：指定节点必须是 ONLINE，否则拒绝并给明原因，
     * <b>不回落本机</b>（离线排队是 v2.1 明确不做的行为，口径见 DEV-PLAN §5.3）。
     *
     * @return 规范化后的 nodeId；未指定节点返回 null（走本机）
     */
    private Long requireLaunchableNode(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        ExecutionNode node = nodeRepo.find(nodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "执行节点不存在: " + nodeId));
        if (ExecutionNode.ONLINE.equals(node.status())) {
            return nodeId;
        }
        String reason = switch (node.status()) {
            case ExecutionNode.NEW -> "节点已注册但尚未心跳上线（NEW）";
            case ExecutionNode.OFFLINE -> "节点离线（OFFLINE"
                    + (node.lastHeartbeatAt() == null
                       ? "" : "，最后心跳 " + node.lastHeartbeatAt()) + "）";
            case ExecutionNode.DISABLED -> "节点已停用（DISABLED），先由管理员启用";
            default -> "节点状态不可发起（" + node.status() + "）";
        };
        throw new BusinessException(ErrorCode.CONFLICT,
                "节点 " + node.name() + " 不可发起: " + reason + "；不回落本机执行");
    }

    public RunRecord get(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
    }

    /**
     * 执行线程内部查询（无用户会话）：派发线程与节点 agent 专用（NODX-03）。
     * 调用方必须已在 {@code TenantContext.runAs} 的项目上下文里；不做权限校验。
     */
    public RunRecord findForRunner(long projectId, String runId) {
        return runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
    }

    /**
     * v2.7 FR-04：回写本次运行使用的测试内核（run 级快照，路由真源仍是 KernelSelector）。
     * 只在执行线程里调用，仓储未建模（内存版）时静默跳过。
     */
    public void updateKernel(String runId, String kernel) {
        runs.updateKernel(runId, kernel);
    }

    /**
     * 租约外键 {@code job_id}：{@code execution_job} 与 run 同事务创建（{@code job_no = run_id}），
     * run 表本身不保存该主键，执行前按 run_id 反查一次。
     */
    public long leaseJobId(String runId) {
        long projectId = TenantContext.requireProjectId();
        return runs.findJobId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "执行作业不存在: " + runId));
    }

    /**
     * 回写本次运行等待设备租约的耗时（FR-08）。只在执行线程里调用，失败不阻断执行主流程，
     * 所以仓储默认实现可以为空；JDBC 实现写 {@code run.lease_wait_ms}。
     */
    public int recordLeaseWait(String runId, long waitMs) {
        return runs.recordLeaseWait(runId, waitMs);
    }

    /**
     * 回读已落库的租约等待耗时（FR-08 残余）：等待态与实时秒数由 {@code LeaseWaitRegistry}
     * 提供，这里只负责把「已落库的总耗时」读出来，并顺手做归属与权限校验。
     * 还没回写（NULL）时返回 {@code null}，与「等待 0 秒」区分开。
     */
    public Long leaseWaitTotalMs(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
        return runs.findLeaseWaitMs(projectId, runId).orElse(null);
    }

    /**
     * 上一次尝试（FR-v0.4-29）：把 {@code retry_of_run_id} 背后的运行读出来，
     * 运行详情才能回答「为什么重试」。没有重试来源时返回 {@code null}。
     */
    public RunRetryOrigin retryOrigin(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
        return runs.findRetryOrigin(projectId, runId).orElse(null);
    }

    public PageResult<RunRecord> page(long projectId, RunState state, PageQuery paging) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return runs.page(projectId, state, paging);
    }

    /**
     * 状态跃迁：状态机先行校验，再落库；落库影响行数必须为 1，否则视为并发冲突。
     */
    @Transactional
    public RunRecord transition(String runId, RunState to, TransitionContext ctx) {
        long projectId = TenantContext.requireProjectId();
        RunRecord current = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
        RunStateMachine machine = new RunStateMachine(current.state());
        if (to == RunState.RUNNING) {
            machine.transition(to, ctx.leaseHeld() && ctx.artifactsReady(),
                    "租约已持有且产物目录已建");
        } else {
            machine.transition(to);
        }

        Instant startedAt = current.startedAt();
        if (to == RunState.RUNNING && startedAt == null) {
            startedAt = ctx.startedAt() == null ? clock.instant() : ctx.startedAt();
        }
        Instant finishedAt = ctx.finishedAt();
        if (to.terminal() && finishedAt == null) {
            finishedAt = clock.instant();
        }

        int affected = runs.updateState(runId, current.state(), to, startedAt, finishedAt,
                ctx.failureClass(), ctx.failureReason(), ctx.failureMessage());
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已被并发修改: " + runId);
        }
        audit.record("RUN_" + to.name(), "run", runId,
                ctx.failureReason() == null ? "" : ctx.failureReason());
        if (to.terminal() && admission != null) {
            admission.release(runId);
        }
        RunRecord completed = new RunRecord(current.id(), current.runId(), current.projectId(),
                current.testSetId(), to, current.priority(), current.concurrency(),
                current.attemptNo(), current.totalTests(), current.passedTests(),
                current.failedTests(), ctx.failureClass(), ctx.failureReason(),
                ctx.failureMessage(), current.artifactsDir(), current.enqueuedAt(),
                startedAt, finishedAt, current.deviceScope(), current.runRequestId());
        if (to.terminal()) {
            notifyCompletion(completed);
        }
        return completed;
    }

    /**
     * 终态完成通知（PLN-03 钩子）：有事务时在<b>事务提交后</b>执行，避免把回写 HTTP 调用
     * 拖进 run 状态事务；监听器异常一律吞掉并记录，<b>不影响 run 结论</b>。
     */
    private void notifyCompletion(RunRecord completed) {
        Runnable task = () -> {
            for (RunCompletionListener listener : completionListeners) {
                try {
                    listener.onCompleted(completed);
                } catch (RuntimeException ex) {
                    LOG.warn("run {} 的完成监听器 {} 执行失败（已忽略）: {}", completed.runId(),
                            listener.getClass().getSimpleName(), ex.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * 回写用例统计与产物目录。影响行数必须为 1，否则视为并发冲突。
     */
    @Transactional
    public RunRecord updateCounts(String runId, int total, int passed, int failed, String artifactsDir) {
        long projectId = TenantContext.requireProjectId();
        RunRecord current = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
        int affected = runs.updateCounts(runId, total, passed, failed, artifactsDir);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行统计已被并发修改: " + runId);
        }
        return new RunRecord(current.id(), current.runId(), current.projectId(), current.testSetId(),
                current.state(), current.priority(), current.concurrency(), current.attemptNo(),
                total, passed, failed, current.failureClass(), current.failureReason(),
                current.failureMessage(), artifactsDir, current.enqueuedAt(),
                current.startedAt(), current.finishedAt(), current.deviceScope(), current.runRequestId());
    }

    private void validate(CreateRunCommand cmd) {
        if (cmd == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请求为空");
        }
        if (cmd.projectId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        if (cmd.suitePaths() == null || cmd.suitePaths().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "suitePaths 不能为空");
        }
        int maxParallel = Math.max(1, props.getMaxParallel());
        if (cmd.concurrency() < 1 || cmd.concurrency() > maxParallel) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "concurrency 必须在 1~" + maxParallel);
        }
        if (cmd.priority() < 0 || cmd.priority() > 9) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "priority 必须在 0~9");
        }
        if (cmd.timeoutSeconds() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "timeoutSeconds 必须为正");
        }
    }

    /**
     * FR-v0.4-01：设备范围落库。逗号分隔、去重保序；空/全空白记 NULL。
     *
     * <p>这里只做持久化口径（≤512 字符），单键长度与互斥语义由执行期
     * {@code RunExecutionService.resourceKeys} 负责——同一个列表在两侧各有各的边界，
     * 写库时放宽是为了历史回溯不被执行期规则绑死。
     */
    static String deviceScope(CreateRunCommand cmd) {
        List<String> keys = cmd.deviceKeys();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : keys) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.trim();
            if (!normalized.contains(key)) {
                normalized.add(key);
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        String joined = String.join(",", normalized);
        if (joined.length() > MAX_DEVICE_SCOPE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "deviceScope 超过 " + MAX_DEVICE_SCOPE_LENGTH + " 字符");
        }
        return joined;
    }

    private String resolveCasePrefix(CreateRunCommand cmd) {
        List<String> suites = cmd.suitePaths();
        if (suites.isEmpty()) {
            return "";
        }
        String first = suites.get(0);
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        String name = slash >= 0 ? first.substring(slash + 1) : first;
        return name.replaceAll("\\.robot$", "");
    }

    public record CreateRunCommand(long projectId,
                                   Long testSetId,
                                   List<String> suitePaths,
                                   int concurrency,
                                   Long environmentId,
                                   List<String> deviceKeys,
                                   String runRequestId,
                                   int priority,
                                   int timeoutSeconds,
                                   String includeTag,
                                   List<String> testNames,
                                   int testTimeoutSeconds,
                                   /** 指定执行节点；null = 本机执行（默认，v2.0 语义）。 */
                                   Long nodeId) {

        /** 兼容既有构造点（未指定节点 = 本机）。 */
        public CreateRunCommand(long projectId, Long testSetId, List<String> suitePaths,
                                int concurrency, Long environmentId, List<String> deviceKeys,
                                String runRequestId, int priority, int timeoutSeconds,
                                String includeTag, List<String> testNames, int testTimeoutSeconds) {
            this(projectId, testSetId, suitePaths, concurrency, environmentId, deviceKeys,
                    runRequestId, priority, timeoutSeconds, includeTag, testNames,
                    testTimeoutSeconds, null);
        }
    }

    /** 单用例超时（秒）上限；与前端输入框上限一致。 */
    public static final int MAX_TEST_TIMEOUT_SECONDS = 86400;

    /**
     * 单用例超时归一（FR-v0.4-24）：{@code null} 或 {@code <=0} 表示沿用全局默认
     * {@code execution.test-timeout-seconds}，这里用 0 占位；超过上限直接拒绝。
     */
    public static int normalizeTestTimeout(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return 0;
        }
        if (seconds > MAX_TEST_TIMEOUT_SECONDS) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单用例超时最多 " + MAX_TEST_TIMEOUT_SECONDS + " 秒");
        }
        return seconds;
    }

    /**
     * 租约等待现状（FR-08 残余：等待时长回读）。
     *
     * @param state     {@code waiting} = 此刻仍在等租约；{@code settled} = 已拿到或已结束
     * @param elapsedMs 等待中为实时已等待毫秒；否则等于 {@code totalMs}（同样未回写时为 0）
     * @param totalMs   落库的本次等待总耗时；还没回写为 {@code null}
     */
    public record LeaseWait(String state, long elapsedMs, Long totalMs) {
    }

    public record TransitionContext(Instant startedAt,
                                    Instant finishedAt,
                                    String failureClass,
                                    String failureReason,
                                    String failureMessage,
                                    boolean leaseHeld,
                                    boolean artifactsReady) {

        public static TransitionContext plain() {
            return new TransitionContext(null, null, null, null, null, false, false);
        }

        public TransitionContext withStartedAt(Instant value) {
            return new TransitionContext(value, finishedAt, failureClass, failureReason,
                    failureMessage, leaseHeld, artifactsReady);
        }

        public TransitionContext withArtifactsReady(boolean value) {
            return new TransitionContext(startedAt, finishedAt, failureClass, failureReason,
                    failureMessage, leaseHeld, value);
        }

        public TransitionContext withLeaseHeld(boolean value) {
            return new TransitionContext(startedAt, finishedAt, failureClass, failureReason,
                    failureMessage, value, artifactsReady);
        }
    }
}
