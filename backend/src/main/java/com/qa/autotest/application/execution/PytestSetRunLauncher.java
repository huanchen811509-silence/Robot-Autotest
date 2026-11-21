package com.qa.autotest.application.execution;

import com.qa.autotest.application.kernel.pytest.PytestEvidenceService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.report.PytestCommandEnricher;
import com.qa.autotest.application.report.ReportIngestService;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.FailureClass;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用例集 → pytest 内核的一次执行（FR-UIE-09）。
 *
 * <p><b>为什么单独一个 Launcher</b>：{@code PytestNicRunController} 里的 prepare /
 * runPrepared 是接口编排（HTTP 视图、进度桥、@PreDestroy 线程池），应用层直接依赖
 * 控制器会把 api 层拖进领域链。这里只保留「落 run 记录 → 执行 → 计数 → 产物 → 入库 →
 * 证据」六步，与控制器<b>同一套服务、同一套安全闸</b>，差别仅在不发进度事件、不拼 HTTP 视图。
 *
 * <p><b>同步执行</b>：用例集入口一次最多几十条，同步更简单——调用方拿到回执时报告已经入库，
 * 不会出现「执行成功、报告空白」。条目量大的场景仍应走 pytest 工作台的异步入口。
 */
@Service
public class PytestSetRunLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(PytestSetRunLauncher.class);

    private final PytestNicRunService runs;
    private final ProjectSettingRepository settings;
    private final RunRepository runRepo;
    private final RunArtifactRepository artifacts;
    private final RunMetadataRepository runMetadata;
    private final ReportIngestService reportIngest;
    private final PytestEvidenceService evidence;
    private final PytestCommandEnricher commandEnricher;

    public PytestSetRunLauncher(PytestNicRunService runs,
                                ProjectSettingRepository settings,
                                RunRepository runRepo,
                                RunArtifactRepository artifacts,
                                RunMetadataRepository runMetadata,
                                ReportIngestService reportIngest,
                                PytestEvidenceService evidence,
                                PytestCommandEnricher commandEnricher) {
        this.runs = runs;
        this.settings = settings;
        this.runRepo = runRepo;
        this.artifacts = artifacts;
        this.runMetadata = runMetadata;
        this.reportIngest = reportIngest;
        this.evidence = evidence;
        this.commandEnricher = commandEnricher;
    }

    /** 一次用例集执行的入参。{@code nodeIds} 已由调用方按编号解析完毕。 */
    public record LaunchCommand(long projectId,
                                Long testSetId,
                                String setCode,
                                List<String> nodeIds,
                                String mode,
                                List<String> hostKeys,
                                Map<String, String> metadata,
                                String runRequestId,
                                Integer timeoutSeconds) {
    }

    /**
     * 执行并返回落库后的 run 记录。失败会先落 FAILED 状态再抛业务异常——执行事实不能被吞掉。
     */
    public RunRecord launch(LaunchCommand cmd) {
        Map<String, String> config = PytestProjectSettings.load(settings, cmd.projectId());
        if (PytestProjectSettings.repoPath(config) == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "未配置 pytest.repo_path，无法执行 pytest 用例集");
        }
        String mode = cmd.mode() == null || cmd.mode().isBlank()
                ? "fake" : cmd.mode().trim().toLowerCase(Locale.ROOT);
        if (!"fake".equals(mode) && !"real".equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的执行模式：" + mode + "（仅 fake / real）");
        }
        String runId = "SET-" + sanitize(cmd.setCode()) + "-" + System.currentTimeMillis();
        if (runRepo.findByRunId(cmd.projectId(), runId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "runId 已存在：" + runId);
        }
        Path runDir = Path.of(System.getProperty("java.io.tmpdir"),
                "fake".equals(mode) ? "eht-fake-runs" : "eht-real-runs", runId);
        int seconds = cmd.timeoutSeconds() == null ? 1800 : Math.max(30, cmd.timeoutSeconds());
        Map<String, String> metadata = new java.util.LinkedHashMap<>(
                cmd.metadata() == null ? Map.of() : cmd.metadata());

        Instant started = Instant.now();
        // 用例集入口没有元数据表单，平台已知的事实由这里补齐（RUN_ID / 开始时间 / 类别）：
        // 不补的话，任何一条从用例集发起的运行都必然「十项缺四」，连内部报告都带上
        // 「不可对外交付」——而这几项本来就不是用户该手填的。
        metadata.putIfAbsent("RUN_ID", runId);
        metadata.putIfAbsent("STARTED_AT", started.toString());
        metadata.putIfAbsent("CASE_KIND", "auto");
        if (cmd.setCode() != null && !cmd.setCode().isBlank()) {
            metadata.putIfAbsent("CASE_ID", cmd.setCode().trim());
        }
        // 先落一条 RUNNING 的 run：孤儿清扫器按 DB 活跃运行对账，不先登记会被 60s SIGTERM。
        runRepo.insert(new RunRecord(0L, runId, cmd.projectId(), cmd.testSetId(),
                        RunState.QUEUED, 0, 1, 1, 0, 0, 0, null, null, null,
                        runDir.toString(), started, null, null, null, null),
                runId, cmd.runRequestId(), TenantContext.userId());
        runRepo.updateState(runId, RunState.QUEUED, RunState.RUNNING,
                started, null, null, null, null);
        runRepo.updateKernel(runId, "pytest");

        Path commandRoot = com.qa.autotest.application.kernel.pytest.PytestNicKernelCommand
                .artifactRoot(PytestProjectSettings.repoPath(config), runId);

        PytestNicRunService.ExecuteResult result;
        try {
            result = runs.execute(new PytestNicRunService.ExecuteRequest(
                    cmd.projectId(), runId, cmd.nodeIds(),
                    cmd.hostKeys() == null ? List.of() : cmd.hostKeys(), mode,
                    runDir, Duration.ofSeconds(seconds), metadata, 0));
        } catch (RuntimeException ex) {
            runRepo.updateState(runId, RunState.RUNNING, RunState.FAILED, started, Instant.now(),
                    FailureClass.INFRA.name(), "pytest_launch_failed", ex.getMessage());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "pytest 执行启动失败：" + ex.getMessage());
        }

        NormalizedRun normalized = result.run();
        if (result.normalize() != null && commandEnricher != null) {
            var enriched = commandEnricher.enrich(commandRoot, result.normalize());
            if (enriched != null && enriched.run() != null) {
                normalized = enriched.run();
            }
        }
        int total = normalized == null ? 0 : normalized.cases().size();
        int failed = 0;
        int skipped = 0;
        if (normalized != null) {
            for (NormalizedCaseResult one : normalized.cases()) {
                if (one.status().isFailure()) {
                    failed++;
                } else if (one.status() != CaseStatus.PASS) {
                    skipped++;
                }
            }
        }
        int passed = Math.max(0, total - failed - skipped);
        // 三态一起写：只写前三列会留下 skipped_tests 为 NULL，界面按 total=p+f+s 校验时对不上。
        runRepo.updateCountsDetailed(runId, total, passed, failed, skipped,
                runDir.toString());
        boolean success = result.success();
        runRepo.updateState(runId, RunState.RUNNING, success ? RunState.FINISHED : RunState.FAILED,
                started, Instant.now(),
                success ? null : FailureClass.INFRA.name(), result.reason(), null);

        if (!metadata.isEmpty()) {
            try {
                runMetadata.save(cmd.projectId(), runId, metadata, List.of(),
                        TenantContext.userId());
            } catch (RuntimeException ex) {
                LOG.warn("用例集执行 {} metadata 落库失败：{}", runId, ex.getMessage());
            }
        }
        registerArtifacts(cmd.projectId(), runId, runDir, result.junitFiles());
        try {
            reportIngest.parseAndIngest(cmd.projectId(), runId, null);
        } catch (RuntimeException ex) {
            LOG.warn("用例集执行 {} 报告入库失败，可手工 parse 重试", runId, ex);
        }
        try {
            evidence.extract(cmd.projectId(), runId, true);
        } catch (RuntimeException ex) {
            LOG.warn("用例集执行 {} 步骤证据抽取失败，可手工 evidence 重试", runId, ex);
        }
        if (!success) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "pytest 执行未成功（" + result.reason() + "），runId=" + runId + "，详见执行报告");
        }
        return runRepo.findByRunId(cmd.projectId(), runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "run 记录缺失：" + runId));
    }

    private String sanitize(String setCode) {
        if (setCode == null || setCode.isBlank()) {
            return "SET";
        }
        return setCode.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void registerArtifacts(long projectId, String runId, Path runDir,
                                   List<Path> junitFiles) {
        var record = runRepo.findByRunId(projectId, runId);
        if (record.isEmpty()) {
            return;
        }
        long runDbId = record.get().id();
        Path merged = runDir.resolve("junit.xml");
        try {
            if (junitFiles != null && !junitFiles.isEmpty() && !Files.isRegularFile(merged)) {
                mergeJunit(junitFiles, merged);
            }
        } catch (Exception ex) {
            LOG.warn("用例集执行 {} 合并 junit 失败：{}", runId, ex.getMessage());
        }
        for (Path file : junitFiles == null ? List.<Path>of() : junitFiles) {
            register(projectId, runDbId, "junit_xml", file);
        }
        if (Files.isRegularFile(merged)) {
            register(projectId, runDbId, "pytest_report_json", merged);
        }
    }

    private void register(long projectId, long runDbId, String kind, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String sha = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            artifacts.upsert(projectId, runDbId, kind, file.toString(), sha, bytes.length, 0, null);
        } catch (Exception ex) {
            LOG.warn("产物登记失败 {}：{}", file, ex.getMessage());
        }
    }

    /** 多批 junit 合并：保留每个 testsuite，计数重新累加。单批直接拷贝。 */
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
                        root.appendChild(merged.importNode(child, true));
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
}
