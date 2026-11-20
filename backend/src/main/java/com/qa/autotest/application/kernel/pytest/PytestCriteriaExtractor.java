package com.qa.autotest.application.kernel.pytest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 从执行产物里提取「用例 → 判据原文」（v5.7.0 / FR-v5.7-12，复核 W57-50；v5.8.0 W58-13 改造）。
 *
 * <p><b>载体为什么换了</b>（v5.8.0 实测，别再改回只看 junit）：pytest 9.2 默认
 * {@code junit_logging} 不开，junit 的 {@code <testcase>} 里<b>根本没有</b>
 * {@code <system-out>}——4 份真实 run 的 {@code junit.xml} 里 {@code system-out} 段数全是 0，
 * 于是提取器恒取不到东西、{@code criteria_json} 至今 715 条全是 NULL。
 * 平台的<b>主通道</b> {@code pytest-report.json} 则按阶段记了 {@code stdout}
 * （{@code setup} / {@code call} / {@code teardown}），实测能拿到用例自己打印的判据块。
 * 因此本类<b>优先读 json-report</b>，junit 只作兼容兜底（老版本或没装 json-report 插件的环境）。
 *
 * <p><b>归属口径</b>：json-report 用 {@code nodeid}（唯一，含参数化后缀），junit 用
 * {@code testcase@name}（可能重名）。两种键都保留，由调用方按「先 nodeid 后 name」匹配。
 * testsuite 级的 {@code <system-out>} 是所有用例混在一起的输出，无法归属到具体用例，
 * 一律计入 {@code unassigned} 并<b>不猜</b>它属于谁。
 *
 * <p><b>容错</b>：任何文件解析失败只跳过该文件（返回已收集部分），不抛给调用方——
 * 判据采集失败不能把一次已经跑完的执行判成失败。
 */
