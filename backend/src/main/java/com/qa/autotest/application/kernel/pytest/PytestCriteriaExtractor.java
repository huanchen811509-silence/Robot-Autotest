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

    /** 扫产物目录：先取 json-report（主通道），再用 junit 补位（兼容兜底）。 */
    public static Extraction scan(Path artifactDir) {
        Extraction junit = scanJunit(artifactDir);
        Map<String, String> byNodeId = scanJsonReport(artifactDir);
        return new Extraction(junit.byCaseName(), byNodeId, junit.files(),
                byNodeId.isEmpty() ? 0 : 1, junit.unassigned());
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
        int files = 0;
        int unassigned = 0;
        for (Path xml : xmls) {
            try (InputStream in = Files.newInputStream(xml)) {
                int[] counter = new int[1];
                readOne(in, byName, counter);
                unassigned += counter[0];
                files++;
            } catch (Exception ignored) {
                // 单个文件坏掉不影响其它文件；判据缺了会在 criteria_missing 里体现。
            }
        }
        return new Extraction(Map.copyOf(byName), Map.of(), files, 0, unassigned);
    }

    private static void readOne(InputStream in, Map<String, String> byName, int[] unassigned)
            throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 禁用外部实体：artifact 目录里的文件可能来自被测环境，不能让它读取本机文件。
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        String currentCase = null;
        StringBuilder buffer = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("testcase".equals(local)) {
                        currentCase = attribute(reader, "name");
                    } else if ("system-out".equals(local)) {
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
                    if ("system-out".equals(local) && buffer != null) {
                        String text = buffer.toString();
                        if (!text.isBlank()) {
                            // 同名用例（参数化重复）后出现的覆盖前面：后一轮是最终事实。
                            byName.put(currentCase, text);
                        }
                        buffer = null;
                    } else if ("testcase".equals(local)) {
                        currentCase = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
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
