package com.qa.autotest.application.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.ExecutionKernel;
import com.qa.autotest.domain.kernel.KernelRequest;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import com.qa.autotest.domain.report.MetadataGate;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * pytest 内核（KRN-03）：主通道 pytest-json-report，兜底 junitxml。
 * 参数化用例保留 [param] 后缀独立成条，xfail / xpass / error 不折叠成 PASS/FAIL。
 *
 * <p>并行路线见 ADR-0007：走 pytest-xdist（{@code -n N}），不引入平台侧分片合并。
 * 并发为 1 时不加 {@code -n}，与 A2/A3 取证时的串行命令逐字等价。
 */
@Service
public class PytestKernel implements ExecutionKernel {

    public static final String NAME = "pytest";

    private final ObjectMapper mapper;
    private final com.qa.autotest.application.execution.ExecutionProperties props;

    public PytestKernel(ObjectMapper mapper,
                        com.qa.autotest.application.execution.ExecutionProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> command(KernelRequest request, Path artifactsRoot) {
        Path dir = artifactsRoot.resolve("kernel-pytest-" + request.shardNo());
        List<String> argv = new ArrayList<>();
        // v2.7 FR-01：执行链真实跑的是 venv 里的 pytest（与 pabot 同源），预览/实跑同一 argv。
        argv.add(pytestExecutable());
        argv.add("--json-report");
        argv.add("--json-report-file=" + dir.resolve("pytest-report.json"));
        argv.add("--junitxml=" + dir.resolve("junit.xml"));
        argv.add("-q");
        // v2.7 FR-03：run 级动态键经命令行透传（RUN_ID 是三方关联键）；
        // 静态键（QA_PROJECT/DUT/LINK 等）由套件 pytest.ini 携带，与 RF 的 `Metadata:` 设置同口径。
        if (request.runId() != null && !request.runId().isBlank()
                && !"PREVIEW".equals(request.runId())) {
            argv.add("--metadata");
            argv.add("RUN_ID");
            argv.add(request.runId());
        }
        // ADR-0007：并行统一由 xdist 承担（json-report 由 master 聚合），不用平台分片。
        if (request.processes() > 1) {
            argv.add("-n");
            argv.add(Integer.toString(request.processes()));
        }
        for (String test : request.testNames()) {
            argv.add(test);
        }
        argv.addAll(request.suitePaths());
        return List.copyOf(argv);
    }

    /** v2.7：pytest 可执行文件解析顺序 EXECUTION_PYTEST_BIN > venvBin/pytest > PATH 上的 pytest。 */
    private String pytestExecutable() {
        if (props.getPytestBin() != null && !props.getPytestBin().isBlank()) {
            return props.getPytestBin();
        }
        if (props.getVenvBin() == null || props.getVenvBin().isBlank()) {
            return "pytest";
        }
        return java.nio.file.Path.of(props.getVenvBin()).resolve("pytest").toString();
    }

    @Override
    public NormalizedRun normalize(Path artifactDir, String runId) {
        Path json = artifactDir.resolve("pytest-report.json");
        Path junit = artifactDir.resolve("junit.xml");
        NormalizedRun base;
        if (Files.isRegularFile(json)) {
            base = fromJson(json, runId);
        } else if (Files.isRegularFile(junit)) {
            base = fromJunit(junit, runId);
        } else {
            throw new IllegalArgumentException("pytest 产物缺少 pytest-report.json / junit.xml: "
                    + artifactDir);
        }
        return withRetries(base, artifactDir, runId);
    }

    /**
     * 把复测轮次合进首轮结论（v5.6.0 / FR-v5.6-24）。
     *
     * <p>报告入库是**重新解析产物**的独立链路，不读执行时的内存结果。若这里不认
     * {@code junit-retry-*.xml}，就会出现「执行页显示通过（含抖动）、报告页显示失败」——
     * 同一批产物两个结论，比不做复测更糟。
     *
     * <p>合并规则复用 {@link com.qa.autotest.domain.kernel.RetryMerge}，不在这里重写一遍。
     */
    private NormalizedRun withRetries(NormalizedRun base, Path dir, String runId) {
        java.util.TreeMap<Integer, List<Path>> byRound = new java.util.TreeMap<>();
        try (var stream = Files.newDirectoryStream(dir, "junit-retry-*.xml")) {
            for (Path file : stream) {
                int round = retryRoundOf(file.getFileName().toString());
                if (round > 0) {
                    byRound.computeIfAbsent(round, key -> new ArrayList<>()).add(file);
                }
            }
        } catch (java.io.IOException ex) {
            return base;
        }
        if (byRound.isEmpty()) {
            return base;
        }
        List<NormalizedRun> rounds = new ArrayList<>();
        rounds.add(base);
        for (Map.Entry<Integer, List<Path>> entry : byRound.entrySet()) {
            List<Path> files = new ArrayList<>(entry.getValue());
            files.sort(java.util.Comparator.comparing(Path::toString));
            List<NormalizedCaseResult> cases = new ArrayList<>();
            for (Path file : files) {
                cases.addAll(junitCases(file));
            }
            rounds.add(new NormalizedRun(NAME, runId, cases, base.metadata()));
        }
        return com.qa.autotest.domain.kernel.RetryMerge.merge(rounds);
    }

    /** {@code junit-retry-2.xml} / {@code junit-retry-2-0.xml} → 2；取不到返回 -1。 */
    static int retryRoundOf(String fileName) {
        String prefix = "junit-retry-";
        if (fileName == null || !fileName.startsWith(prefix)) {
            return -1;
        }
        int end = prefix.length();
        while (end < fileName.length() && Character.isDigit(fileName.charAt(end))) {
            end++;
        }
        if (end == prefix.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(fileName.substring(prefix.length(), end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private NormalizedRun fromJson(Path path, String runId) {
        try (InputStream in = Files.newInputStream(path)) {
            JsonNode root = mapper.readTree(in);
            List<NormalizedCaseResult> cases = new ArrayList<>();
            for (JsonNode test : root.path("tests")) {
                String nodeId = test.path("nodeid").asText("unknown");
                String outcome = test.path("outcome").asText("error");
                double duration = test.path("duration").asDouble(0.0);
                // v3.9.0 / FR-v3.9-02：命令从 stage 文本里取（约定行 `$ <命令>`），
                // 取不到就显式 NONE。json-report 的 keywords 只有标记名、没有标记参数，
                // 所以 @pytest.mark.commandline 也只能靠 stdout 里的约定行落地。
                com.qa.autotest.application.report.CaseCommandExtractor.Extracted command =
                        com.qa.autotest.application.report.CaseCommandExtractor
                                .fromPytestStdout(stageText(test));
                cases.add(new NormalizedCaseResult(nodeId, nodeId,
                        CaseStatus.fromPytest(outcome), duration, outcome,
                        command.text(), command.source(), command.exitCode()));
            }
            // N3：pytest-metadata 由 --metadata / conftest 写入 json-report。
            // json-report 1.5.0 的 metadata 落在 **environment** 键（v2.7 实测），
            // 旧版落 metadata 键——两个位置都兜住。只保留十项门禁键，其余（Python/Platform 等）不污染 run_metadata。
            JsonNode env = root.path("environment");
            JsonNode source = env.isObject() && env.size() > 0 ? env : root.path("metadata");
            Map<String, String> metadata = canonicalMetadata(source);
            return new NormalizedRun(NAME, runId, cases, withRunId(metadata, runId));
        } catch (Exception e) {
            throw new IllegalStateException("pytest json-report 解析失败: " + path, e);
        }
    }

    /**
     * 把一个用例各阶段（setup / call / teardown）的 stdout、stderr、longrepr 拼成一段文本。
     *
     * <p>json-report 把输出放在 stage 对象里，而不是用例顶层 —— 只读顶层会一条命令都取不到。
     * 顺序固定 setup → call → teardown，与 pytest 真实执行顺序一致；
     * longrepr 放在最后，因为它通常是失败回溯（里面也可能出现 `$ cmd` 的上下文行）。
     */
    private static String stageText(JsonNode test) {
        StringBuilder sb = new StringBuilder();
        for (String stage : new String[] {"setup", "call", "teardown"}) {
            JsonNode node = test.path(stage);
            if (!node.isObject()) {
                continue;
            }
            for (String key : new String[] {"stdout", "stderr", "longrepr"}) {
                JsonNode value = node.path(key);
                if (value.isValueNode() && !value.isNull()) {
                    sb.append(value.asText()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private NormalizedRun fromJunit(Path path, String runId) {
        try (InputStream in = Files.newInputStream(path)) {
            // 一个流只能读一次：用例与 metadata 必须从同一份 DOM 取，重开流会读到半个文档。
            Document doc = parse(in);
            return new NormalizedRun(NAME, runId, casesOf(doc),
                    withRunId(canonicalMetadata(doc), runId));
        } catch (Exception e) {
            throw new IllegalStateException("junit.xml 解析失败: " + path, e);
        }
    }

    /** 复测轮次只有用例、没有自己的 metadata：metadata 一律取首轮那份。 */
    private static List<NormalizedCaseResult> junitCases(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return casesOf(parse(in));
        } catch (Exception e) {
            throw new IllegalStateException("junit.xml 解析失败: " + file, e);
        }
    }

    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(in);
    }

    private static List<NormalizedCaseResult> casesOf(Document doc) {
        NodeList nodes = doc.getElementsByTagName("testcase");
        List<NormalizedCaseResult> cases = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            String name = node.getAttribute("name");
            String classname = node.getAttribute("classname");
            String nodeId = classname.isEmpty() ? name : classname + "::" + name;
            CaseStatus status = CaseStatus.PASS;
            if (node.getElementsByTagName("failure").getLength() > 0) {
                status = CaseStatus.FAIL;
            } else if (node.getElementsByTagName("error").getLength() > 0) {
                status = CaseStatus.ERROR;
            } else if (node.getElementsByTagName("skipped").getLength() > 0) {
                status = CaseStatus.SKIP;
            }
            cases.add(new NormalizedCaseResult(nodeId, name, status,
                    parseDouble(node.getAttribute("time")), failureMessage(node, status)));
        }
        return cases;
    }

    /**
     * 取 junit 的失败/错误消息。
     *
     * <p>旧实现直接把状态名（{@code FAIL}）当消息塞进结果，报告里「失败消息」一列
     * 就只剩两个字，对外报告等于没有原因。这里按 junit 语义取 {@code message} 属性，
     * 缺属性时退回正文首个非空行，并截断到 600 字符避免整段 traceback 进报告。
     */
    private static String failureMessage(Element testCase, CaseStatus status) {
        if (status != CaseStatus.FAIL && status != CaseStatus.ERROR) {
            return null;
        }
        String tag = status == CaseStatus.FAIL ? "failure" : "error";
        NodeList hits = testCase.getElementsByTagName(tag);
        if (hits.getLength() == 0) {
            return status.name();
        }
        Element node = (Element) hits.item(0);
        String message = node.getAttribute("message");
        if (message == null || message.isBlank()) {
            String body = node.getTextContent();
            if (body != null) {
                for (String line : body.split("\\R")) {
                    if (!line.isBlank()) {
                        message = line.trim();
                        break;
                    }
                }
            }
        }
        if (message == null || message.isBlank()) {
            return status.name();
        }
        return message.length() > 600 ? message.substring(0, 600) : message;
    }

    private static double parseDouble(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0.0 : Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** json-report 的 metadata 节点：只收标量值，键名大小写不敏感。 */
    private static Map<String, String> canonicalMetadata(JsonNode node) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isValueNode() && !value.isNull()) {
                    raw.put(entry.getKey(), value.asText());
                }
            });
        }
        return canonical(raw);
    }

    /** junit.xml 的 <properties><property name value/>：pytest 侧同样可携带 metadata。 */
    private static Map<String, String> canonicalMetadata(Document doc) {
        Map<String, String> raw = new LinkedHashMap<>();
        NodeList properties = doc.getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            raw.put(property.getAttribute("name"), property.getAttribute("value"));
        }
        return canonical(raw);
    }

    /**
     * 键名统一大写，只保留十项门禁键。
     *
     * <p>刻意丢弃 pytest-metadata 自带的 Python / Platform / Packages 等键：
     * 它们既不能回答「谁、在哪台设备、什么版本」，还会把 run_metadata 撑大。
     */
    private static Map<String, String> canonical(Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String canonicalKey = key.trim().toUpperCase(Locale.ROOT);
            if (MetadataGate.REQUIRED.contains(canonicalKey)) {
                result.put(canonicalKey, value.trim());
            }
        });
        return result;
    }

    /** RUN_ID 是三方关联键，内核必须自己补齐，不能依赖调用方另外传一遍。 */
    private static Map<String, String> withRunId(Map<String, String> metadata, String runId) {
        if (runId == null || runId.isBlank() || metadata.containsKey("RUN_ID")) {
            return metadata;
        }
        Map<String, String> merged = new LinkedHashMap<>(metadata);
        merged.put("RUN_ID", runId);
        return merged;
    }
}
