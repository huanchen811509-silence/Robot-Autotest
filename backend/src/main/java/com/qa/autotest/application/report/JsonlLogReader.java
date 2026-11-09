package com.qa.autotest.application.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * pytest 用例日志分段读取（FR-v3.9-03 ④）。
 *
 * <p>与 {@link LogXmlReader} 同一套「全局序号 + 左闭右开区间」口径，只是数据源是
 * {@link PytestLogPersister} 落盘的 {@code case-log.jsonl}。逐行读、按 seq 过滤、
 * 命中区间即停，绝不整文件进内存再过滤——大 run 的日志可以到几十万行。
 */
@Component
public class JsonlLogReader {

    private final ObjectMapper mapper;

    public JsonlLogReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取序号区间 {@code [fromInclusive, toExclusive)}。
     *
     * @throws IllegalArgumentException 文件不可读（由全局异常处理器翻译成 400）
     */
    public List<LogXmlReader.LogXmlLine> read(Path file, long fromInclusive, long toExclusive) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("用例日志不存在: " + file);
        }
        if (toExclusive <= fromInclusive) {
            return List.of();
        }
        List<LogXmlReader.LogXmlLine> out = new ArrayList<>();
        try {
            long index = 0;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (raw.isBlank()) {
                    continue;
                }
                if (index >= fromInclusive && index < toExclusive) {
                    out.add(parse(index, raw));
                }
                index++;
                if (index >= toExclusive) {
                    break;
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("读取用例日志失败: " + file, e);
        }
    }

    private LogXmlReader.LogXmlLine parse(long fallbackSeq, String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            return new LogXmlReader.LogXmlLine(
                    node.path("seq").asLong(fallbackSeq),
                    node.path("level").asText("INFO"),
                    node.path("keyword").asText(""),
                    node.path("message").asText(""));
        } catch (IOException e) {
            // 单行坏掉不该让整页日志 500：按原样当正文返回，并把级别标成 WARN 提示异常。
            return new LogXmlReader.LogXmlLine(fallbackSeq, "WARN", "", raw);
        }
    }
}
