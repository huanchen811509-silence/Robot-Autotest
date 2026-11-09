package com.qa.autotest.application.report;

import com.qa.autotest.application.execution.EvidenceLogWriter;
import com.qa.autotest.domain.report.KeywordNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用例级「下发命令」提取（FR-v3.9-02 / ADR-39-04）。
 *
 * <p>目标是把「这条用例到底对设备发了什么命令」变成平台一等数据，而不是散落在框架日志里。
 * 三条刻意的边界：
 * <ol>
 *   <li><b>不猜</b>：只认框架事实（关键字名白名单、pytest 标记、stdout 里的 {@code $ } 提示行）。
 *       认不出来就写 {@code NONE} 并把原因留在 {@code commandSource} 里，绝不从失败信息里
 *       反推一条"看起来像命令"的字符串。</li>
 *   <li><b>脱敏在入库前</b>：命令原文一律过 {@link EvidenceLogWriter#scrub}，
 *       凭据永不进库（与日志同一条规则，避免"日志脱敏了、报告没脱敏"）。</li>
 *   <li><b>截断显式</b>：超过 {@link #MAX_COMMAND_LENGTH} 保留前段并追加「…（已截断）」，
 *       不做静默裁剪——半个命令比没有命令更容易误判。</li>
 * </ol>
 *
 * <p>RF 与 pytest 两条取材路径分开写（{@link #fromRobotKeywords} / {@link #fromPytestStdout}），
 * 因为两者能拿到的事实形态完全不同：RF 有关键字树与消息，pytest 只有 stage 文本与 markers。
 */
public final class CaseCommandExtractor {

    /** 无命令时的显式取值。报告页据此显示「本用例未下发外部命令」。 */
    public static final String NONE = "NONE";

    public static final String SOURCE_RF_KEYWORD = "RF_KEYWORD";
    public static final String SOURCE_PYTEST_MARK = "PYTEST_MARK";
    public static final String SOURCE_STDOUT = "STDOUT_EXTRACT";
    public static final String SOURCE_NONE = NONE;

    /** 命令原文上限：4KB（DEV-PLAN §9）。超长截断并留标记。 */
    public static final int MAX_COMMAND_LENGTH = 4096;

    /**
     * RF 侧入口关键字白名单（ADR-39-04：识别式，不全文正则盲扫）。
     * 盲扫会把断言消息里出现的 "ssh xxx" 误判成命令；这里只认这些关键字的**自身消息**。
     * 新增远程执行入口时在这里登记，漏登记的表现是覆盖率下降（可观测），不是错记。
     */
    private static final List<String> RF_KEYWORDS = List.of(
            "execute command",
            "run process",
            "start process",
            "ssh 执行命令",
            "执行命令并记录原始输出",
            "远端执行",
            "远程执行",
            "执行远端命令");

    /**
     * 命令行的可识别前缀：既有夹具约定 {@code $ <命令>}，
     * 也有「命令: xxx」「CMD=xxx」「argv=xxx」三种常见写法。
     */
    private static final List<Pattern> COMMAND_PATTERNS = List.of(
            Pattern.compile("^\\$\\s+(.+)$"),
            Pattern.compile("^(?:命令|执行命令|CMD|cmd|argv)\\s*[:=]\\s*(.+)$"));

    /** 退出码：{@code exit=1} / {@code 退出码=1} / {@code rc=1}。 */
    private static final Pattern EXIT_PATTERN =
            Pattern.compile("(?:exit(?:[_ ]?code)?|退出码|rc)\\s*[:=]\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);

    private CaseCommandExtractor() {
    }

    /**
     * 提取结果。{@code source} 永远非空（无命令时为 {@link #NONE}），
     * {@code exitCode} 未知时为 {@code null}（不是 0——0 是"命令成功"的有效取值）。
     */
    public record Extracted(String text, String source, Integer exitCode) {

        public Extracted {
            text = text == null || text.isBlank() ? NONE : text;
            source = source == null || source.isBlank() ? SOURCE_NONE : source;
        }

        public static Extracted none() {
            return new Extracted(NONE, SOURCE_NONE, null);
        }

        public boolean hasCommand() {
            return !NONE.equals(text);
        }
    }

    /**
     * RF 取材：遍历关键字树，命中白名单的关键字取其消息里的命令行。
     * 同一用例多条命令时取**首条**为主（报告页只显示一条），其余丢弃但不算失败——
     * 完整命令清单属于 v3.12 的「命令复跑」范围，本版不展开。
     *
     * <p><b>为什么要往下钻子关键字</b>：真实套件的写法是
     * <pre>
     * 执行命令并记录原始输出
     *     Log    $ ${cmd}          # 这行在 RF 里是一条**子关键字** Log，不是父关键字的消息
     *     ${r}=    Run Process     bash    -c    ${cmd}
     *     Log    exit=${r.rc}
     * </pre>
     * 命令原文落在子关键字 {@code Log} 的消息上。只读命中关键字的自身消息，结果就是
     * 每条命令都提取不到（2026-11-09 实测：3 条用例全部 NONE）。所以命中后按前序遍历
     * 该关键字的**整棵子树**取第一条命令行——祖先仍是白名单关键字，不放开误识别面。
     */
    public static Extracted fromRobotKeywords(List<KeywordNode> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Extracted.none();
        }
        List<KeywordNode> hits = new ArrayList<>();
        collectMatching(keywords, hits);
        for (KeywordNode keyword : hits) {
            Extracted found = firstCommandInSubtree(keyword);
            if (found.hasCommand()) {
                return found;
            }
        }
        return Extracted.none();
    }

    /** 在命中关键字的子树里（含自身）找第一条命令行，退出码在该子树内就近取。 */
    private static Extracted firstCommandInSubtree(KeywordNode root) {
        List<KeywordNode> subtree = new ArrayList<>();
        collectSubtree(root, subtree);
        for (KeywordNode node : subtree) {
            for (String message : node.messages()) {
                String command = matchCommand(message);
                if (command != null) {
                    List<String> lines = new ArrayList<>();
                    for (KeywordNode item : subtree) {
                        lines.addAll(item.messages());
                    }
                    return new Extracted(scrubAndTruncate(command), SOURCE_RF_KEYWORD,
                            matchExitCode(lines, message));
                }
            }
        }
        return Extracted.none();
    }

    private static void collectSubtree(KeywordNode node, List<KeywordNode> out) {
        out.add(node);
        for (KeywordNode child : node.children()) {
            collectSubtree(child, out);
        }
    }

    private static void collectMatching(List<KeywordNode> nodes, List<KeywordNode> out) {
        for (KeywordNode node : nodes) {
            if (isCommandKeyword(node.name())) {
                out.add(node);
            }
            collectMatching(node.children(), out);
        }
    }

    /** 关键字名匹配：忽略大小写与首尾空白，允许「Execute Command    ssh ...」这类带参数的写法。 */
    static boolean isCommandKeyword(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (String keyword : RF_KEYWORDS) {
            if (lower.equals(keyword) || lower.startsWith(keyword + " ") || lower.startsWith(keyword + "\t")) {
                return true;
            }
        }
        return false;
    }

    /**
     * pytest 取材：json-report 的 stage 文本里找约定行。
     * 优先级：显式 {@code @pytest.mark.commandline}（由调用方先判） &gt; stdout 的 {@code $ } 行。
     */
    public static Extracted fromPytestStdout(String text) {
        if (text == null || text.isBlank()) {
            return Extracted.none();
        }
        List<String> lines = List.of(text.split("\\R"));
        for (String line : lines) {
            String command = matchCommand(line);
            if (command != null) {
                return new Extracted(scrubAndTruncate(command), SOURCE_STDOUT,
                        matchExitCode(lines, line));
            }
        }
        return Extracted.none();
    }

    /** 显式标记 {@code @pytest.mark.commandline("...")}：来源记为 PYTEST_MARK。 */
    public static Extracted fromPytestMark(String command) {
        if (command == null || command.isBlank()) {
            return Extracted.none();
        }
        return new Extracted(scrubAndTruncate(command), SOURCE_PYTEST_MARK, null);
    }

    /** 在一行里认出「命令行」。认不出返回 null（不抛异常：日志里什么都有）。 */
    static String matchCommand(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_COMMAND_LENGTH * 2) {
            return null;
        }
        for (Pattern pattern : COMMAND_PATTERNS) {
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.matches()) {
                String command = matcher.group(1).trim();
                return command.isEmpty() ? null : command;
            }
        }
        return null;
    }

    /**
     * 退出码：优先取命令行同一行里的，取不到再在该关键字/该段文本里找。
     * 取不到返回 null —— 写 0 会让"没记录"看起来像"成功退出"。
     */
    static Integer matchExitCode(List<String> lines, String preferredLine) {
        Integer fromLine = exitCodeOf(preferredLine);
        if (fromLine != null) {
            return fromLine;
        }
        for (String line : lines) {
            Integer value = exitCodeOf(line);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer exitCodeOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = EXIT_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.valueOf(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 脱敏 + 截断。脱敏规则与日志完全一致（同一个静态方法，不复制一份）。 */
    static String scrubAndTruncate(String command) {
        String scrubbed = EvidenceLogWriter.scrub(command);
        if (scrubbed == null) {
            return NONE;
        }
        String trimmed = scrubbed.trim();
        if (trimmed.length() <= MAX_COMMAND_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_COMMAND_LENGTH) + "…（已截断）";
    }

    /** 供测试与调用方判断来源合法性（门禁 28 步同一份清单）。 */
    public static List<String> allowedSources() {
        return List.of(SOURCE_RF_KEYWORD, SOURCE_PYTEST_MARK, SOURCE_STDOUT, SOURCE_NONE);
    }

    /** 便于诊断：把「哪些关键字识别到/没识别到」暴露出来，用于收敛白名单。 */
    public static Map<String, Integer> keywordHitStats(List<KeywordNode> keywords) {
        List<KeywordNode> hits = new ArrayList<>();
        collectMatching(keywords == null ? List.of() : keywords, hits);
        return Map.of("matched", hits.size(), "total", countAll(keywords));
    }

    private static int countAll(List<KeywordNode> nodes) {
        if (nodes == null) {
            return 0;
        }
        int total = 0;
        for (KeywordNode node : nodes) {
            total += 1 + countAll(node.children());
        }
        return total;
    }
}
