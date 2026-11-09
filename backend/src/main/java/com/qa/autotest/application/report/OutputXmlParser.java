package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.CaseNode;
import com.qa.autotest.domain.report.KeywordNode;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.SuiteNode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * output.xml 解析器（RPT-01）。
 *
 * <p>用 StAX 事件流解析，不把整文件读成 DOM：一个 1,000 用例规模的 output.xml 可能数十 MB，
 * DOM 会成倍吃内存。破损 XML 统一抛 {@link IllegalArgumentException}，由全局异常处理器
 * 翻译成 400 + 可读说明，不会以 500 裸栈暴露给前端。
 *
 * <p>metadata 必须<b>递归</b>收集：RF 7.5 只把部分 metadata 写在根套件上，pabot 合并后
 * 真值分散在各子套件（DEV-PLAN §2.1 的实测教训）。本类同时兼容旧格式
 * {@code <metadata><item name="..." value="..."/></metadata>}。
 */
@Service
public class OutputXmlParser {

    /**
     * 解析文件。文件不存在或内容破损均抛可读异常。
     */
    public ParsedReport parse(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("output.xml 路径为空");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("output.xml 不存在: " + file);
        }
        try (InputStream input = Files.newInputStream(file)) {
            return parse(input, file.toString());
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取 output.xml 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解析输入流（由调用方负责语义上的生命周期，本方法会关闭流）。
     */
    public ParsedReport parse(InputStream input) {
        return parse(input, "output.xml");
    }

    private ParsedReport parse(InputStream input, String source) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try (InputStream stream = input) {
            XMLStreamReader reader = factory.createXMLStreamReader(stream);
            try {
                return read(reader, source);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ex) {
            throw new IllegalArgumentException("output.xml 解析失败（" + source + "）：" + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取 output.xml 失败（" + source + "）：" + ex.getMessage(), ex);
        }
    }

    private ParsedReport read(XMLStreamReader reader, String source) throws XMLStreamException {
        Deque<SuiteBuilder> suites = new ArrayDeque<>();
        Deque<KeywordBuilder> keywords = new ArrayDeque<>();
        Map<String, String> rootMetadata = new LinkedHashMap<>();
        SuiteBuilder root = null;
        CaseBuilder currentCase = null;
        boolean oldMetadataBlock = false;
        StringBuilder message = null;
        String messageLevel = null;
        // RF 7.5 的 <meta name="X">值</meta> 把值放在元素文本里，必须等到 END_ELEMENT 才能落库；
        // 也有 <meta name="X" value="Y"/> 的写法（既有夹具与部分版本），两种都要认。
        String metaName = null;
        String metaAttrValue = null;
        StringBuilder metaText = null;
        Map<String, String> metaTarget = null;
        // RPT-02：<msg> 的全局序号（文档顺序，0 基）。入库只存偏移，正文留在 output.xml。
        long messageSeq = 0L;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "suite" -> {
                        SuiteBuilder suite = new SuiteBuilder(attribute(reader, "name"));
                        if (suites.isEmpty()) {
                            if (root == null) {
                                root = suite;
                            }
                        } else {
                            suites.peek().suites.add(suite);
                        }
                        suites.push(suite);
                        currentCase = null;
                    }
                    case "metadata" -> oldMetadataBlock = true;
                    case "item" -> {
                        if (oldMetadataBlock && !suites.isEmpty()) {
                            putMetadata(suites.peek(), attribute(reader, "name"), attribute(reader, "value"));
                        }
                    }
                    case "meta" -> {
                        metaName = attribute(reader, "name");
                        metaAttrValue = attribute(reader, "value");
                        metaText = new StringBuilder();
                        metaTarget = suites.isEmpty() ? rootMetadata : suites.peek().metadata;
                    }
                    case "test" -> {
                        currentCase = new CaseBuilder(attribute(reader, "name"));
                        if (!suites.isEmpty()) {
                            suites.peek().cases.add(currentCase);
                        }
                    }
                    case "kw" -> {
                        KeywordBuilder keyword = new KeywordBuilder(attribute(reader, "name"));
                        if (!keywords.isEmpty()) {
                            keywords.peek().children.add(keyword);
                        } else if (currentCase != null) {
                            currentCase.keywords.add(keyword);
                        }
                        keywords.push(keyword);
                    }
                    case "msg" -> {
                        message = new StringBuilder();
                        messageLevel = attribute(reader, "level");
                        long seq = messageSeq++;
                        // 关键字栈由内到外全部记账：父关键字 span 才能覆盖子孙关键字的日志。
                        for (KeywordBuilder keyword : keywords) {
                            keyword.recordMessage(seq);
                        }
                        if (currentCase != null) {
                            currentCase.recordMessage(seq);
                        }
                    }
                    case "status" -> {
                        String status = attribute(reader, "status");
                        long durationMs = elapsedMillis(attribute(reader, "elapsed"));
                        if (!keywords.isEmpty()) {
                            keywords.peek().status = status;
                            keywords.peek().durationMs = durationMs;
                        } else if (currentCase != null) {
                            currentCase.status = status;
                            currentCase.durationMs = durationMs;
                        } else if (!suites.isEmpty()) {
                            suites.peek().status = status;
                        }
                    }
                    default -> {
                        // robot / statistics / total 等节点对报告入库无意义，直接忽略。
                    }
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (message != null) {
                    message.append(reader.getText());
                }
                if (metaText != null) {
                    metaText.append(reader.getText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "meta" -> {
                        String value = metaAttrValue != null
                                ? metaAttrValue
                                : (metaText == null ? "" : metaText.toString());
                        putMetadata(metaTarget, metaName, value);
                        metaName = null;
                        metaAttrValue = null;
                        metaText = null;
                        metaTarget = null;
                    }
                    case "metadata" -> oldMetadataBlock = false;
                    case "msg" -> {
                        if (message != null) {
                            String text = message.toString().strip();
                            if (!keywords.isEmpty()) {
                                keywords.peek().messages.add(text);
                            }
                            if (currentCase != null && messageLevel != null
                                    && "FAIL".equals(messageLevel.trim().toUpperCase(Locale.ROOT))) {
                                currentCase.failureMessages.add(text);
                            }
                        }
                        message = null;
                        messageLevel = null;
                    }
                    case "kw" -> {
                        if (!keywords.isEmpty()) {
                            keywords.pop();
                        }
                    }
                    case "test" -> currentCase = null;
                    case "suite" -> {
                        if (!suites.isEmpty()) {
                            suites.pop();
                        }
                        currentCase = null;
                    }
                    default -> {
                        // 忽略
                    }
                }
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>(rootMetadata);
        if (root != null) {
            collectMetadata(root, metadata);
        }
        SuiteNode rootNode = root == null
                ? new SuiteNode("", "UNKNOWN", Map.of(), List.of(), List.of())
                : toSuiteNode(root);
        int[] counts = countCases(rootNode);
        return new ParsedReport(rootNode, metadata, counts[0], counts[1], counts[2]);
    }

    private static void collectMetadata(SuiteBuilder builder, Map<String, String> accumulator) {
        // 父级先写、子套件后写：同名键由子套件覆盖，缺失键由子套件补充。
        accumulator.putAll(builder.metadata);
        for (SuiteBuilder child : builder.suites) {
            collectMetadata(child, accumulator);
        }
    }

    private static SuiteNode toSuiteNode(SuiteBuilder builder) {
        List<SuiteNode> children = builder.suites.stream().map(OutputXmlParser::toSuiteNode).toList();
        List<CaseNode> cases = builder.cases.stream().map(OutputXmlParser::toCaseNode).toList();
        return new SuiteNode(builder.name, normalizeStatus(builder.status),
                builder.metadata, children, cases);
    }

    private static CaseNode toCaseNode(CaseBuilder builder) {
        List<KeywordNode> keywords = builder.keywords.stream().map(OutputXmlParser::toKeywordNode).toList();
        String failure = builder.failureMessages.isEmpty() ? null : String.join("\n", builder.failureMessages);
        // v3.9.0 / FR-v3.9-02：这里就把「下发命令」定下来，不留给报告页现算。
        // 现算会让同一份 output.xml 在不同版本页面显示不同结果，且无法进库审计。
        CaseCommandExtractor.Extracted command = CaseCommandExtractor.fromRobotKeywords(keywords);
        return new CaseNode(builder.name, normalizeStatus(builder.status),
                builder.durationMs, failure, keywords, builder.logOffset(), builder.logSpan(),
                command.text(), command.source(), command.exitCode());
    }

    private static KeywordNode toKeywordNode(KeywordBuilder builder) {
        List<KeywordNode> children = builder.children.stream().map(OutputXmlParser::toKeywordNode).toList();
        return new KeywordNode(builder.name, normalizeStatus(builder.status),
                builder.durationMs, builder.messages, children,
                builder.logOffset(), builder.logSpan());
    }

    private static int[] countCases(SuiteNode node) {
        int pass = 0;
        int fail = 0;
        int skip = 0;
        for (CaseNode test : node.cases()) {
            switch (test.status()) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                case "SKIP" -> skip++;
                default -> {
                    // 非三类结论（如 NOT RUN）不进入计数，避免污染报表口径。
                }
            }
        }
        for (SuiteNode child : node.suites()) {
            int[] sub = countCases(child);
            pass += sub[0];
            fail += sub[1];
            skip += sub[2];
        }
        return new int[]{pass, fail, skip};
    }

    private static void putMetadata(SuiteBuilder suite, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        suite.metadata.put(key, value == null ? "" : value);
    }

    private static void putMetadata(Map<String, String> target, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        target.put(key, value == null ? "" : value);
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String attribute(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private static long elapsedMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Math.round(Double.parseDouble(raw.trim()) * 1000.0);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static final class SuiteBuilder {

        private final String name;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private final List<SuiteBuilder> suites = new ArrayList<>();
        private final List<CaseBuilder> cases = new ArrayList<>();
        private String status;

        private SuiteBuilder(String name) {
            this.name = name;
        }
    }

    private static final class CaseBuilder {

        private final String name;
        private final List<KeywordBuilder> keywords = new ArrayList<>();
        private final List<String> failureMessages = new ArrayList<>();
        private String status;
        private long durationMs;
        private long firstMessage = -1L;
        private long endMessage = -1L;

        private CaseBuilder(String name) {
            this.name = name;
        }

        private void recordMessage(long seq) {
            if (firstMessage < 0) {
                firstMessage = seq;
            }
            endMessage = seq + 1;
        }

        private long logOffset() {
            return firstMessage;
        }

        private String logSpan() {
            return firstMessage < 0 ? null : firstMessage + ":" + endMessage;
        }
    }

    private static final class KeywordBuilder {

        private final String name;
        private final List<String> messages = new ArrayList<>();
        private final List<KeywordBuilder> children = new ArrayList<>();
        private String status;
        private long durationMs;
        private long firstMessage = -1L;
        private long endMessage = -1L;

        private KeywordBuilder(String name) {
            this.name = name;
        }

        private void recordMessage(long seq) {
            if (firstMessage < 0) {
                firstMessage = seq;
            }
            endMessage = seq + 1;
        }

        private long logOffset() {
            return firstMessage;
        }

        private String logSpan() {
            return firstMessage < 0 ? null : firstMessage + ":" + endMessage;
        }
    }
}
