package com.qa.autotest.application.execution.monitor;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pytest 控制台行解析（W53-01 取材适配器）。
 *
 * <p>pytest 的结果行是 {@code tests/test_x.py::test_a PASSED   [ 50%]}，
 * 失败详情是 {@code E   AssertionError: ...}，会话头是 {@code ==== test session starts ====}。
 *
 * <p><b>为什么 caseCode 取「文件末段::函数名」而不是整条 nodeid</b>：
 * 同一用例在不同机器上 nodeid 的目录前缀不同（{@code /abs/path/tests/a.py} 与
 * {@code tests/a.py}），用整条会让「同一条用例的事件」在两个环境下对不上；
 * 只用末段又会撞名。折中取「文件名::函数名」，与 junit 侧的点分类名可以互相校正
 * （这条键在 5.1.x 的 {@code PytestResultNormalizer} 里已经定过一次，此处沿用同一口径）。
 */
public final class PytestKernelAdapter implements KernelAdapter {

    /** 结果行：{@code tests/a.py::test_x PASSED [ 50%]}。 */
    private static final Pattern RESULT = Pattern.compile(
            "^(\\S+?::\\S+?)\\s+(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)\\b.*$");

    /** 会话阶段标记。 */
    private static final Pattern SESSION = Pattern.compile(
            "^=+\\s*(.*?)\\s*=+$");

    /** 失败详情首行：{@code E   AssertionError: 1 != 2}。 */
    private static final Pattern DETAIL = Pattern.compile("^E\\s{2,}(.*)$");

    /** 告警汇总行。 */
    private static final Pattern WARN = Pattern.compile(
            "^=+\\s*warnings summary\\s*=+$|^(?:\\S+):(\\d+):\\s+\\w*Warning\\b.*$");

    /** 进度百分比：只用于日志切片阅读，不参与判定。 */
    @Override
    public String kernel() {
        return "pytest";
    }

    @Override
    public Optional<Parsed> parse(String line, boolean stderr) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        Matcher result = RESULT.matcher(line);
        if (result.matches()) {
            String nodeId = result.group(1);
            return Optional.of(Parsed.of(MonitorEvent.Kind.CASE_END,
                    caseCodeOf(nodeId), nodeId, result.group(2)));
        }

        Matcher detail = DETAIL.matcher(line);
        if (detail.matches()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.ERROR, null, null, detail.group(1)));
        }

        Matcher session = SESSION.matcher(line);
        if (session.matches()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.PHASE, null, null,
                    session.group(1).toLowerCase(Locale.ROOT)));
        }
        if (WARN.matcher(line).find()) {
            return Optional.of(Parsed.of(MonitorEvent.Kind.WARNING, null, null, line.trim()));
        }
        if (stderr) {
            return KernelAdapter.detectCommand(line).map(Parsed::command)
                    .or(() -> Optional.of(Parsed.of(MonitorEvent.Kind.ERROR, null, null,
                            line.trim().toLowerCase(Locale.ROOT))));
        }
        return KernelAdapter.detectCommand(line).map(Parsed::command);
    }

    /** nodeid → {@code 文件名::函数名}（去掉目录前缀与参数化后缀）。 */
    static String caseCodeOf(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String value = nodeId.trim();
        int bracket = value.indexOf('[');
        if (bracket > 0) {
            // 参数化用例：tests/a.py::test_x[1-a] → 保留参数化 id，否则同函数的多条用例会合并
            String head = value.substring(0, bracket);
            int split = head.lastIndexOf("::");
            String file = split >= 0 ? head.substring(0, split) : head;
            String function = split >= 0 ? head.substring(split + 2) : "";
            return fileName(file) + "::" + function + value.substring(bracket);
        }
        int split = value.lastIndexOf("::");
        String file = split >= 0 ? value.substring(0, split) : value;
        String function = split >= 0 ? value.substring(split + 2) : "";
        return fileName(file) + "::" + function;
    }

    private static String fileName(String path) {
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
