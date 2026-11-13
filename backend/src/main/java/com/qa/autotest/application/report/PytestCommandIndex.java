package com.qa.autotest.application.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 逐用例「下发命令」索引（v5.6.0 / FR-v5.6-21）。
 *
 * <p><b>为什么不去 junit 里找</b>：junit 只带 {@code system-out}/{@code system-err}，
 * 而用例库默认日志级别是 INFO，SSH 命令那行是 DEBUG —— 实测 2026-11-13 的 59 条真实执行，
 * junit 里 {@code command=} 出现 0 次。因此「从 junit 取命令」这条路在当前用例库上恒为
 * {@code NONE}（这正是 v5.5.0 评估里「下发命令列恒 NONE」的根因）。
 *
 * <p><b>真源在这里</b>：框架的 {@code SSHConnection.run()} 每下发一条命令就向
 * {@code <repo>/artifacts/<run_id>/cases/<node_id>/raw/<endpoint>.commands.jsonl} 追加一行 JSON，
 * 含 {@code command} / {@code purpose} / {@code expected_observation}。这是框架自己写的事实，
 * 不需要从失败信息里反推，符合 {@link CaseCommandExtractor}「不猜」的约束。
 *
 * <p><b>为什么放在 report 包而不是 pytest 适配包</b>：门禁
 * {@code check_kernel_decoupling} 要求适配层不得反向依赖 {@code application.report}；
 * 命令索引本质是「报告侧读证据」，放这里方向才对——report 依赖 kernel 是允许的，反之不行。
 *
 * <p><b>目录名规则</b>：框架 {@code Runtime.begin_case} 用
 * {@code re.sub(r"[^A-Za-z0-9_.-]+", "_", node_id)} 把 nodeid 变成目录名，
 * 本类逐字对应（{@link #NODE_ID_SANITIZER}），两边不一致就会一条都找不到。
 */
public final class PytestCommandIndex {

    /** 与 {@code Runtime.begin_case} 的 re.sub 逐字对应；改这里必须同步改用例库。 */
    static final String NODE_ID_SANITIZER = "[^A-Za-z0-9_.-]+";

    /** 框架写的命令索引文件名后缀。 */
    public static final String INDEX_SUFFIX = ".commands.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PytestCommandIndex() {
    }

    /**
     * 取一个用例的首条命令。
     *
     * @param artifactRoot 框架产物根，即 {@code <repo>/artifacts/<run_id>}
     * @param nodeIds      候选 nodeid，按优先级排列（绑定脚本路径拼出的优先，junit 原名兜底）
     * @return 取不到时返回 {@link CaseCommandExtractor.Extracted#none()}，不抛异常——
     *         命令是加分项，不该让一次成功的运行因为读不到证据而失败
     */
    public static CaseCommandExtractor.Extracted lookup(Path artifactRoot, List<String> nodeIds) {
        if (artifactRoot == null || nodeIds == null) {
            return CaseCommandExtractor.Extracted.none();
        }
        for (String nodeId : nodeIds) {
            Path rawDir = rawDir(artifactRoot, nodeId);
            if (rawDir == null || !Files.isDirectory(rawDir)) {
                continue;
            }
            CaseCommandExtractor.Extracted found = firstCommandInDir(rawDir);
            if (found.hasCommand()) {
                return found;
            }
        }
        return CaseCommandExtractor.Extracted.none();
    }

    /** nodeid → {@code <root>/cases/<sanitized>/raw}；nodeid 为空时返回 null。 */
    static Path rawDir(Path artifactRoot, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String sanitized = nodeId.replaceAll(NODE_ID_SANITIZER, "_");
        return artifactRoot.resolve("cases").resolve(sanitized).resolve("raw");
    }

    /**
     * 在一个用例的 raw 目录里找首条命令。
     *
     * <p>一个用例可能连了多台设备（被测端 + 对端 + 交换机），因此这里有多份 jsonl；
     * 文件名排序后取第一份——顺序固定，同一产物两次读取结果一致。
     */
    private static CaseCommandExtractor.Extracted firstCommandInDir(Path rawDir) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(rawDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(INDEX_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            return CaseCommandExtractor.Extracted.none();
        }
        for (Path file : files) {
            CaseCommandExtractor.Extracted found = firstCommandInFile(file);
            if (found.hasCommand()) {
                return found;
            }
        }
        return CaseCommandExtractor.Extracted.none();
    }

    private static CaseCommandExtractor.Extracted firstCommandInFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CaseCommandExtractor.Extracted.none();
        }
        for (String line : lines) {
            String command = commandOf(line);
            if (command != null && !command.isBlank()) {
                return CaseCommandExtractor.fromCommandIndex(command);
            }
        }
        return CaseCommandExtractor.Extracted.none();
    }

    /** 一行 JSON 取 {@code command}；坏行直接跳过（日志型文件里出现半行是常态）。 */
    private static String commandOf(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            return node.path("command").asText(null);
        } catch (IOException e) {
            return null;
        }
    }
}
