package com.qa.autotest.application.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * pytest 用例日志落盘（FR-v3.9-03 ④：修掉「pytest 日志抽屉为空」）。
 *
 * <p>RF 的日志正文在 output.xml 里，按偏移切片读即可；pytest 的 json-report 把输出放在
 * 每个用例的 setup/call/teardown 段里，**没有**全局日志文件。报告页的抽屉按
 * {@code log_offset/log_span} 切片，pytest 一直是 -1/null，于是抽屉空白——这正是
 * PRD Q2-7 记的那条缺陷。
 *
 * <p>做法：入库时把各用例的 stage 文本按**全局顺序**摊平成一份 JSONL
 * （{@code case-log.jsonl}，一行一条），同时算出每个用例的 {@code [start, end)} 区间。
 * 抽屉照旧按偏移切片，只是数据源换成这份文件；两份产物同目录，随 run 一起保留/归档。
 *
 * <p>为什么用 JSONL 而不是「纯文本 + 分隔符」：日志正文里什么字符都有（tab、竖线、
 * 控制字符），自定义分隔符迟早撞车；一行一个 JSON 对象不会歧义，也便于以后加字段。
 */
@Component
public class PytestLogPersister {

    /** 日志文件名：与 pytest-report.json 同目录，跟随产物归档。 */
    public static final String LOG_FILE = "case-log.jsonl";

    private static final String[] STAGES = {"setup", "call", "teardown"};
    private static final String[] CHANNELS = {"stdout", "stderr", "longrepr"};

    private final ObjectMapper mapper;

    public PytestLogPersister(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 某用例的日志区间（全局序号，左闭右开）。 */
    public record Spans(Map<String, long[]> byNodeId) {

        /** 用例缺失时返回 -1 / null，与 RF 侧「无消息」口径一致。 */
        public long offsetOf(String nodeId) {
            long[] span = byNodeId.get(nodeId);
            return span == null ? -1L : span[0];
        }

        public String spanOf(String nodeId) {
            long[] span = byNodeId.get(nodeId);
            return span == null ? null : span[0] + ":" + span[1];
        }
    }

    /**
     * 生成登录日志并返回各用例区间。
     *
     * @param artifactDir 含 pytest-report.json 的目录
     * @return 区间表；报告文件缺失或不可解析时返回空表（不抛：日志缺失不该阻断入库）
     */
    public Spans persist(Path artifactDir) {
        if (artifactDir == null) {
            return new Spans(Map.of());
        }
        Path report = artifactDir.resolve("pytest-report.json");
        if (!Files.isRegularFile(report)) {
            // v5.24.0 / FR-EHT-01：自研用例库装不了 json-report，真实产物只有 junit.xml。
            // 旧代码在这里直接返回空 Spans，导致 log_offset/log_span 恒为 -1/null、日志抽屉全空。
            // 改从 junit 的 <testcase> 捕获文本（system-out/err）生成日志，key 用 classname::name，
            // 与 test_result.nodeId 同一口径（json-report 的斜杠 nodeid 本就和它对不上）。
            return fromJunit(artifactDir);
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(report));
            JsonNode tests = root.path("tests");
            if (!tests.isArray()) {
                return new Spans(Map.of());
            }
            Map<String, long[]> spans = new LinkedHashMap<>();
            List<String> rows = new ArrayList<>();
            long seq = 0;
            for (JsonNode test : tests) {
                String nodeId = test.path("nodeid").asText("");
                long start = seq;
                for (String stage : STAGES) {
                    JsonNode node = test.path(stage);
                    if (!node.isObject()) {
                        continue;
                    }
                    for (String channel : CHANNELS) {
                        JsonNode value = node.path(channel);
                        if (!value.isValueNode() || value.isNull()) {
                            continue;
                        }
                        for (String line : value.asText().split("\\R")) {
                            rows.add(row(seq++, "longrepr".equals(channel) ? "ERROR" : "INFO",
                                    stage, line));
                        }
                    }
                }
                // 空日志的用例不登记区间：抽屉会显示「暂无日志」而不是一段假区间。
                if (seq > start) {
                    spans.put(nodeId, new long[] {start, seq});
                }
            }
            Path target = artifactDir.resolve(LOG_FILE);
            Files.createDirectories(artifactDir);
            Files.write(target, rows, StandardCharsets.UTF_8);
            return new Spans(spans);
        } catch (IOException e) {
            throw new UncheckedIOException("写 pytest 用例日志失败: " + artifactDir, e);
        }
    }

