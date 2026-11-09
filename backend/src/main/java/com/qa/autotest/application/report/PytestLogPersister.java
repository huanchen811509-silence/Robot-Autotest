package com.qa.autotest.application.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

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
            return new Spans(Map.of());
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