public final class PytestCriteriaExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** json-report 的固定文件名，由 {@code PytestKernel} 的 {@code --json-report-file} 决定。 */
    static final String JSON_REPORT = "pytest-report.json";

    /** pytest 的输出阶段。三个都要读：夹具里的打印落在 setup、用例体在 call。 */
    private static final String[] STAGES = {"setup", "call", "teardown"};

    /**
     * @param byCaseName 用例名（junit {@code testcase@name}，含参数化后缀）→ 判据原文
     * @param byNodeId   nodeid（json-report {@code tests[].nodeid}）→ 判据原文，优先使用
     * @param files      实际解析成功的 junit 文件数
     * @param jsonFiles  实际解析成功的 json-report 文件数（0 或 1）
     * @param unassigned 无法归属到具体用例的 {@code system-out} 段数（仅用于如实报数）
     */
    public record Extraction(Map<String, String> byCaseName, Map<String, String> byNodeId,
                             int files, int jsonFiles, int unassigned) {

        public static Extraction empty() {
            return new Extraction(Map.of(), Map.of(), 0, 0, 0);
        }
    }

    private PytestCriteriaExtractor() {
    }

    /**
     * 扫产物目录：junit 与 json-report 都读，各自产出两类索引。
     *
     * <p><b>v5.24.0 / FR-EHT-03</b>：junit 现在也产出 {@code classname::name} 形式的
     * {@code byNodeId}，与 {@code test_result.nodeid} 同一口径。此前只有 json-report 提供
     * {@code byNodeId} 且是斜杠 nodeid，和归一后的点分 nodeid 永远对不上，只能靠
     * 「取 nodeId 的 :: 后段再查 byCaseName」的兜底——一旦命名有差异就整条判据丢失。
     */
    public static Extraction scan(Path artifactDir) {
        Extraction junit = scanJunit(artifactDir);
        Map<String, String> jsonNode = scanJsonReport(artifactDir);
        Map<String, String> nodeIndex = new LinkedHashMap<>(jsonNode);
        // junit 的点分 key 与 json 的斜杠 key 互不冲突，合并后按 nodeid 一次命中。
        nodeIndex.putAll(junit.byNodeId());
        return new Extraction(junit.byCaseName(), Map.copyOf(nodeIndex), junit.files(),
                jsonNode.isEmpty() ? 0 : 1, junit.unassigned());
    }

    /**
     * 从 {@code pytest-report.json} 的 {@code tests[].{setup,call,teardown}.stdout} 取原文。
     *
     * <p>目录不存在、文件缺失、JSON 坏掉、没有 {@code tests} 数组——一律返回空表，
     * 由调用方退回 junit；这里不区分「没装插件」和「装了但没输出」，因为对上层是同一件事。
     */
    static Map<String, String> scanJsonReport(Path artifactDir) {
        if (artifactDir == null || !Files.isDirectory(artifactDir)) {
            return Map.of();
        }
        Path report = artifactDir.resolve(JSON_REPORT);
        if (!Files.isRegularFile(report)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(report)) {
            JsonNode root = JSON.readTree(in);
            JsonNode tests = root.get("tests");
            if (tests == null || !tests.isArray()) {
                return Map.of();
            }
            Map<String, String> byNodeId = new LinkedHashMap<>();
            for (JsonNode test : tests) {
                String nodeId = text(test, "nodeid");
                if (nodeId == null || nodeId.isBlank()) {
                    continue;
                }
                String stdout = stageOutput(test);
                if (stdout != null) {
                    // 同名 nodeid 后出现的覆盖前面：与 junit 分支同一口径（后一轮是最终事实）。
                    byNodeId.put(nodeId, stdout);
                }
            }
            return Map.copyOf(byNodeId);
        } catch (Exception e) {
            // json-report 坏掉不影响执行事实，退回 junit 兜底。
            return Map.of();
        }
    }

    /** 按 setup → call → teardown 顺序拼接 stdout；全空返回 null（不产生空条目）。 */
    private static String stageOutput(JsonNode test) {
        StringBuilder buffer = new StringBuilder();
        for (String stage : STAGES) {
            JsonNode node = test.get(stage);
            if (node == null || !node.isObject()) {
                continue;
            }
            JsonNode out = node.get("stdout");
            if (out == null || out.isNull()) {
                continue;
            }
            String value = out.asText();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(value);
        }
        return buffer.length() == 0 ? null : buffer.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 扫目录下所有 {@code *.xml}（不递归）。目录不存在或为空时返回空结果，不抛异常。 */
    static Extraction scanJunit(Path artifactDir) {
        if (artifactDir == null || !Files.isDirectory(artifactDir)) {
            return Extraction.empty();
        }
        List<Path> xmls;
        try (Stream<Path> stream = Files.list(artifactDir)) {
            xmls = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .sorted().toList();
        } catch (Exception e) {
            return Extraction.empty();
        }
        Map<String, String> byName = new LinkedHashMap<>();
        Map<String, String> byNodeId = new LinkedHashMap<>();
        int files = 0;
        int unassigned = 0;
        for (Path xml : xmls) {
            try (InputStream in = Files.newInputStream(xml)) {
                int[] counter = new int[1];
                readOne(in, byName, byNodeId, counter);
                unassigned += counter[0];
                files++;
            } catch (Exception ignored) {
                // 单个文件坏掉不影响其它文件；判据缺了会在 criteria_missing 里体现。
            }
        }
        return new Extraction(Map.copyOf(byName), Map.copyOf(byNodeId), files, 0, unassigned);
    }

    private static void readOne(InputStream in, Map<String, String> byName,
                                Map<String, String> byNodeId, int[] unassigned)
            throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 禁用外部实体：artifact 目录里的文件可能来自被测环境，不能让它读取本机文件。
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        String currentCase = null;
        String currentClass = null;
        StringBuilder buffer = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("testcase".equals(local)) {
                        currentCase = attribute(reader, "name");
                        currentClass = attribute(reader, "classname");
                    } else if ("system-out".equals(local) || "system-err".equals(local)) {
                        // v5.24.0 / FR-EHT-01：两个标签都要读。框架的 logging.StreamHandler()
                        // 默认写 stderr，pytest xunit2 把 capstderr 放 <system-err>；只读
                        // system-out 会让 9/10 个用例的 [明细] 全部漏掉（只剩 print 到 stdout
                        // 的 IM-FUN-014 有值）——与 PytestStepExtractor.capturedOf 同一口径。
                        if (currentCase == null) {
                            unassigned[0]++;
                        } else {
                            buffer = new StringBuilder();
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (buffer != null) {
                        buffer.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if (("system-out".equals(local) || "system-err".equals(local)) && buffer != null) {
                        // 同名用例（参数化/多阶段）按块追加并去重：pytest 会在 setup/call
                        // 各写一份内容逐字相同的捕获块，直接 put 会让后者覆盖前者、丢一半明细。
                        String text = buffer.toString();
                        appendCase(byName, currentCase, text);
                        // classname::name 与 test_result.nodeid 同口径，供按 nodeid 直接命中。
                        String caseKey = currentClass == null || currentClass.isEmpty()
                                ? currentCase : currentClass + "::" + currentCase;
                        appendCase(byNodeId, caseKey, text);
                        buffer = null;
                    } else if ("testcase".equals(local)) {
                        currentCase = null;
                        currentClass = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    /**
     * 把一个捕获块合并到用例已有文本，按行去重。
     *
     * <p>pytest xunit2 下同一用例会在 setup / call 阶段各写一份 system-out/system-err，
     * 两份内容高度重复（实测 3 条步骤被写了 6 遍）。按行去重既保留不同步骤，
     * 又不会把「条数翻倍」当成用例写了两遍。与 {@code PytestStepExtractor} 的去重口径一致。
     */
    private static void appendCase(Map<String, String> byName, String caseName, String text) {
        if (caseName == null || text == null || text.isBlank()) {
            return;
        }
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        String prev = byName.get(caseName);
        if (prev != null && !prev.isBlank()) {
            for (String line : prev.split("\\R")) {
                lines.add(line);
            }
        }
        for (String line : text.split("\\R")) {
            lines.add(line);
        }
        byName.put(caseName, String.join("\n", lines).trim());
    }

    /** 判定标记，与框架 {@code step.check()} 的 {@code [判定] 标签：结论} 对齐。 */
    private static final String VERDICT_TEXT = "[判定]";

    /** {@code CRITERIA_JSON} 哨兵：用例显式声明的结构化判据块。 */
    private static final String SENTINEL = "CRITERIA_JSON";

    /**
     * 把捕获文本转成 {@code CriteriaEvaluation} 能吃的结构化 JSON（v5.24.0 / FR-EHT-03）。
     *
     * <p>两类来源，优先级从高到低：
     * <ol>
     *   <li>用例显式打印的 {@code CRITERIA_JSON {...}} 哨兵块——原样取出，不改一字；</li>
     *   <li>框架 {@code step.check()} 打的 {@code [判定] 步骤N：通过 / 不通过（原因）}
     *       ——按行合成 {@code {"criteria":[{metric,actual,passed}]}}。这是把用例
     *       <b>自己已经给出的结论</b>转成结构化，不新增判断、不编造。</li>
     * </ol>
     *
     * <p>Plan-only 只有 {@code [明细]}、没有 {@code [判定]}，故返回 {@code null}（未声明判据）；
     * 报告如实显示 MISSING/未判定，不伪造通过。调用方拿到 null 时应保留原文供证据层展示。
     */
    public static String toStructured(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String explicit = extractSentinelJson(text);
        if (explicit != null) {
            return explicit;
        }
        java.util.List<java.util.Map<String, Object>> criteria = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            int at = line.indexOf(VERDICT_TEXT);
            if (at < 0) {
                continue;
            }
            String body = line.substring(at + VERDICT_TEXT.length()).trim();
            if (body.isEmpty()) {
                continue;
            }
            boolean failed = body.startsWith("不通过") || body.contains("：不通过");
            boolean passed = !failed && (body.startsWith("通过") || body.contains("：通过")
                    || body.contains("均有有效回显"));
            if (!passed && !failed) {
                continue;
            }
            int colon = body.indexOf('：');
            String metric = colon > 0 ? body.substring(0, colon).trim() : body;
            String actual = colon > 0 ? body.substring(colon + 1).trim() : body;
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("metric", metric);
            item.put("actual", actual);
            item.put("passed", passed);
            criteria.add(item);
        }
        if (criteria.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(java.util.Map.of("criteria", criteria));
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 {@code CRITERIA_JSON} 之后的第一个完整 JSON 对象（按括号配对，跳过字符串内括号）。 */
    private static String extractSentinelJson(String text) {
        int idx = text.indexOf(SENTINEL);
        if (idx < 0) {
            return null;
        }
        int start = text.indexOf('{', idx);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }
}
