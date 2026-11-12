package com.qa.autotest.application.execution.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 执行监视器（FR-v5.3-01 / W53-01）：内核无关，四输入三输出，自身绝不拖累执行。
 *
 * <p><b>四输入</b>：进程 stdout/stderr（{@link #stdout}/{@link #stderr}）、
 * 证据目录变化（{@link #evidenceChanged}）、{@code progress.jsonl}（{@link #progress}）、
 * 平台 {@code execution_event}（{@link #executionEvent}）。
 *
 * <p><b>三输出</b>：脱敏日志切片（{@link Snapshot#logSlice}）、结构化事件
 * （{@link Snapshot#events}）、命令集合（{@link Snapshot#commands}）。
 *
 * <p><b>R4 是本类的第一原则</b>：{@link #feed} 把任何解析动作包在 try/catch 里，
 * 出异常只标 {@code DEGRADED}，绝不向外抛。理由很直接——监视器是观测手段，
 * 观测失败最坏的结果是「看不见」，绝不能变成「测试跑不下去」。测试里有一条
 * 「喂垃圾输入不抛异常」的用例专门守这条。
 *
 * <p><b>R5 资源上限</b>：日志行数、事件数、命令数、单行字符数、每秒行速率五道闸。
 * 超限<b>丢弃并计数</b>（{@code dropped*}），不静默——页面上要能显示「丢了 N 行」，
 * 否则「看起来完整」的日志比「明确缺失」更危险。
 *
 * <p>线程安全：执行线程（日志）与调度线程（事件）会同时喂数据，所有入口 {@code synchronized}。
 * 同步块里只做内存操作，不做 I/O，不会拖慢执行。
 */
public final class ExecutionMonitor {

    public static final int DEFAULT_MAX_LOG_LINES = 5_000;
    public static final int DEFAULT_MAX_EVENTS = 2_000;
    public static final int DEFAULT_MAX_COMMANDS = 5_000;
    /** 单行最多保留字符数。RF 的字典变量 dump 一行能到几十 KB。 */
    public static final int DEFAULT_MAX_LINE_CHARS = 4_096;
    /** 每秒最多接收行数。超了只丢不报错——卡住的监视器不能阻塞被监视的进程。 */
    public static final int DEFAULT_MAX_LINES_PER_SECOND = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String runId;
    private final String kernel;
    private final KernelAdapter adapter;
    private final Clock clock;
    private final int maxLogLines;
    private final int maxEvents;
    private final int maxCommands;
    private final int maxLineChars;
    private final int maxLinesPerSecond;

    private final List<String> logSlice = new ArrayList<>();
    private final List<MonitorEvent> events = new ArrayList<>();
    private final List<CommandRecord> commands = new ArrayList<>();
    private final Map<String, String> degradedReasons = new LinkedHashMap<>();

    private long seq;
    /** 当前用例：命令落在哪个用例下靠它 —— 命令行本身不带用例标识。 */
    private String currentCase;
    private long droppedLines;
    private long droppedEvents;
    private long droppedCommands;
    private long droppedByRate;
    private Instant rateWindowStart;
    private int rateCount;

    public ExecutionMonitor(String runId, KernelAdapter adapter, Clock clock) {
        this(runId, adapter, clock, DEFAULT_MAX_LOG_LINES, DEFAULT_MAX_EVENTS,
                DEFAULT_MAX_COMMANDS, DEFAULT_MAX_LINE_CHARS, DEFAULT_MAX_LINES_PER_SECOND);
    }

    public ExecutionMonitor(String runId, KernelAdapter adapter, Clock clock,
                            int maxLogLines, int maxEvents, int maxCommands,
                            int maxLineChars, int maxLinesPerSecond) {
        this.runId = runId;
        this.kernel = adapter.kernel();
        this.adapter = adapter;
        this.clock = clock;
        this.maxLogLines = Math.max(1, maxLogLines);
        this.maxEvents = Math.max(1, maxEvents);
        this.maxCommands = Math.max(1, maxCommands);
        this.maxLineChars = Math.max(64, maxLineChars);
        this.maxLinesPerSecond = Math.max(1, maxLinesPerSecond);
        this.rateWindowStart = clock.instant();
    }

    /** 按内核名建监视器；未知内核按 {@code other} 处理（只记日志，不产事件）。 */
    public static ExecutionMonitor forKernel(String runId, String kernel, Clock clock) {
        String value = kernel == null ? "" : kernel.trim().toLowerCase(Locale.ROOT);
        KernelAdapter adapter = switch (value) {
            case "pytest" -> new PytestKernelAdapter();
            case "robot", "rf" -> new RobotKernelAdapter();
            default -> new UnknownKernelAdapter(value.isEmpty() ? "other" : value);
        };
        return new ExecutionMonitor(runId, adapter, clock);
    }

    // ------------------------------------------------------------------ 四输入

    public synchronized void stdout(String line) {
        feed(line, false, () -> adapter.parse(line, false));
    }

    public synchronized void stderr(String line) {
        feed(line, true, () -> adapter.parse(line, true));
    }

    /**
     * {@code progress.jsonl} 的一行（R1 第三种输入）。
     *
     * <p>进度文件由执行侧产出（RF 走 pabot 汇总，pytest 走 json-report 中间产物），
     * 它是<b>可选</b>的：文件不存在或行非法都不影响其它三路输入。
     * 只认 {@code phase} / {@code case} / {@code verdict} 三个键，其余字段忽略——
     * 监视器不承担「理解执行器内部格式」的责任。
     */
    public synchronized void progress(String jsonlLine) {
        if (jsonlLine == null || jsonlLine.isBlank()) {
            return;
        }
        try {
            JsonNode node = JSON.readTree(jsonlLine);
            String phase = text(node, "phase");
            String caseCode = text(node, "case");
            String verdict = text(node, "verdict");
            if (phase != null) {
                emit(MonitorEvent.Kind.PHASE, null, "progress", phase);
            }
            if (caseCode != null && verdict != null) {
                emit(MonitorEvent.Kind.CASE_END, caseCode, caseCode, verdict.toUpperCase(Locale.ROOT));
            } else if (caseCode != null) {
                emit(MonitorEvent.Kind.CASE_START, caseCode, caseCode, null);
            }
        } catch (Exception e) {
            degrade("progress-parse", "progress.jsonl 行无法解析：" + rootMessage(e));
        }
    }

    /**
     * 证据目录发生变化（R1 第二种输入）：只记事实，不去读文件。
     *
     * <p>「谁变了」本身是有用信息（例如新增 {@code output.xml} 说明进入报告阶段），
     * 但监视器不打开文件——读盘失败会把观测变成异常源，与 R4 冲突。
     */
    public synchronized void evidenceChanged(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        emit(MonitorEvent.Kind.WARNING, null, "evidence", relativePath.trim());
    }

    /**
     * 平台 {@code execution_event}（R1 第四种输入）。
     *
     * <p>这条通道让「平台自己知道的事」（租约获取、节点调度、超时告警）也进同一条事件流，
     * 于是页面只需读一处就能按时间线还原全过程，不必再 join 三张表。
     */
    public synchronized void executionEvent(String type, String detail) {
        if (type == null || type.isBlank()) {
            return;
        }
        // name 原样存事件类型，不加前缀：数据里带显示装饰会污染过滤与统计
        emit(MonitorEvent.Kind.PHASE, null, type.trim(), detail);
    }

    // ------------------------------------------------------------------ 三输出

    /**
     * 快照（三输出 + 降级信息）。
     *
     * @param logSlice 已脱敏的日志行（可能因超限少于实际输出，差额见 {@code droppedLines}）
     */
    public record Snapshot(String runId, String kernel, List<String> logSlice,
                           List<MonitorEvent> events, List<CommandRecord> commands,
                           long droppedLines, long droppedEvents, long droppedCommands,
                           long droppedByRate, boolean degraded, Map<String, String> degradedReasons,
                           int caseEnded, int casePassed, int caseFailed) {

        /** 累计丢弃行数（含速率丢弃）。 */
        public long totalDropped() {
            return droppedLines + droppedByRate;
        }

        /** 监视数据是否完整可用（页面据此决定是否显示「监视不可用」）。 */
        public boolean complete() {
            return !degraded && totalDropped() == 0;
        }
    }

    public synchronized Snapshot snapshot() {
        int ended = 0;
        int passed = 0;
        int failed = 0;
        for (MonitorEvent event : events) {
            if (event.kind() == MonitorEvent.Kind.CASE_END) {
                ended++;
                if (event.passed()) {
                    passed++;
                } else if (!"SKIP".equalsIgnoreCase(event.detail())
                        && !"SKIPPED".equalsIgnoreCase(event.detail())) {
                    failed++;
                }
            }
        }
        return new Snapshot(runId, kernel, List.copyOf(logSlice), List.copyOf(events),
                List.copyOf(commands), droppedLines, droppedEvents, droppedCommands,
                droppedByRate, !degradedReasons.isEmpty(), Map.copyOf(degradedReasons),
                ended, passed, failed);
    }

    // ------------------------------------------------------------------ 内部

    /** 日志行入口：限速 → 截断 → 脱敏 → 存切片 → 解析事件。 */
    private void feed(String line, boolean stderr, Supplier<Optional<KernelAdapter.Parsed>> parser) {
        if (line == null) {
            return;
        }
        try {
            if (!admitRate()) {
                return;
            }
            String text = line.length() > maxLineChars
                    ? line.substring(0, maxLineChars) + "…[truncated " + (line.length() - maxLineChars) + " chars]"
                    : line;
            String scrubbed = scrub(text);
            if (logSlice.size() < maxLogLines) {
                logSlice.add(scrubbed);
            } else {
                droppedLines++;
            }
            parser.get().ifPresent(parsed -> {
                if (parsed.command() != null) {
                    addCommand(parsed.command());
                }
                emit(parsed.kind(), parsed.caseCode(), parsed.name(), scrub(parsed.detail()));
            });
        } catch (RuntimeException e) {
            // R4：解析失败只降级，绝不外抛
            degrade("parse", rootMessage(e));
        }
    }

    /** 速率闸：窗口内超限就丢，并且只记一次原因（否则降级原因会被刷爆）。 */
    private boolean admitRate() {
        Instant now = clock.instant();
        if (Duration.between(rateWindowStart, now).getSeconds() >= 1) {
            rateWindowStart = now;
            rateCount = 0;
        }
        rateCount++;
        if (rateCount > maxLinesPerSecond) {
            droppedByRate++;
            if (droppedByRate == 1) {
                degrade("rate", "日志行速率超过 " + maxLinesPerSecond + " 行/秒，超出部分已丢弃");
            }
            return false;
        }
        return true;
    }

    private void addCommand(KernelAdapter.CommandDraft draft) {
        if (commands.size() >= maxCommands) {
            droppedCommands++;
            if (droppedCommands == 1) {
                degrade("commands", "命令集合超过 " + maxCommands + " 条上限，超出部分已丢弃");
            }
            return;
        }
        // 不在这里再过一次速率闸：命令是日志行的派生结果，行已经被限过一次
        commands.add(CommandRecord.of(runId, null, currentCase, commands.size() + 1L,
                draft.endpoint(), draft.argv(), clock.instant(), null, null, null));
    }

    private void emit(MonitorEvent.Kind kind, String caseCode, String name, String detail) {
        if (caseCode != null && (kind == MonitorEvent.Kind.CASE_START
                || kind == MonitorEvent.Kind.CASE_END)) {
            // CASE_END 之后仍保留：紧随其后的清理命令属于该用例
            currentCase = caseCode;
        }
        if (events.size() >= maxEvents) {
            droppedEvents++;
            if (droppedEvents == 1) {
                degrade("events", "事件数超过 " + maxEvents + " 上限，超出部分已丢弃");
            }
            return;
        }
        events.add(new MonitorEvent(++seq, clock.instant(), kernel, kind, caseCode, name, detail));
    }

    private void degrade(String key, String reason) {
        degradedReasons.putIfAbsent(key, reason == null ? "(无详细信息)" : reason);
    }

    /** 日志切片脱敏：先抹凭据形态（-pXXX / --password=XXX），再按值抹（SecretRedactor）。 */
    private static String scrub(String value) {
        if (value == null) {
            return null;
        }
        return com.qa.autotest.application.execution.EvidenceLogWriter.scrub(
                SensitiveMask.mask(value));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText == null || asText.isBlank() ? null : asText.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 未知内核：只记日志，不产事件也不产命令。好过假装能解析。 */
    static final class UnknownKernelAdapter implements KernelAdapter {

        private final String kernel;

        UnknownKernelAdapter(String kernel) {
            this.kernel = kernel;
        }

        @Override
        public String kernel() {
            return kernel;
        }

        @Override
        public Optional<Parsed> parse(String line, boolean stderr) {
            return Optional.empty();
        }
    }
}
