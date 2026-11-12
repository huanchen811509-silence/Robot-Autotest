package com.qa.autotest.application.execution.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 命令集合的落地与检索（FR-v5.3-02 / W53-02）。
 *
 * <p>存储形态按 PRD「存储与查询」表：<b>文件（{@code commands.jsonl}）为主，查询走文件扫描</b>，
 * 原文不入库。理由：一个 5000 条命令的 run 若逐条入库，会让 `run` 库表体积随执行次数线性膨胀，
 * 而命令集合的读取频率远低于执行频率（只在排障与对比时看），用文件 + 扫描完全够用，
 * 且天然跟随 run 的保留策略清理（Q11）。
 *
 * <p><b>为什么自己拼 JSON 而不用 ObjectMapper 直接序列化 record</b>：
 * {@code Instant} 需要 JavaTimeModule，而文件格式一旦确定就被 {@code check_command_set.py}
 * 当契约校验，不能随某个 ObjectMapper 的配置变化而变。手写字段名，字段顺序与 PRD 表一致，
 * 契约才稳。
 *
 * <p><b>写入用「临时文件 + 原子移动」</b>：命令集合是随执行增量写的，中途被杀进程时
 * 半截文件会让后续所有查询都报解析错。原子移动保证「要么旧内容、要么新内容」。
 */
@Service
public class CommandSetService {

