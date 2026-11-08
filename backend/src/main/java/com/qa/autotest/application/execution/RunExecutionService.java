package com.qa.autotest.application.execution;

import com.qa.autotest.application.node.NodeChannel;
import com.qa.autotest.application.node.NodeTask;
import com.qa.autotest.application.node.TaskSigner;
import com.qa.autotest.domain.node.ExecutionNode;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.TestResultWriter;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.execution.FailureClass;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 运行执行编排（EXEC-09）：创建 -> 预检 -> 建目录 -> 申请租约 -> RUNNING -> pabot -> 校验 -> 终态 -> 释放租约。
 *
 * <p>租约按 run 维度覆盖本次运行要占用的资源键集合：有设备范围时用「设备+端口」键，
 * 缺省退化为环境（{@code environment:<id>}）或项目（{@code project:<id>}）独占键。
 * 申请失败不进入 RUNNING，直接 failed(lease_timeout)；任何离开执行段的路径都释放租约。
 *
 * <p><b>键口径</b>：只有请求显式给出 {@code deviceKeys}（FR-01 设备范围）时才按「设备+端口」互斥；
 * 未声明设备范围的运行（工作区与端口已按 run 隔离）用 {@code run:<runId>} 自持，
 * 避免 MAX_PARALLEL 内的并发运行被一条项目级租约串行化甚至超时失败。
 */
@Service
public class RunExecutionService {

    /** {@code resource_lease.resource_key} 列宽（V1 基线 128）。 */
    static final int MAX_RESOURCE_KEY_LENGTH = 128;

    private final RunService runs;
    private final ExecutionProperties props;
    private final ExecutionPrecheck precheck;
    private final LeaseService leases;
    private final ExecutionWorkspaceMaterializer workspaces;
    private final PabotCommandBuilder commands;
    private final PabotOrchestrator orchestrator;
    private final RunArtifactRepository artifacts;
    private final TestResultWriter results;
    private final FailureClassifier classifier;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final LeaseWaitRegistry leaseWaits;
    /** NODX-03：节点投递；旧构造默认空实现（本机语义，任何 nodeId 都判不可投递）。 */
    private final ExecutionNodeRepository nodeRepo;
    private final NodeChannel nodeChannel;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** v2.7 双内核：执行链唯一路由真源；null（旧构造/单测）时按 RF 处理，行为不变。 */
    private final com.qa.autotest.application.kernel.KernelSelector kernelSelector;
    private final com.qa.autotest.application.kernel.PytestRunAdapter pytestRuns;

