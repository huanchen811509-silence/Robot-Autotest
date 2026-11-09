package com.qa.autotest.application.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 运行进度（N15）：把 pabot / worker 控制台行解析成「已完成 / 通过 / 失败 / 跳过 + 最近用例」。
 *
 * <p>与实时日志同源：{@link PabotOrchestrator#emit} 的每一行都会经过这里，不新增外部通道、
 * 不改 WebSocket 协议。进度是<b>尽力而为</b>的展示信息，解析失败只影响读数，不影响执行与判定。
 *
 * <p>两个口径要在报告里说清：
 * <ul>
 *   <li><b>total</b> 只从 RF 汇总行（{@code N tests, X passed, Y failed}）拿；拿不到就是 0，
 *       前端按「不确定进度」显示，不伪造百分比；</li>
 *   <li><b>completed</b> 来自逐条<b>用例级</b>结果行（{@code | PASS |} / {@code | FAIL |} / {@code | SKIP |}）；
 *       并行分片下多线程写入，因此用 {@code synchronized} 保护同一 run 的计数。</li>
 * </ul>
 *
 * <p><b>套件行必须排除</b>（2026-11-07 实测）：RF/pabot 控制台除了用例结果行，还会打印套件级汇总行，
 * 形如 {@code run-RUN-xxx.Demo :: 套件说明 ... | PASS |} 与根套件 {@code run-RUN-xxx | PASS |}。
 * 早期实现把它们当用例计数，导致 2 个用例报出 {@code completed=6}，且 {@code lastTest} 取到套件名。
 * 判据：结果行的名称列以 {@code run-<runId>} 开头即套件行，不计入 completed / 不覆盖 lastTest。
 */
@Component
public class RunProgressRegistry {

    /** 进度快照；{@code total = 0} 表示总数未知（前端按不确定进度渲染）。 */
    public record Progress(String phase, int completed, int passed, int failed, int skipped,
                           int total, String lastTest, String lastVerdict, long elapsedSeconds) {
    }

    private static final Pattern RESULT = Pattern.compile("\\|\\s*(PASS|FAIL|SKIP)\\s*\\|");

    /** RF 汇总行：{@code 12 tests, 10 passed, 2 failed.}（全通过时 failed 段仍会出现，但允许缺失）。 */
    private static final Pattern SUMMARY =
            Pattern.compile("(\\d+)\\s+tests?\\s*,\\s*(\\d+)\\s+passed(?:\\s*,\\s*(\\d+)\\s+failed)?");

    private static final int MAX_PREFIX = 12;

    /** RF 控制台状态行的「名称 → 正文」对齐填充（2 个以上空格）。 */
    private static final Pattern PADDING = Pattern.compile("\\s{2,}");

    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public RunProgressRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 开始跟踪一条 run；重复调用会重置（同一 run 重跑时从头计数）。 */
    public void begin(String runId) {
        if (runId == null) {
            return;
        }
        states.put(runId, new State(clock.instant()));
    }

    /** 阶段：{@code preparing} / {@code running} / {@code reporting} / {@code done}。 */
    public void phase(String runId, String phase) {
        State state = states.get(runId);
        if (state != null) {
            state.phase = phase;
            state.lastActivityAt = clock.instant();
        }
    }

    /**
     * 最近一次活动时间：开始、阶段切换、任意控制台行都会刷新。
     * 进度接口用它算心跳年龄，判断「RUNNING 但长时间没有输出」。
     */
    public java.time.Instant lastActivityAt(String runId) {
        State state = states.get(runId);
        return state == null ? null : state.lastActivityAt;
    }

    /** 吃一行控制台输出；不是结果行/汇总行就忽略。 */
    public void onLine(String runId, String line) {
        if (runId == null || line == null) {
            return;
        }
        State state = states.get(runId);
        if (state == null) {
            return;
        }
        state.lastActivityAt = clock.instant();
        String text = stripPrefix(line);
        Matcher summary = SUMMARY.matcher(text);
        if (summary.find()) {
            synchronized (state) {
                // 汇总行是最终读数：逐条行可能因跟踪启动晚而缺失，这里只补高不覆盖；
                // 分片下先到的是子套件汇总（1 test），不能让 total 掉到已完成数以下
                state.total = Math.max(state.total, Math.max(parseInt(summary.group(1)), state.completed));
                state.passed = Math.max(state.passed, parseInt(summary.group(2)));
                if (summary.group(3) != null) {
                    state.failed = Math.max(state.failed, parseInt(summary.group(3)));
                }
            }
            return;
        }
        Matcher result = RESULT.matcher(text);
        if (!result.find()) {
            // 用例名可能先于结果后缀打印（用例内部 Log To Console 会把 | PASS | 挤到下一行），
            // 这里记住「名称 + 对齐填充 + 正文」这一行的名称段，作为结果行名称为空时的兜底；套件名行不参与
            String candidate = nameCandidate(text);
            if (!candidate.isEmpty() && candidate.length() <= 60 && !isSuiteRow(candidate, runId)) {
                synchronized (state) {
                    state.pendingTest = candidate;
                }
            }
            return;
        }
        String verdict = result.group(1);
        String name = testName(text.substring(0, result.start()));
        if (isSuiteRow(name, runId)) {
            return;
        }
        synchronized (state) {
            state.completed++;
            switch (verdict) {
                case "PASS" -> state.passed++;
                case "FAIL" -> state.failed++;
                default -> state.skipped++;
            }
            state.lastVerdict = verdict;
            if (!name.isEmpty()) {
                state.lastTest = name;
            } else if (state.pendingTest != null) {
                state.lastTest = state.pendingTest;
            }
        }
    }

    /** 用例名列：去掉 {@code Name :: 文档} 的文档段。 */
    private static String testName(String raw) {
        int doc = raw.indexOf(" :: ");
        return (doc > 0 ? raw.substring(0, doc) : raw).trim();
    }

    /**
     * 从「名称 + 对齐填充 + 正文」这种状态行里取名称段。
     *
     * <p>RF 控制台会把用例名打印到固定列（名称与正文之间是 2 个以上空格）；用例内部的
     * {@code Log To Console} 会直接跟在名称后面，结果后缀 {@code | PASS |} 被挤到后面的独立行。
     * 没有填充也没有 {@code ::} 的行是普通日志，不参与取名。
     */
    private static String nameCandidate(String text) {
        int pad = -1;
        Matcher padding = PADDING.matcher(text);
        if (padding.find()) {
            pad = padding.start();
        }
        int doc = text.indexOf(" :: ");
        int cut = pad >= 0 ? pad : (doc > 0 ? doc : -1);
        if (cut <= 0 || cut >= text.length()) {
            return "";
        }
        return testName(text.substring(0, cut));
    }

    /** 套件结果行（含根套件 {@code run-<runId>}）不是用例，不能计入 completed。 */
    private static boolean isSuiteRow(String name, String runId) {
        return !name.isEmpty() && name.startsWith("run-" + runId);
    }

    /** 快照；未跟踪的 run 返回 {@code null}（调用方按 run 状态推断阶段）。 */
    public Progress snapshot(String runId) {
        State state = states.get(runId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return new Progress(state.phase, state.completed, state.passed, state.failed,
                    state.skipped, state.total, state.lastTest, state.lastVerdict,
                    Duration.between(state.startedAt, clock.instant()).toSeconds());
        }
    }

    /** 去掉 {@code [stdout] / [stderr] / [worker:0] / [system]} 前缀，只留正文。 */
    static String stripPrefix(String line) {
        if (line.startsWith("[")) {
            int close = line.indexOf(']');
            if (close > 0 && close <= MAX_PREFIX) {
                return line.substring(close + 1).trim();
            }
        }
        return line;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class State {
        private final Instant startedAt;
        private volatile Instant lastActivityAt;
        private volatile String phase = "running";
        private int completed;
        private int passed;
        private int failed;
        private int skipped;
        private int total;
        private String lastTest;
        private String lastVerdict;
        private String pendingTest;

        private State(Instant startedAt) {
            this.startedAt = startedAt;
            this.lastActivityAt = startedAt;
        }
    }
}
