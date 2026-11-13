package com.qa.autotest.api.kernel;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
    private final RunArtifactRepository artifacts;
    private final RunMetadataRepository runMetadata;
    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final AccessControlService access;

    public PytestNicRunController(PytestNicRunService runs,
                                  ProjectSettingRepository settings,
                                  RunRepository runRepo,
                                  RunArtifactRepository artifacts,
                                  RunMetadataRepository runMetadata,
                                  CaseScriptBindingRepository bindings,
                                  TestCaseRepository cases,
                                  AccessControlService access) {
        this.runs = runs;
        this.settings = settings;
        this.runRepo = runRepo;
        this.artifacts = artifacts;
        this.runMetadata = runMetadata;
        this.bindings = bindings;
        this.cases = cases;
        this.access = access;
    }

    /** 执行请求；nodeIds 为空表示整库全量收集。{@code confirmReal} 是 real 模式的显式授权位。 */
    public record FakeRunRequest(String runId, String mode, List<String> nodeIds,
                                 List<String> hostKeys, Integer timeoutSeconds,
                                 Map<String, String> metadata, Boolean confirmReal) {

        /** 老调用方 6 参形态（只跑 fake），保持源码兼容。 */
        public FakeRunRequest(String runId, String mode, List<String> nodeIds,
                              List<String> hostKeys, Integer timeoutSeconds,
                              Map<String, String> metadata) {
            this(runId, mode, nodeIds, hostKeys, timeoutSeconds, metadata, null);
        }
    }

    /**
     * 发起一次全链路执行：渲染 → 探契约 → 构命令 → 起进程 → junit 归一。
     *
     * <p>{@code /runs/fake} 强制 Plan-only；{@code /runs} 支持 real，但必须带
     * {@code confirmReal=true}，且目标用例全部通过 ADR-0013 安全闸（LX/L2 一律拒跑）。
     * 产物目录按模式分流到 {@code eht-fake-runs} / {@code eht-real-runs}，
     * 响应里带 runDir，供取证脚本原样归档；平台不写用例库真源。
     */
    @PostMapping("/runs/fake")
    public ApiResponse<Map<String, Object>> fake(@PathVariable long projectId,
                                                  @RequestBody(required = false) FakeRunRequest request) {
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        // 显式要 real 却打到 fake 端点，必须报错而不是悄悄降级成 fake——
        // 静默降级会让人以为跑的是真机，实际一条命令都没发出去。
        if (req.mode() != null && !req.mode().isBlank()
                && !"fake".equals(req.mode().trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "此端点只跑 fake；real 请用 POST /runs 并带 confirmReal=true");
        }
        return execute(projectId, new FakeRunRequest(req.runId(), "fake", req.nodeIds(),
                req.hostKeys(), req.timeoutSeconds(), req.metadata(), Boolean.FALSE));
    }

    /** 统一执行入口；real 模式需要显式授权位 + 安全闸双通过。 */
    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> run(@PathVariable long projectId,
                                                 @RequestBody(required = false) FakeRunRequest request) {
        FakeRunRequest req = request == null
                ? new FakeRunRequest(null, null, null, null, null, null) : request;
        return execute(projectId, req);
    }

    private ApiResponse<Map<String, Object>> execute(long projectId, FakeRunRequest req) {
        access.requireProjectAccess(projectId);
        String mode = req.mode() == null || req.mode().isBlank()
                ? "fake" : req.mode().trim().toLowerCase(Locale.ROOT);
        if (!"fake".equals(mode) && !"real".equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的执行模式：" + mode + "（仅 fake / real）");
        }
        List<String> nodeIds = req.nodeIds() == null ? List.of() : req.nodeIds();
        if ("real".equals(mode)) {
            // real 会真的 SSH 到 DUT 执行命令。两个条件缺一不可：
            // ① 调用方显式声明已获授权（不给默认值放行）；② 目标用例安全级可执行。
            if (!Boolean.TRUE.equals(req.confirmReal())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "real 模式需要显式确认：请求体加 confirmReal=true（真实执行会登录 DUT）");
            }
            List<String> blocked = blockedCases(projectId, nodeIds);
            if (!blocked.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "目标用例安全级未判定或属高危，按 ADR-0013 拒跑（先人工补判）："
                                + String.join(", ", blocked));
            }
            // fail-closed：点名了却映射不到用例，就等于「不知道它是什么风险」。
            // 放行的话，任何拼错/未绑定的 nodeid 都能绕过安全闸直接登录 DUT。
            List<String> unmapped = unmappedNodes(projectId, nodeIds);
            if (!unmapped.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "以下 nodeId 未绑定用例，无法判定安全级，按 fail-closed 拒跑："
                                + String.join(", ", unmapped));
            }
        }
        String runId = req.runId() == null || req.runId().isBlank()
                ? "EHT-" + mode.toUpperCase(Locale.ROOT) + "-" + System.currentTimeMillis()
                : req.runId().trim();
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        if (PytestProjectSettings.repoPath(config) == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未配置 pytest.repo_path");
        }
        Path runDir = Path.of(System.getProperty("java.io.tmpdir"),
                "fake".equals(mode) ? "eht-fake-runs" : "eht-real-runs", runId);
        // 重复 runId 直接 409：底层唯一键冲突会抛 500，看上去像服务出错，
        // 实际是「这个名字已经用过」，调用方重试时应带新 runId。
        if (runRepo.findByRunId(projectId, runId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "runId 已存在：" + runId + "（换一个 runId 重试）");
        }
        int seconds = req.timeoutSeconds() == null ? 1800 : Math.max(30, req.timeoutSeconds());

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
                        nodeIds,
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

        List<String> notes = new ArrayList<>();
        // 请求里的 metadata 必须落 run_metadata：报告解析读的是 run_artifact 侧的 junit，
        // 请求参数不会自己长进报告里；不落库的结果就是十项门禁全红、导出 400。
        // 走 HRD-02 补录机制（读取时 MetadataOverlay 叠加），不伪造 junit 内容。
        if (req.metadata() != null && !req.metadata().isEmpty()) {
            try {
                runMetadata.save(projectId, runId, req.metadata(), List.of(),
                        TenantContext.userId());
                notes.add("metadata 已落 run_metadata：" + req.metadata().size() + " 项");
            } catch (RuntimeException ex) {
                notes.add("metadata 补录失败：" + ex.getMessage());
            }
        }
        // 报告链路只认 run_artifact：不登记产物，执行完就只剩一个临时目录，
        // 「生成报告」按钮永远报「找不到 pytest 产物」。这里把 junit 登记成
        // 平台认的两种 kind，并保证 runDir/junit.xml 存在（多批时合并）。
        notes.addAll(registerArtifacts(projectId, runId, runDir, result.junitFiles()));
        Map<String, Object> payload = view(result, runId, runDir, mode);
        payload.put("artifactNotes", notes);
        return ApiResponse.ok(payload);
    }

    /** 登记 junit 产物；多批执行先合并成 runDir/junit.xml，报告解析才有单一入口。 */
    private List<String> registerArtifacts(long projectId, String runId, Path runDir,
                                           List<Path> junitFiles) {
        List<String> notes = new ArrayList<>();
        Optional<RunRecord> record = runRepo.findByRunId(projectId, runId);
        if (record.isEmpty()) {
            notes.add("run 记录缺失，跳过产物登记");
            return notes;
        }
        long runDbId = record.get().id();
        Path merged = runDir.resolve("junit.xml");
        try {
            if (junitFiles != null && !junitFiles.isEmpty() && !Files.isRegularFile(merged)) {
                mergeJunit(junitFiles, merged);
                notes.add("合并 " + junitFiles.size() + " 个批次 junit → junit.xml");
            }
        } catch (Exception ex) {
            notes.add("合并 junit 失败：" + ex.getMessage());
        }
        for (Path file : junitFiles == null ? List.<Path>of() : junitFiles) {
            register(projectId, runDbId, "junit_xml", file, notes);
        }
        if (Files.isRegularFile(merged)) {
            // 报告服务按 kind=pytest_report_json 定位产物目录，再在目录里找 junit.xml。
            register(projectId, runDbId, "pytest_report_json", merged, notes);
        } else {
            notes.add("未找到可登记的 junit 产物");
        }
        return notes;
    }

    private void register(long projectId, long runDbId, String kind, Path file, List<String> notes) {
        if (file == null || !Files.isRegularFile(file)) {
            notes.add(kind + " 不存在：" + file);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String sha = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            artifacts.upsert(projectId, runDbId, kind, file.toString(), sha, bytes.length, 0, null);
            notes.add(kind + " 已登记 " + file.getFileName());
        } catch (Exception ex) {
            notes.add(kind + " 登记失败：" + ex.getMessage());
        }
    }

    /** 多批 junit 合并：保留每个 testsuite，计数重新累加。单批直接拷贝，不做 XML 往返。 */
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
                        Element imported = (Element) merged.importNode(child, true);
                        root.appendChild(imported);
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

    /**
     * ADR-0013 安全闸：把 nodeId 映射到用例，LX（未判定）/ L2（高危）一律拒跑。
     *
     * <p>nodeIds 为空表示整库，此时按项目已绑定用例全量核对——不能因为「没点名」
     * 就绕过闸门。未绑定脚本的用例不在这里判，它根本不会被本次 run 执行。
     */
    private List<String> blockedCases(long projectId, List<String> nodeIds) {
        Set<String> blocked = new LinkedHashSet<>();
        List<CaseScriptBinding> targets = new ArrayList<>();
        if (nodeIds.isEmpty()) {
            targets.addAll(bindings.listByProject(projectId, "pytest"));
        } else {
            for (String raw : nodeIds) {
                String base = raw == null ? "" : raw.trim();
                int bracket = base.indexOf('[');
                if (bracket > 0) {
                    base = base.substring(0, bracket);
                }
                Optional<CaseScriptBinding> found = bindings.findByNodeId(projectId, "pytest", base);
                if (found.isEmpty()) {
                    continue;
                }
                targets.add(found.get());
            }
        }
        for (CaseScriptBinding binding : targets) {
            Optional<TestCase> testCase = cases.find(projectId, binding.caseId());
            if (testCase.isEmpty()) {
                continue;
            }
            SafetyLevel level = SafetyLevel.from(testCase.get().safetyLevel());
            if (level.blocksExecution()) {
                blocked.add(testCase.get().caseCode().trim() + "(" + level.name() + ")");
            }
        }
        return new ArrayList<>(blocked);
    }

    /**
     * 点名但未绑定到用例的 nodeId 列表。
     *
     * <p>real 模式下这份清单必须为空：绑定关系是安全级的唯一入口，找不到绑定就
     * 无从判断 L0/L1/L2，只能按最保守的方式拒跑（fail-closed），不能默认放行。
     * nodeIds 为空（整库执行）时返回空——此时按项目已绑定用例全量核对。
     */
    private List<String> unmappedNodes(long projectId, List<String> nodeIds) {
        List<String> unmapped = new ArrayList<>();
        for (String raw : nodeIds) {
            String base = raw == null ? "" : raw.trim();
            int bracket = base.indexOf('[');
            if (bracket > 0) {
                base = base.substring(0, bracket);
            }
            if (base.isEmpty()) {
                continue;
            }
            if (bindings.findByNodeId(projectId, "pytest", base).isEmpty()) {
                unmapped.add(base);
            }
        }
        return unmapped;
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