    public RunExecutionService(RunService runs,
                               ExecutionProperties props,
                               ExecutionPrecheck precheck,
                               LeaseService leases,
                               ExecutionWorkspaceMaterializer workspaces,
                               PabotCommandBuilder commands,
                               PabotOrchestrator orchestrator,
                               RunArtifactRepository artifacts,
                               TestResultWriter results,
                               FailureClassifier classifier,
                               RetryPolicy retryPolicy,
                               Clock clock,
                               LeaseWaitRegistry leaseWaits) {
        this(runs, props, precheck, leases, workspaces, commands, orchestrator, artifacts,
                results, classifier, retryPolicy, clock, leaseWaits, null, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunExecutionService(RunService runs,
                               ExecutionProperties props,
                               ExecutionPrecheck precheck,
                               LeaseService leases,
                               ExecutionWorkspaceMaterializer workspaces,
                               PabotCommandBuilder commands,
                               PabotOrchestrator orchestrator,
                               RunArtifactRepository artifacts,
                               TestResultWriter results,
                               FailureClassifier classifier,
                               RetryPolicy retryPolicy,
                               Clock clock,
                               LeaseWaitRegistry leaseWaits,
                               ExecutionNodeRepository nodeRepo,
                               NodeChannel nodeChannel,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                               com.qa.autotest.application.kernel.KernelSelector kernelSelector,
                               com.qa.autotest.application.kernel.PytestRunAdapter pytestRuns) {
        this.runs = runs;
        this.props = props;
        this.precheck = precheck;
        this.leases = leases;
        this.workspaces = workspaces;
        this.commands = commands;
        this.orchestrator = orchestrator;
        this.artifacts = artifacts;
        this.results = results;
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.leaseWaits = leaseWaits;
        this.nodeRepo = nodeRepo;
        this.nodeChannel = nodeChannel;
        this.objectMapper = objectMapper == null
                ? new com.fasterxml.jackson.databind.ObjectMapper() : objectMapper;
        this.kernelSelector = kernelSelector;
        this.pytestRuns = pytestRuns;
    }

    public RunRecord execute(RunService.CreateRunCommand cmd) {
        RunRecord created = runs.create(cmd);
        return execute(created, cmd);
    }

    /**
     * SCEN-03：按场景登记一次执行，落库一个带全新 RUN_ID 的 run 并返回。
     *
     * <p>最小改动：复用既有 {@code RunService.create} 建 run（RUN_ID 由 RunIdGenerator 生成，
     * 同秒也有单调序列保证唯一），不派发 pabot、不重写执行引擎。
     * {@code runRequestId} 留空，故连续两次调用各生成独立 RUN_ID；
     * 套件路径仅为占位标记（本端点不派发执行），场景到用例集的解析留待后续包。
     */
    public RunRecord executeScenario(long projectId, long scenarioId) {
        RunService.CreateRunCommand cmd = new RunService.CreateRunCommand(
                projectId,
                null,
                List.of("scenario/" + scenarioId),
                1,
                null,
                List.of(),
                null,
                0,
                props.getRunTimeoutSeconds(),
                null,
                List.of(),
                0);
        return runs.create(cmd);
    }

    /**
     * 执行一条已创建的 run（PLN-01 计划调度用）。
     *
     * <p>调度器先把 run 以 trigger_type='schedule' + plan_id 落库（短事务提交），
     * 再通过单线程 TaskExecutor 调本方法，避免把长耗时的 pabot 执行包在调度事务里。
     */
    public RunRecord execute(RunRecord created, RunService.CreateRunCommand cmd) {
        if (cmd.nodeId() != null) {
            return executeOnNode(created, cmd);
        }
        // NODX-03：节点与主控同库——主控投递前已迁 PREPARING，节点侧不重复迁移
        //（状态机禁同态跃迁）。本机路径 state=QUEUED，照常迁移。
        RunRecord current = runs.findForRunner(created.projectId(), created.runId());
        if (current.state() != RunState.PREPARING) {
            runs.transition(created.runId(), RunState.PREPARING,
                    RunService.TransitionContext.plain());
        }

        // v2.7 FR-04：执行链内核路由唯一走 KernelSelector（项目默认→覆盖），run 表记快照。
        // 旧构造/单测 kernelSelector 为 null → 按 RF 处理，既有行为不变。
        String kernel = com.qa.autotest.application.kernel.RobotKernel.NAME;
        if (kernelSelector != null) {
            kernel = kernelSelector.select(created.projectId(), null, null).kernel();
            runs.updateKernel(created.runId(), kernel);
            if (com.qa.autotest.application.kernel.PytestKernel.NAME.equals(kernel)
                    && cmd.nodeId() != null) {
                // pytest 走本机适配器（xdist 承担并行）；节点投递是 pabot 语义，本版范围外，
                // 不静默回落 RF（会跑错内核），直接判失败。
                return fail(created, "node_dispatch_unsupported",
                        "pytest 内核不支持节点投递（本版范围外）：runId=" + created.runId());
            }
        }

        Path artifactsRoot = ExecutionPaths.resolve(props.getArtifactsRoot());
        Path runDir = artifactsRoot.resolve(created.runId());
        try {
            Files.createDirectories(runDir);
        } catch (Exception ex) {
            return fail(created, "artifact_dir_failed", "创建产物目录失败: " + ex.getMessage());
        }

        // EXEC-05：数据库 robot_file 是脚本真源，先落盘到本次 run 的独立工作区；
        // 数据库为空时回落到 execution.workspace-root，兼容仍在磁盘维护脚本的项目。
        Path workDir;
        boolean materialized;
        try {
            Optional<Path> copy = workspaces.materialize(created.projectId(), runDir);
            materialized = copy.isPresent();
            workDir = copy.orElseGet(() -> ExecutionPaths.resolve(props.getWorkspaceRoot()));
        } catch (Exception ex) {
            return fail(created, "workspace_materialize_failed",
                    "执行工作区落盘失败: " + ex.getMessage());
        }
        // FR-v0.4-24 单用例超时：RF 没有全局 CLI 开关，只能在套件 `*** Settings ***` 注入
        // `Test Timeout`。只改本 run 的副本（materialize 成功时），
        // 不碰数据库脚本与真实 workspace-root（回落分支下那是用户原脚本）。
        // 本次触发显式给了 testTimeoutSeconds 就用它，否则沿用全局默认。
        // v2.7：pytest 没有 Test Timeout 概念（超时由 run 级 timeout 兜底），跳过注入。
        if (materialized && !com.qa.autotest.application.kernel.PytestKernel.NAME.equals(kernel)) {
            int testTimeout = cmd.testTimeoutSeconds() > 0
                    ? cmd.testTimeoutSeconds() : props.getTestTimeoutSeconds();
            TestTimeoutInjector.inject(workDir, cmd.suitePaths(), testTimeout);
        }

        List<ExecutionPrecheck.Violation> violations = precheck.check(
                new ExecutionPrecheck.PrecheckRequest(
                        ExecutionPaths.resolve(props.getVenvBin()),
                        artifactsRoot,
                        cmd.suitePaths(),
                        cmd.concurrency(),
                        props.getAvailablePorts(),
                        props.getMinFreeBytes(),
                        props.getPabotVersionPrefix(),
                        List.of()));
        if (!violations.isEmpty()) {
            String reason = "precheck_" + violations.get(0).code().toLowerCase();
            String message = violations.stream()
                    .map(v -> v.code() + ": " + v.message())
                    .reduce((a, b) -> a + "; " + b).orElse("");
            return fail(created, reason, message);
        }

        // EXEC-09：进入 RUNNING 之前必须先持有租约（状态机的前置条件由 RunService 强制校验）。
        List<String> resourceKeys;
        try {
            resourceKeys = resourceKeys(created.runId(), cmd);
        } catch (IllegalArgumentException invalidKey) {
            return fail(created, "lease_key_invalid", invalidKey.getMessage());
        }
        long jobId;
        try {
            jobId = runs.leaseJobId(created.runId());
        } catch (BusinessException missingJob) {
            return fail(created, "lease_job_missing",
                    "找不到执行作业记录，无法申请租约: " + missingJob.getMessage());
        }
        List<LeaseService.LeaseRequest> requests = new ArrayList<>();
        for (String key : resourceKeys) {
            requests.add(new LeaseService.LeaseRequest(
                    key, jobId, created.runId(), created.projectId()));
        }
        Duration maxWait = Duration.ofSeconds(Math.max(0, props.getLeaseMaxWaitSeconds()));
        boolean acquired;
        // FR-08：等租约期间登记「等待资源」，接口据此显示 waiting；结束（成功/超时/异常）都回写耗时。
        leaseWaits.begin(created.runId());
        try {
            acquired = leases.acquireAll(requests, maxWait);
        } catch (RuntimeException acquireFailed) {
            runs.recordLeaseWait(created.runId(), leaseWaits.end(created.runId()));
            return fail(created, "lease_acquire_failed",
                    "申请租约异常: " + acquireFailed.getMessage());
        }
        runs.recordLeaseWait(created.runId(), leaseWaits.end(created.runId()));
        if (!acquired) {
            return fail(created, "lease_timeout", FailureClass.INFRA,
                    "等待资源 " + String.join(", ", resourceKeys) + " 超过 "
                            + maxWait.toSeconds() + " 秒仍被占用，本次运行终止");
        }

        String releaseReason = "failed";
        try {
            runs.transition(created.runId(), RunState.RUNNING,
                    new RunService.TransitionContext(clock.instant(), null, null, null, null, true, true));

            PabotCommandBuilder.PabotCommand command;
            PabotOrchestrator.ShardResult result;
            if (com.qa.autotest.application.kernel.PytestKernel.NAME.equals(kernel)) {
                // v2.7 FR-01：pytest 内核走专用适配器（venv pytest + json-report，
                // 并行由 xdist 承担）；产物校验 = 可归一。RF 路径逐字等价不动。
                command = pytestRuns.build(created.projectId(), created.runId(),
                        cmd.suitePaths(), Math.max(1, cmd.concurrency()), runDir, workDir);
                result = pytestRuns.run(created.runId(), command,
                        Duration.ofSeconds(cmd.timeoutSeconds()));
            } else {
                int processes = Math.min(cmd.concurrency(), cmd.suitePaths().size());
                PabotCommandBuilder.ShardSpec shard = new PabotCommandBuilder.ShardSpec(
                        0, cmd.suitePaths(), processes, cmd.includeTag(),
                        "run-" + created.runId(), cmd.testNames());
                command = commands.build(shard, props, runDir, workDir);
                result = orchestrator.runShard(
                        created.runId(), command, Duration.ofSeconds(cmd.timeoutSeconds()), line -> {
                        });
            }

            registerArtifacts(created.projectId(), created.id(), command, result);
            // v2.7：pytest 产物单独登记（RF 路径这两个文件不存在，register 会跳过）。
            register(created.projectId(), created.id(), "pytest_report_json",
                    command.outputDir().resolve("pytest-report.json"), 0);
            register(created.projectId(), created.id(), "junit_xml",
                    command.outputDir().resolve("junit.xml"), 0);

            int total = result.totalTests();
            int passed = result.success() ? total : 0;
            int failed = total - passed;
            runs.updateCounts(created.runId(), total, passed, failed, runDir.toString());

            if (result.success()) {
                results.write(created.projectId(), created.id(), "run-" + created.runId(),
                        "PASS", 0, null, 1);
                releaseReason = "finished";
                return runs.transition(created.runId(), RunState.FINISHED,
                        new RunService.TransitionContext(null, clock.instant(), null, null, null,
                                true, true));
            }
            results.write(created.projectId(), created.id(), "run-" + created.runId(),
                    "FAIL", 0, result.reason(), 1);
            FailureClass cls = classify(result.reason(), result.success());
            return runs.transition(created.runId(), RunState.FAILED,
                    new RunService.TransitionContext(null, clock.instant(), cls.name(),
                            result.reason(), "分片执行失败: " + result.reason(), true, true));
        } finally {
            // 成功、失败、异常、并发取消（transition 抛错）都走同一条释放路径。
            leases.releaseRun(created.runId(), releaseReason);
        }
    }

    /**
     * 本次运行的资源键：声明了设备范围就用「设备+端口」键（FR-v0.4-06），否则用 {@code run:<runId>} 自持。
     *
     * <p>未声明设备范围时不做互斥：执行工作区已按 run 隔离（EXEC-05），端口由预检保证，
     * 若再叠加环境/项目级独占键，会让 {@code MAX_PARALLEL} 内的并发运行互相等待直至 lease_timeout。
     */
    static List<String> resourceKeys(String runId, RunService.CreateRunCommand cmd) {
        List<String> keys = new ArrayList<>();
        if (cmd.deviceKeys() != null) {
            for (String raw : cmd.deviceKeys()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String key = raw.trim();
                if (key.length() > MAX_RESOURCE_KEY_LENGTH) {
                    throw new IllegalArgumentException(
                            "资源键超过 " + MAX_RESOURCE_KEY_LENGTH + " 字符: " + key);
                }
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add("run:" + runId);
        }
        return keys;
    }

    private RunRecord fail(RunRecord created, String reason, String message) {
        return fail(created, reason, classify(reason, false), message);
    }

    private RunRecord fail(RunRecord created, String reason, FailureClass cls, String message) {
        results.write(created.projectId(), created.id(), "run-" + created.runId(),
                "FAIL", 0, reason, 1);
        return runs.transition(created.runId(), RunState.FAILED,
                new RunService.TransitionContext(null, clock.instant(), cls.name(), reason,
                        message, false, false));
    }

    /**
     * 节点执行体（NODX-03 实装）：把任务经 {@link NodeChannel} 推到节点 agent，
     * 命令由**节点用同一套 PabotCommandBuilder / KRN** 按任务参数生成（命令等价）。
     *
     * <p>投递成功后 run 状态由节点侧执行链路正常迁移（本版节点与主控同库）；
     * 主控在投递前用同一参数 build 一条<b>参照命令</b>写入
     * {@code node-cmd-master.json}，节点回传 argv 写 {@code node-cmd-agent.json}，
     * {@code tools/kernel_cmd_diff.py} 归一化对比——判据「同参数命令 diff 为空」。
     *
     * <p>三种失败都不排队不重投（DEV-PLAN §5.3 口径）：节点不存在 / 未登记接入地址 /
     * 投递被拒或不可达，一律判 FAILED（node_dispatch_failed），绝不回落本机。
     * 投递成功后 agent 崩溃由 {@code NodeHeartbeatMonitor} 按心跳超时判失败。
     */
    private RunRecord executeOnNode(RunRecord created, RunService.CreateRunCommand cmd) {
        runs.transition(created.runId(), RunState.PREPARING, RunService.TransitionContext.plain());
        ExecutionNode node = cmd.nodeId() == null ? null
                : nodeRepo.find(cmd.nodeId()).orElse(null);
        if (node == null) {
            return fail(created, "node_dispatch_failed",
                    "执行节点不存在: " + cmd.nodeId() + "；不回落本机执行");
        }
        if (node.agentBaseUrl() == null || node.agentBaseUrl().isBlank()) {
            return fail(created, "node_dispatch_failed",
                    "节点 " + node.name() + " 未登记 agent 接入地址，无法投递；"
                            + "不排队不重投，不回落本机执行");
        }

        Path runDir = ExecutionPaths.resolve(props.getArtifactsRoot()).resolve(created.runId());
        // 参照命令：与节点同参数（命令等价 diff 的主控侧基线）
        int processes = Math.min(cmd.concurrency(), cmd.suitePaths().size());
        PabotCommandBuilder.PabotCommand reference = commands.build(
                new PabotCommandBuilder.ShardSpec(0, cmd.suitePaths(), processes,
                        cmd.includeTag(), "run-" + created.runId(), cmd.testNames()),
                props, runDir, ExecutionPaths.resolve(props.getWorkspaceRoot()));
        writeReferenceCommand(runDir, created.runId(), reference.argv());

        NodeTask task = new NodeTask(created.runId(), created.projectId(),
                cmd.suitePaths(), cmd.concurrency(), cmd.environmentId(), cmd.deviceKeys(),
                cmd.timeoutSeconds(), cmd.testTimeoutSeconds(), cmd.includeTag(),
                cmd.testNames(), null, null);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(task);
        } catch (Exception jsonError) {
            return fail(created, "node_dispatch_failed",
                    "任务序列化失败: " + jsonError.getMessage());
        }
        String signature = TaskSigner.sign(node.tokenHash(), payload);
        NodeChannel.Result result = nodeChannel.post(
                java.net.URI.create(node.agentBaseUrl() + "/api/agent/run"),
                payload, java.util.Map.of("X-Task-Signature", signature));
        if (result.ok()) {
            results.write(created.projectId(), created.id(), "run-" + created.runId(),
                    "DISPATCHED", 0, "node=" + node.name(), 1);
            return runs.findForRunner(created.projectId(), created.runId());
        }
        return fail(created, "node_dispatch_failed",
                "节点 " + node.name() + " 任务投递失败（" + result.kind() + " "
                        + result.statusCode() + " " + nullToEmpty(result.body())
                        + "）；不排队不重投，不回落本机执行");
    }

    /** 参照命令基线（best effort：写盘失败不影响投递主流程）。 */
    private void writeReferenceCommand(Path runDir, String runId, java.util.List<String> argv) {
        try {
            java.nio.file.Files.createDirectories(runDir);
            java.nio.file.Files.writeString(runDir.resolve("node-cmd-master.json"),
                    objectMapper.writeValueAsString(java.util.Map.of(
                            "runId", runId, "argv", argv)));
        } catch (Exception ignored) {
            // 基线写不进去只影响 diff 取证，不阻断执行
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 分类只依赖 reason 与产物结论；分片文本已由 {@link PabotOrchestrator} 落到 run.log。 */
    private FailureClass classify(String reason, boolean artifactOk) {
        return classifier.classify(new FailureClassifier.FailureInput(
                false, reason, null, null, null, null, artifactOk));
    }

    private void registerArtifacts(long projectId, long runDbId,
                                   PabotCommandBuilder.PabotCommand command,
                                   PabotOrchestrator.ShardResult result) {
        Path outputDir = command.outputDir();
        register(projectId, runDbId, "output_xml", result.outputXml(), 0);
        register(projectId, runDbId, "stdout_log", outputDir.resolve("stdout.log"), 0);
        register(projectId, runDbId, "stderr_log", outputDir.resolve("robot_stderr.out"), 0);
    }

    private void register(long projectId, long runDbId, String kind, Path path, int shardNo) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            artifacts.upsert(projectId, runDbId, kind, path.toString(), sha,
                    Files.size(path), shardNo, null);
        } catch (Exception ignored) {
            // 产物登记失败不影响主流程；日志文件仍在磁盘上。
        }
    }

    /**
     * EVID-01：冻结计划 SHA 的「同一规则」入口。
     * 服务端与请求方都用 {@link FrozenPlanService#computePlanSha256} 复算 / 校验 freezeSha256，
     * 规则本体只在 FrozenPlanService 定义一次，避免两处漂移。
     */
    public static String computePlanSha256(long projectId, Long sourceSelectionId,
                                           List<String> suitePaths, int roundNo,
                                           String payloadJson) {
        return FrozenPlanService.computePlanSha256(projectId, sourceSelectionId,
                suitePaths, roundNo, payloadJson);
    }

    /** EVID-01：未知 / 异常状态 RUN_ID 的只读找回，只查询、不新增 run、不重发命令。 */
    public RunRecord recoverUnknownRun(long projectId, String runId) {
        return runs.findForRunner(projectId, runId);
    }
}