    private static final Logger log = LoggerFactory.getLogger(CommandSetService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String FILE_NAME = "commands.jsonl";

    private final Path root;

    public CommandSetService(@Value("${execution.command-set-root:${user.home}/robot-evidence}") String root) {
        this.root = Path.of(root);
    }

    /** 某 run 的命令集合文件路径：{@code <root>/<runId>/commands.jsonl}。 */
    public Path fileOf(String runId) {
        return root.resolve(safe(runId)).resolve(FILE_NAME);
    }

    /** 覆盖写。空集合也要写空文件——「采集到了但没有命令」与「没采集」必须能区分。 */
    public void write(String runId, List<CommandRecord> commands) {
        Path file = fileOf(runId);
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (CommandRecord record : commands) {
                sb.append(toJson(record)).append('\n');
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // 采集失败不能影响执行（与监视器的 R4 同一条原则），只记日志
            log.warn("命令集合写入失败 run={}：{}", runId, e.toString());
        }
    }

    /** 读全量。文件不存在返回空列表（不是异常：老 run 本来就没采集过）。 */
    public List<CommandRecord> read(String runId) {
        Path file = fileOf(runId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<CommandRecord> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(fromJson(line));
                } catch (RuntimeException e) {
                    // 单行坏掉不影响整份（截断的最后一行属于正常情形）
                    log.debug("命令集合行解析失败 run={}：{}", runId, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("命令集合读取失败 run={}：{}", runId, e.toString());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * 检索条件（R4）。所有字段为 null 表示不限。
     *
     * @param exitCode   退出码；{@code null} 不限
     * @param failedOnly 只看非 0 退出码（{@code exitCode} 已指定时以它为准）
     */
    public record Query(String caseCode, String endpoint, CommandKind kind,
                        Integer exitCode, boolean failedOnly, int limit) {

        public static Query all() {
            return new Query(null, null, null, null, false, 500);
        }

        public static Query ofCase(String caseCode) {
            return new Query(caseCode, null, null, null, false, 500);
        }
    }

    /** 检索：内存过滤。单 run 上限 5000 条，全量读入再过滤比逐行拼 SQL 更简单也更快。 */
    public List<CommandRecord> query(String runId, Query query) {
        Query effective = query == null ? Query.all() : query;
        List<CommandRecord> out = new ArrayList<>();
        for (CommandRecord record : read(runId)) {
            if (effective.caseCode() != null
                    && !effective.caseCode().equals(record.caseCode())) {
                continue;
            }
            if (effective.endpoint() != null
                    && !effective.endpoint().equals(record.endpoint())) {
                continue;
            }
            if (effective.kind() != null && record.kind() != effective.kind()) {
                continue;
            }
            if (effective.exitCode() != null
                    && !effective.exitCode().equals(record.exitCode())) {
                continue;
            }
            if (effective.failedOnly() && (record.exitCode() == null || record.exitCode() == 0)) {
                continue;
            }
            out.add(record);
            if (out.size() >= Math.max(1, effective.limit())) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 两次 run 的命令差异。 */
    public record CommandDiff(List<CommandRecord> added, List<CommandRecord> removed,
                              List<Change> changed) {

        public boolean identical() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        public int total() {
            return added.size() + removed.size() + changed.size();
        }
    }

    /** 同一位置（用例 + 组内序号）两边的命令不同。 */
    public record Change(CommandRecord left, CommandRecord right) {
    }

    /**
     * 两次 run 同用例命令 diff（R4 后半句）。
     *
     * <p><b>对齐键选用例 + 组内序号，不用 cmd_id</b>：{@code cmd_id} 是 {@code runId#seq}，
     * 两次 run 必然全不同，按它对只会得到「全删全增」这种没有信息量的结果。
     * 用「用例 + 该用例内第几条」对齐，才能看出「同一用例第二次跑，命令从 A 变成 B」。
     *
     * @param caseCode 只比该用例；null 表示比全部
     */
    public CommandDiff diff(String leftRunId, String rightRunId, String caseCode) {
        List<CommandRecord> left = keyed(leftRunId, caseCode);
        List<CommandRecord> right = keyed(rightRunId, caseCode);
        List<CommandRecord> added = new ArrayList<>();
        List<CommandRecord> removed = new ArrayList<>();
        List<Change> changed = new ArrayList<>();
        int max = Math.max(left.size(), right.size());
        for (int i = 0; i < max; i++) {
            CommandRecord l = i < left.size() ? left.get(i) : null;
            CommandRecord r = i < right.size() ? right.get(i) : null;
            if (l == null) {
                added.add(r);
            } else if (r == null) {
                removed.add(l);
            } else if (!l.sameShape(r)) {
                changed.add(new Change(l, r));
            }
        }
        return new CommandDiff(List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    private List<CommandRecord> keyed(String runId, String caseCode) {
        List<CommandRecord> all = read(runId);
        if (caseCode == null) {
            return all;
        }
        List<CommandRecord> out = new ArrayList<>();
        for (CommandRecord record : all) {
            if (caseCode.equals(record.caseCode())) {
                out.add(record);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- JSON

    static String toJson(CommandRecord record) {
        ObjectNode node = JSON.createObjectNode();
        node.put("cmd_id", record.cmdId());
        node.put("run_id", record.runId());
        node.put("node_id", record.nodeId());
        node.put("case_code", record.caseCode());
        node.put("seq", record.seq());
        node.put("endpoint", record.endpoint());
        node.put("kind", record.kind().name().toLowerCase(java.util.Locale.ROOT));
        var argv = node.putArray("argv");
        record.argv().forEach(argv::add);
        node.put("started_at", record.startedAt() == null ? null : record.startedAt().toString());
        node.put("duration_ms", record.durationMs());
        node.put("exit_code", record.exitCode());
        node.put("stdout_sha", record.stdoutSha());
        node.put("truncated", record.truncated());
        node.put("sensitive", record.sensitive());
        return node.toString();
    }

    static CommandRecord fromJson(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            List<String> argv = new ArrayList<>();
            JsonNode array = node.get("argv");
            if (array != null && array.isArray()) {
                array.forEach(item -> argv.add(item.asText()));
            }
            String startedAt = text(node, "started_at");
            return new CommandRecord(
                    text(node, "cmd_id"), text(node, "run_id"), text(node, "node_id"),
                    text(node, "case_code"),
                    node.path("seq").asLong(),
                    text(node, "endpoint"),
                    parseKind(text(node, "kind")),
                    List.copyOf(argv),
                    startedAt == null ? null : Instant.parse(startedAt),
                    node.hasNonNull("duration_ms") ? node.get("duration_ms").asLong() : null,
                    node.hasNonNull("exit_code") ? node.get("exit_code").asInt() : null,
                    text(node, "stdout_sha"),
                    node.path("truncated").asBoolean(false),
                    node.path("sensitive").asBoolean(false));
        } catch (IOException e) {
            throw new IllegalArgumentException("命令集合行不是合法 JSON", e);
        }
    }

    private static CommandKind parseKind(String value) {
        if (value == null) {
            return CommandKind.OTHER;
        }
        try {
            return CommandKind.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CommandKind.OTHER;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String safe(String runId) {
        String value = runId == null ? "" : runId.trim();
        if (value.isEmpty() || value.contains("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("非法 runId：" + runId);
        }
        return value;
    }
}
