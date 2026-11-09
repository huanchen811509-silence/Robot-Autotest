package com.qa.autotest.application.report;

import com.qa.autotest.application.execution.ExecutionPaths;
import com.qa.autotest.application.execution.RunArtifact;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.CiTriggerRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.ci.CiTriggerRecord;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.MetadataOverlay;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告入库编排（RPT-01 + RPT-07）。
 *
 * <p>流程：定位 output.xml → StAX 解析 → metadata 十项门禁 → <b>同一事务</b>内写
 * run.exportable / run.metadata_missing / 计数 / test_result / keyword_result。
 * 门禁结论在入库时即固化，导出环节不再重新判定（DEV-PLAN §3）。
 */
@Service
public class ReportIngestService {

    private final RunRepository runs;
    private final RunArtifactRepository artifacts;
    private final ReportRepository reports;
    private final AccessControlService access;
    private final OutputXmlParser parser;
    private final CiTriggerRepository ciTriggers;
    private final RunMetadataRepository runMetadata;
    /** v2.7 FR-02：pytest run 的逐用例解析路由到 PytestKernel.normalize；旧构造为 null → 仅 RF。 */
    private final com.qa.autotest.application.kernel.PytestKernel pytestKernel;
    /** v3.9.0 FR-v3.9-03：pytest 用例日志落盘；旧构造为 null → 不生成日志（RF 不受影响）。 */
    private final PytestLogPersister pytestLogs;

    public ReportIngestService(RunRepository runs,
                               RunArtifactRepository artifacts,
                               ReportRepository reports,
                               AccessControlService access,
                               OutputXmlParser parser) {
        this(runs, artifacts, reports, access, parser, null, null, null, null);
    }

    public ReportIngestService(RunRepository runs,
                               RunArtifactRepository artifacts,
                               ReportRepository reports,
                               AccessControlService access,
                               OutputXmlParser parser,
                               CiTriggerRepository ciTriggers) {
        this(runs, artifacts, reports, access, parser, ciTriggers, null, null, null);
    }

