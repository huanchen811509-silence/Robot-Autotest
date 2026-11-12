package com.qa.autotest.application.kernel.pytest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import com.qa.autotest.domain.report.MetadataGate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * pytest 结果归一（FR-v5.1-08，验收 A19–A21 / GAP-09 修复）。
 *
 * <p>把 junit 的 &lt;testcase&gt; 还原成「用例库编号」的结论行，并落 metadata 等价物。
 *
 * <p><b>为什么按「文件名 + 函数名」匹配而不是解析 nodeid</b>：junit 的 classname 是
 * **点分**（{@code tests.nic.base.test_im_fun_020}），json-report 的 nodeid 是**斜杠 +
 * :: **（{@code tests/nic/base/test_im_fun_020.py::test_im_fun_020}），两者不同源。
 * 从点分串反推路径必须猜「哪些点原本是斜杠、`.py` 后缀在哪」——参数化与同名目录一出现就错。
 * 这里改用 {@code <文件末段>::<函数名>} 作匹配键：两侧都能稳定取到，且实测 61 个用例
 * 文件函数名互不重复（唯一性由 W51-11 的重复绑定检测守住）。
 *
 * <p><b>metadata 等价物（项目硬规矩「output.xml 必带 6 项」在 pytest 上的落法）</b>：
 * pytest 不产生 output.xml，因此由平台写 sidecar {@code run_metadata.json}，
 * 与 junit 并列归档，{@code check_output_metadata.py} 双形态校验。缺项时**不报错**，
 * 只把 {@code exportable} 降级为 false —— 与 RF 报告同一语义。
 */
@Service
public class PytestResultNormalizer {

    /** sidecar 文件名：metadata 等价物载体。 */
    public static final String SIDECAR_NAME = "run_metadata.json";

    private final CaseScriptBindingRepository bindings;
    private final com.qa.autotest.application.repository.TestCaseRepository cases;
    private final ObjectMapper mapper;

    public PytestResultNormalizer(CaseScriptBindingRepository bindings,
                                  com.qa.autotest.application.repository.TestCaseRepository cases,
                                  ObjectMapper mapper) {
        this.bindings = bindings;
        this.cases = cases;
        this.mapper = mapper;
    }

    /** 单条用例的映射情况。 */
    public record CaseMapping(String nodeId, String caseCode, String scriptPath, boolean matched) {
    }

    /** 归一结果。 */
    public record NormalizeResult(NormalizedRun run,
                                  List<CaseMapping> mappings,
                                  int unbound,
                                  boolean allSkipped,
                                  Path sidecar) {

        /** 全部跳过（fake 模式）时通过率「不适用」，不能显示成 0% 或 100%。 */
        public boolean passRateApplicable() {
            return !allSkipped;
        }
    }