    /**
     * 无 json-report 时从 junit 生成日志与区间（v5.24.0 / FR-EHT-01）。
     *
     * <p>junit 的 {@code <testcase classname name>} 天然是按用例分组的边界；
     * 捕获文本在 {@code <system-out>}（print）与 {@code <system-err>}（logging 默认写 stderr）
     * 两处，都要读并按行去重——pytest 会按 setup/call 阶段各写一份，内容高度重复。
     *
     * <p>key 用 {@code classname::name}，与 {@code PytestResultNormalizer} 生成的
     * {@code test_result.nodeid} 同口径；否则区间永远匹配不上，抽屉还是空。
     */
    private Spans fromJunit(Path artifactDir) {
        List<Path> xmls;
        try (var stream = Files.list(artifactDir)) {
            xmls = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("junit") && n.endsWith(".xml");
            }).sorted().toList();
        } catch (IOException e) {
            return new Spans(Map.of());
        }
        if (xmls.isEmpty()) {
            return new Spans(Map.of());
        }
        Map<String, long[]> spans = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        long seq = 0;
        for (Path xml : xmls) {
            seq = appendJunit(xml, spans, rows, seq);
        }
        if (rows.isEmpty()) {
            return new Spans(Map.of());
        }
        try {
            Files.write(artifactDir.resolve(LOG_FILE), rows, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写 pytest 用例日志失败: " + artifactDir, e);
        }
        return new Spans(spans);
    }

    /** 解析一个 junit 文件，追加日志行并登记用例区间；返回新的全局序号。 */
    private long appendJunit(Path xml, Map<String, long[]> spans, List<String> rows, long seq) {
        try (InputStream in = Files.newInputStream(xml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 与 PytestStepExtractor 同一套安全设置：禁 DOCTYPE、禁外部实体。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(in);
            NodeList nodes = doc.getElementsByTagName("testcase");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element tc = (Element) nodes.item(i);
                String classname = tc.getAttribute("classname");
                String name = tc.getAttribute("name");
                String nodeId = classname.isEmpty() ? name : classname + "::" + name;
                String text = captured(tc);
                if (text.isBlank()) {
                    continue;
                }
                long start = seq;
                for (String line : text.split("\\R")) {
                    rows.add(row(seq++, "INFO", "call", line));
                }
                if (seq > start) {
                    spans.put(nodeId, new long[] {start, seq});
                }
            }
        } catch (Exception ignored) {
            // 单个文件坏掉不影响其它文件；缺日志的用例抽屉显示「暂无日志」。
        }
        return seq;
    }

    /** testcase 下的 system-out + system-err 文本，按行去重（跨阶段重复）。 */
    private static String captured(Element testcase) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String tag : new String[] {"system-out", "system-err"}) {
            NodeList list = testcase.getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) {
                String text = list.item(i).getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
                for (String line : text.split("\\R")) {
                    if (seen.add(line)) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(line);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 单行 JSON。手写而不是用 mapper：字段固定且要保证不转义中文以外无差异。 */
    private String row(long seq, String level, String keyword, String message) {
        try {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", seq);
            item.put("level", level);
            item.put("keyword", keyword);
            item.put("message", message);
            return mapper.writeValueAsString(item);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化日志行失败", e);
        }
    }
}
