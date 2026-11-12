package com.qa.autotest.application.execution.monitor;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robot Framework 控制台行解析（W53-01 取材适配器）。
 *
 * <p>RF 的形态与 pytest 完全不同：用例结果行是「名称 + 若干空格 + {@code | PASS |}」，
 * 而不是 {@code 文件::函数 PASSED}。同一条事实（某用例过了）要在两边都能被认出来，
 * 所以适配器必须分开写，但产出的 {@link MonitorEvent} 必须一模一样。
 *
 * <p><b>套件行必须排除</b>（2026-11-07 实测踩过）：pabot 除了用例结果行，还会打印套件级
 * 汇总行 {@code run-RUN-xxx.Demo :: 套件说明 ... | PASS |} 和根套件 {@code run-RUN-xxx | PASS |}。
 * 早期把它们当用例计数，2 个用例报出 {@code completed=6}。判据是名称列以 {@code run-<runId>}
 * 开头——这里沿用同一条判据，避免监视器与进度注册表两处口径漂移。
 */
public final class RobotKernelAdapter implements KernelAdapter {

    /** 结果行：{@code 名称<2+空格>| PASS | 说明}。 */
    private static final Pattern RESULT = Pattern.compile(
            "^(.*?)\\s{2,}\\|\\s*(PASS|FAIL|SKIP)\\s*\\|\\s*(.*)$");

    /** 汇总行：{@code 12 tests, 10 passed, 2 failed.} */
    private static final Pattern SUMMARY = Pattern.compile(
            "(\\d+)\\s+tests?\\s*,\\s*(\\d+)\\s+passed(?:\\s*,\\s*(\\d+)\\s+failed)?");

    /** 段分隔线（{@code ====} / {@code ----}）。 */
    private static final Pattern RULE = Pattern.compile("^\\s*[=\\-]{3,}\\s*$");

    /** RF 的告警/错误标记。 */
    private static final Pattern ERROR = Pattern.compile("^\\s*\\[\\s*ERROR\\s*\\]\\s*(.*)$");
    private static final Pattern WARN = Pattern.compile("^\\s*\\[\\s*WARN\\s*\\]?\\s*(.*)$");

    private static final String SUITE_PREFIX = "run-";

    @Override
    public String kernel() {
        return "robot";
    }

    @Override
    public Optional<Parsed> parse(String line, boolean stderr) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        Matcher result = RESULT.matcher(line);
        if (result.matches()) {
            String name = result.group(1).trim();
            if (isSuiteLine(name)) {
                // 套件行不是用例，直接丢弃（不是降级、不是告警，就是无关行）
                return Optional.empty();
            }
            return Optional.of(Parsed.of(MonitorEvent.Kind.CASE_END,
                    caseCodeOf(name), name, result.group(2)));
        }

        Matcher summary = SUMMARY.matcher(line);
        if (summary.find()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.PHASE, null, "summary",
                    "total=" + summary.group(1) + " passed=" + summary.group(2)
                            + " failed=" + (summary.group(3) == null ? "0" : summary.group(3))));
        }

        Matcher error = ERROR.matcher(line);
        if (error.matches()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.ERROR, null, null, error.group(1)));
        }
        Matcher warn = WARN.matcher(line);
        if (warn.matches()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.WARNING, null, null, warn.group(1)));
        }
        if (RULE.matcher(line).matches()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.PHASE, null, null, null));
        }
        if (stderr) {
            // stderr 的任意一行都是错误语境；但仍先尝试当命令（命令回显常走 stderr）
            return KernelAdapter.detectCommand(line).map(Parsed::command)
                    .or(() -> Optional.of(Parsed.of(MonitorEvent.Kind.ERROR, null, null,
                            line.trim().toLowerCase(Locale.ROOT))));
        }
        return KernelAdapter.detectCommand(line).map(Parsed::command);
    }

    /** 套件行判定：名称列以 {@code run-} 开头（pabot 的 run 前缀）。 */
    static boolean isSuiteLine(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(SUITE_PREFIX);
    }

    /** 用例标识：RF 名称里的 {@code [CASE-1]} 标签或末段。取不到返回 null，不编造。 */
    static String caseCodeOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int open = name.indexOf('[');
        int close = name.indexOf(']', open + 1);
        if (open >= 0 && close > open + 1) {
            return name.substring(open + 1, close).trim();
        }
        return name.trim();
    }
}