    @Autowired
    public ReportIngestService(RunRepository runs,
                               RunArtifactRepository artifacts,
                               ReportRepository reports,
                               AccessControlService access,
                               OutputXmlParser parser,
                               CiTriggerRepository ciTriggers,
                               RunMetadataRepository runMetadata,
                               com.qa.autotest.application.kernel.PytestKernel pytestKernel,
                               PytestLogPersister pytestLogs) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.reports = reports;
        this.access = access;
        this.parser = parser;
        this.ciTriggers = ciTriggers;
        this.runMetadata = runMetadata;
        this.pytestKernel = pytestKernel;
        this.pytestLogs = pytestLogs;
    }

    /**
     * 解析并入库。
     *
     * @param projectId     项目
     * @param runId         RUN_ID 字符串
     * @param outputXmlPath 可选显式路径；为空时按 run_artifact → artifacts_dir 顺序推导
     */
    @Transactional
    public ReportRecord parseAndIngest(long projectId, String runId, String outputXmlPath) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return ingest(projectId, runId, outputXmlPath);
    }

    /**
     * 节点回传专用入库（NODX-03）：agent 无用户会话，权限在回传端点以节点令牌
     * 认证替代（{@code NodeResultService} 已验 {@code X-Node-Token} 并核对
     * {@code run.node_id} 归属）；其余逻辑与 {@link #parseAndIngest} 完全一致。
     */
    @Transactional
    public ReportRecord parseAndIngestForAgent(long projectId, String runId,
                                               String outputXmlPath) {
        return ingest(projectId, runId, outputXmlPath);
    }

    private ReportRecord ingest(long projectId, String runId, String outputXmlPath) {
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }

        RunRecord run = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运行不存在: " + runId));
        String kernel = runs.findKernel(projectId, runId).orElse("robot");
        ParsedReport parsed = "pytest".equals(kernel)
                ? parsePytest(projectId, run)
                : parser.parse(resolveOutputXml(projectId, run, outputXmlPath));
        parsed = mergePlanMetadata(projectId, runId, parsed);
        // 已补录过的 run 重新解析时，门禁必须按「解析值 + 补录值」计算，
        // 否则人已补齐的十项会被一次重析冲回不可导出（HRD-02）。
        GateResult gate = MetadataGate.check(effectiveMetadata(projectId, runId, parsed.metadata()));
        Instant startedAt = parseStartedAt(parsed.metadataValue("STARTED_AT"));

        reports.replace(projectId, run.id(), parsed, gate, startedAt);
        return reports.find(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告入库后读取失败: " + runId));
    }

    /**
     * pytest run 逐用例解析（v2.7 FR-02）：产物目录定位 → {@code PytestKernel.normalize}
     * → 六态映射（{@link KernelStatusMapper}）→ 组装报告域套件树。
     * metadata 直接取归一结果（十项门禁与 RF 共用同一口径）。
     */
    private ParsedReport parsePytest(long projectId, RunRecord run) {
        if (pytestKernel == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "pytest 内核未装配，无法解析 pytest run 的报告");
        }
        Path artifactDir = resolvePytestArtifactDir(projectId, run);
        // 先摊平日志：区间要随 test_result 一起入库，抽屉才能按偏移切片。
        PytestLogPersister.Spans spans = pytestLogs == null
                ? new PytestLogPersister.Spans(java.util.Map.of())
                : pytestLogs.persist(artifactDir);
        com.qa.autotest.domain.kernel.NormalizedRun normalized =
                pytestKernel.normalize(artifactDir, run.runId());
        java.util.List<com.qa.autotest.domain.report.CaseNode> cases = new java.util.ArrayList<>();
        int pass = 0;
        int fail = 0;
        int skip = 0;
        for (com.qa.autotest.domain.kernel.NormalizedCaseResult item : normalized.cases()) {
            String status = KernelStatusMapper.toReportStatus(item.status());
            switch (status) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                default -> skip++;
            }
            String message = "FAIL".equals(status)
                    ? item.message() == null ? "pytest: " + item.status() : item.message()
                    : null;
            // v3.9.0 / FR-v3.9-02：命令三列随用例一起入库，报告页才有「下发了什么」可看。
            cases.add(new com.qa.autotest.domain.report.CaseNode(item.name(), status,
                    Math.round(item.durationSeconds() * 1000.0), message,
                    java.util.List.of(), spans.offsetOf(item.nodeId()), spans.spanOf(item.nodeId()),
                    item.commandText(), item.commandSource(), item.commandExitCode()));
        }
        String rootStatus = fail > 0 ? "FAIL" : pass + skip == cases.size() ? "PASS" : "UNKNOWN";
        com.qa.autotest.domain.report.SuiteNode root =
                new com.qa.autotest.domain.report.SuiteNode("run-" + run.runId(), rootStatus,
                        normalized.metadata(), java.util.List.of(), cases);
        return new ParsedReport(root, normalized.metadata(), pass, fail, skip);
    }

    /** pytest 产物目录：run_artifact(kind=pytest_report_json) → artifactsDir/kernel-pytest-0。 */
    private Path resolvePytestArtifactDir(long projectId, RunRecord run) {
        for (RunArtifact artifact : artifacts.list(projectId, run.id())) {
            if (!"pytest_report_json".equals(artifact.kind())
                    || artifact.path() == null || artifact.path().isBlank()) {
                continue;
            }
            Path candidate = ExecutionPaths.resolve(artifact.path());
            if (Files.isRegularFile(candidate)) {
                return candidate.getParent();
            }
        }
        if (run.artifactsDir() != null && !run.artifactsDir().isBlank()) {
            Path fallback = ExecutionPaths.resolve(run.artifactsDir())
                    .resolve("kernel-pytest-0");
            if (Files.isRegularFile(fallback.resolve("pytest-report.json"))
                    || Files.isRegularFile(fallback.resolve("junit.xml"))) {
                return fallback;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                "未找到 pytest 产物（pytest-report.json / junit.xml）：请确认 run 已真实执行完成");
    }

    /** 解析值 + 补录值；无补录仓储（单测）时直接用解析值。 */
    private java.util.Map<String, String> effectiveMetadata(long projectId, String runId,
                                                           java.util.Map<String, String> parsed) {
        if (runMetadata == null) {
            return parsed;
        }
        return MetadataOverlay.merge(parsed, runMetadata.findAll(projectId, runId));
    }

    /**
     * 计划触发元数据注入（PLN-01）。
     *
     * <p>平台链路无法在 pabot 命令行里塞 metadata（RUN_ID 之外的键由用例侧写），
     * 因此计划触发时把 {@code plan_id / trigger_type} 落到 {@code run} 表，
     * 入库时在这里合并进 metadata，保证 RPT-01 解析结果含 {@code PLAN_ID}、{@code TRIGGER_TYPE}。
     * 手工运行（无 plan_id）不注入，避免污染既有十项门禁与历史报告。
     */
    private ParsedReport mergePlanMetadata(long projectId, String runId, ParsedReport parsed) {
        RunOrigin origin = runs.findOrigin(projectId, runId).orElse(null);
        if (origin == null || origin.planId() == null) {
            return parsed;
        }
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(parsed.metadata());
        merged.putIfAbsent("PLAN_ID", Long.toString(origin.planId()));
        merged.putIfAbsent("TRIGGER_TYPE", origin.triggerType());
        if (RunOrigin.CI.equals(origin.triggerType()) && ciTriggers != null) {
            CiTriggerRecord trigger = ciTriggers.findByRunId(projectId, runId).orElse(null);
            if (trigger != null) {
                // 40 字符上限：COMMIT_SHA 全量正好 40，BUILD / CI_REF 超长截断（DEV-PLAN §4.1）。
                merged.putIfAbsent("COMMIT_SHA", fit(trigger.commitSha()));
                merged.putIfAbsent("BUILD", fit(trigger.build()));
                merged.putIfAbsent("CI_REF", fit(trigger.ref()));
            }
        }
        return new ParsedReport(parsed.root(), merged, parsed.passCount(), parsed.failCount(),
                parsed.skipCount());
    }

    /** metadata 单值上限 40 字符；null 原样返回，交给门禁按缺失处理。 */
    static String fit(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MetadataGate.MAX_VALUE_LENGTH
                ? value : value.substring(0, MetadataGate.MAX_VALUE_LENGTH);
    }

    /**
     * 定位 output.xml。
     *
     * <p>优先顺序（按代码现状，不硬编码新目录）：
     * <ol>
     *   <li>调用方显式传参 {@code outputXmlPath}；</li>
     *   <li>{@code run_artifact} 中 kind=output_xml 且文件存在的记录（v0.4 执行链路已登记）；</li>
     *   <li>{@code run.artifacts_dir} 下的 v0.4 约定位置 {@code shard-0/output.xml} 或 {@code output.xml}；</li>
     *   <li>都不存在时要求调用方显式传路径，不再猜平台历史目录。</li>
     * </ol>
     */
    private Path resolveOutputXml(long projectId, RunRecord run, String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path explicit = ExecutionPaths.resolve(explicitPath);
            requireReadable(explicit, explicitPath);
            return explicit;
        }
        for (RunArtifact artifact : artifacts.list(projectId, run.id())) {
            if (!"output_xml".equals(artifact.kind()) || artifact.path() == null || artifact.path().isBlank()) {
                continue;
            }
            Path candidate = ExecutionPaths.resolve(artifact.path());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        if (run.artifactsDir() != null && !run.artifactsDir().isBlank()) {
            Path runDir = ExecutionPaths.resolve(run.artifactsDir());
            Path shard = runDir.resolve("shard-0").resolve("output.xml");
            if (Files.isRegularFile(shard)) {
                return shard;
            }
            Path direct = runDir.resolve("output.xml");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                "未找到 output.xml：run_artifact 未登记且产物目录回退不存在，请通过 outputXmlPath 显式传入");
    }

    private static void requireReadable(Path path, String raw) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "output.xml 不存在或不可读: " + raw);
        }
    }

    /**
     * metadata.STARTED_AT 解析：支持 ISO Instant（Z 结尾）、带时区偏移与本地时间三种写法。
     * 解析失败时返回 null，由仓库层保留 run.started_at 原值，不让格式问题阻断入库。
     */
    static Instant parseStartedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它格式
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