    /**
     * 归一。
     *
     * @param junitFiles  一个或多个 junit 文件（分批执行时是 junit-0.xml、junit-1.xml ...）
     * @param runMetadata run 级 metadata（RUN_ID / QA_PROJECT / DUT / LINK / SW_BUILD 等）
     */
    public NormalizeResult normalize(long projectId, String runId, List<Path> junitFiles,
                                     Map<String, String> runMetadata, Path runDir) {
        if (junitFiles == null || junitFiles.isEmpty()) {
            throw new IllegalArgumentException("pytest 归一缺少 junit 文件");
        }
        Map<String, CaseScriptBinding> index = bindingIndex(projectId);
        Map<Long, String> codeById = this.cases.idToCode(projectId);
        List<NormalizedCaseResult> cases = new ArrayList<>();
        List<CaseMapping> mappings = new ArrayList<>();
        int unbound = 0;
        int skipped = 0;
        for (Path file : junitFiles) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("junit 文件不存在: " + file);
            }
            for (JunitCase item : parseJunit(file)) {
                String matchKey = fileStem(item.classname()) + "::" + baseName(item.name());
                CaseScriptBinding binding = index.get(matchKey);
                String nodeId = item.classname().isEmpty() ? item.name()
                        : item.classname() + "::" + item.name();
                if (binding == null) {
                    unbound++;
                    mappings.add(new CaseMapping(nodeId, null, null, false));
                    cases.add(new NormalizedCaseResult(nodeId, nodeId, item.status(),
                            item.seconds(), item.message()));
                } else {
                    String code = codeById.get(binding.caseId());
                    String display = code == null || code.isBlank()
                            ? nodeId : code;
                    mappings.add(new CaseMapping(nodeId, code, binding.scriptPath(), true));
                    // name 放用例库编号：报告页显示 IM-NET-131 而不是 test_im_net_131[param]。
                    cases.add(new NormalizedCaseResult(nodeId, display, item.status(),
                            item.seconds(), item.message()));
                }
                if (item.status() == CaseStatus.SKIP || item.status() == CaseStatus.XFAIL) {
                    skipped++;
                }
            }
        }
        Map<String, String> metadata = canonical(runMetadata, runId);
        Path sidecar = writeSidecar(runDir, runId, metadata);
        NormalizedRun run = new NormalizedRun("pytest", runId, cases, metadata);
        return new NormalizeResult(run, mappings, unbound, skipped == cases.size() && !cases.isEmpty(),
                sidecar);
    }

    /**
     * 绑定索引：`<文件末段>::<函数名>` → 用例编号。
     *
     * <p>供报告入库层把「用例名 = nodeid」换成库编号（GAP-09）。与内部
     * {@link #bindingIndex(long)} 用同一套匹配键，避免「归一用一套、入库用另一套」的分裂。
     */
    public Map<String, String> caseCodeIndex(long projectId) {
        Map<Long, String> codeById = this.cases.idToCode(projectId);
        Map<String, String> index = new LinkedHashMap<>();
        for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
            String stem = fileStemOfPath(binding.scriptPath());
            String func = functionOf(binding.nodeId());
            if (stem.isEmpty() || func.isEmpty()) {
                continue;
            }
            String code = codeById.get(binding.caseId());
            if (code != null && !code.isBlank()) {
                index.putIfAbsent(stem + "::" + func, code);
            }
        }
        return index;
    }

    /**
     * 匹配键：junit 的 {@code classname} + {@code name} → {@code <文件末段>::<函数名>}。
     * 参数化实例会自动剥掉 {@code [...]} 后缀，与绑定表的基础 nodeid 对齐。
     */
    public static String matchKey(String classname, String testName) {
        return fileStem(classname) + "::" + baseName(testName);
    }

    /**
     * 绑定索引：`<文件末段>::<函数名>` → 绑定。
     *
     * <p>一个 key 命中多条时保留第一条并计数，由 W51-11 的重复绑定检测把冲突暴露出来——
     * 这里静默取第一条是有意的：执行期不能因为数据脏就整轮失败。
     */
    private Map<String, CaseScriptBinding> bindingIndex(long projectId) {
        Map<String, CaseScriptBinding> index = new LinkedHashMap<>();
        for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
            String stem = fileStemOfPath(binding.scriptPath());
            String func = functionOf(binding.nodeId());
            if (stem.isEmpty() || func.isEmpty()) {
                continue;
            }
            index.putIfAbsent(stem + "::" + func, binding);
        }
        return index;
    }

    /** 绑定侧：从 scriptPath 取文件名去后缀。 */
    private static String fileStemOfPath(String scriptPath) {
        if (scriptPath == null || scriptPath.isBlank()) {
            return "";
        }
        String name = scriptPath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.endsWith(".py") ? name.substring(0, name.length() - 3) : name;
    }

    /** 绑定侧：nodeId `a/b/test_x.py::test_x` → `test_x`。 */
    private static String functionOf(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "";
        }
        int idx = nodeId.lastIndexOf("::");
        return idx < 0 ? "" : baseName(nodeId.substring(idx + 2));
    }

    /** junit 侧：classname `tests.nic.base.test_im_fun_020` → `test_im_fun_020`。 */
    private static String fileStem(String classname) {
        if (classname == null || classname.isBlank()) {
            return "";
        }
        String value = classname.replace('/', '.').replace('\\', '.');
        // 必须先剥 .py 再按点分割：`tests.nic.base.test_x.py` 按点取末段会得到 "py"。
        if (value.endsWith(".py")) {
            value = value.substring(0, value.length() - 3);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    /** 去掉参数化后缀：`test_x[a-b]` → `test_x`。 */
    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        int bracket = name.indexOf('[');
        return (bracket < 0 ? name : name.substring(0, bracket)).trim();
    }

    private record JunitCase(String classname, String name, CaseStatus status,
                             double seconds, String message) {
    }

    private static List<JunitCase> parseJunit(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 与既有 PytestKernel.fromJunit 同样的安全设置：禁 DOCTYPE、禁外部实体。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(in);
            NodeList nodes = doc.getElementsByTagName("testcase");
            List<JunitCase> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                CaseStatus status = CaseStatus.PASS;
                String message = null;
                if (node.getElementsByTagName("failure").getLength() > 0) {
                    status = CaseStatus.FAIL;
                    message = textOfFirst(node, "failure");
                } else if (node.getElementsByTagName("error").getLength() > 0) {
                    status = CaseStatus.ERROR;
                    message = textOfFirst(node, "error");
                } else if (node.getElementsByTagName("skipped").getLength() > 0) {
                    status = CaseStatus.SKIP;
                    message = textOfFirst(node, "skipped");
                }
                result.add(new JunitCase(node.getAttribute("classname"), node.getAttribute("name"),
                        status, parseSeconds(node.getAttribute("time")),
                        message == null ? null : truncate(message, 2000)));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("junit 解析失败: " + file + " — " + e.getMessage(), e);
        }
    }

    private static String textOfFirst(Element node, String tag) {
        NodeList list = node.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        Element first = (Element) list.item(0);
        String message = first.getAttribute("message");
        String text = first.getTextContent();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return text == null || text.isBlank() ? null : text;
    }

    private static double parseSeconds(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0.0 : Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断）";
    }

    /** 键名大写 + 只留门禁键，与 {@code PytestKernel.canonical} 同一口径。 */
    private static Map<String, String> canonical(Map<String, String> raw, String runId) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    return;
                }
                String canonicalKey = key.trim().toUpperCase(Locale.ROOT);
                if (MetadataGate.REQUIRED.contains(canonicalKey)) {
                    result.put(canonicalKey, value.trim());
                }
            });
        }
        if (runId != null && !runId.isBlank()) {
            result.putIfAbsent("RUN_ID", runId);
        }
        return result;
    }

    /** sidecar：metadata 等价物。写失败不影响结果归一（产物可以少一份，结论不能丢）。 */
    private Path writeSidecar(Path runDir, String runId, Map<String, String> metadata) {
        Path sidecar = runDir.resolve(SIDECAR_NAME);
        try {
            Files.createDirectories(runDir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("run_id", runId);
            payload.put("kernel", "pytest");
            payload.put("metadata", metadata);
            payload.put("required", List.copyOf(MetadataGate.REQUIRED));
            payload.put("missing", missing(metadata));
            Files.writeString(sidecar,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // sidecar 写不进不影响结论；门禁会在「产物缺失」时判红。
        }
        return sidecar;
    }

    private static List<String> missing(Map<String, String> metadata) {
        List<String> missing = new ArrayList<>();
        for (String key : MetadataGate.REQUIRED) {
            if (!metadata.containsKey(key) || metadata.get(key).isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }
}
