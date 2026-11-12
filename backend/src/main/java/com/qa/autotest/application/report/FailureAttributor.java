package com.qa.autotest.application.report;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败归因五层（v5.2.0 / FR-v5.2-06，B15–B17）。
 *
 * <p>五层是给人看的顺序，也是排查的动作顺序：
 * <ol>
 *   <li><b>L1 结论</b>：这一例到底怎么死的（FAIL / ERROR / SKIP / PASS）；</li>
 *   <li><b>L2 分类</b>：是谁的问题（用例断言 / 环境 / 配置契约 / 脚本 / 数据 / 未知）；</li>
 *   <li><b>L3 位置</b>：在哪一行、哪一步；</li>
 *   <li><b>L4 原文</b>：工具链原话，不加工、不摘要；</li>
 *   <li><b>L5 建议</b>：下一步该做什么（一句话、可执行）。</li>
 * </ol>
 *
 * <p><b>为什么分类放在平台侧而不是让 AI 判</b>：分类结论会进报告、进统计、进环境失败分流，
 * 必须是<b>确定性</b>的——同一份输入跑两次得同一个分类，否则「环境失败占比」这种指标
 * 每天都在漂。AI 的位置在 L5 建议（W53-05），且必须标「AI 建议，需人工确认」。
 *
 * <p>规则按「先具体后笼统」排序：`SSHException` 一定先于 `assert` 判定，
 * 因为断言库在连接失败时也会抛带 assert 字样的错误；先判环境能避免把环境问题记成用例缺陷。
 */
public final class FailureAttributor {

    public static final String CONCLUSION_PASS = "PASS";
    public static final String CONCLUSION_FAIL = "FAIL";
    public static final String CONCLUSION_ERROR = "ERROR";
    public static final String CONCLUSION_SKIP = "SKIP";

    public static final String CATEGORY_ASSERT = "ASSERT";
    public static final String CATEGORY_ENV = "ENV";
    public static final String CATEGORY_CONFIG = "CONFIG";
    public static final String CATEGORY_SCRIPT = "SCRIPT";
    public static final String CATEGORY_DATA = "DATA";
    public static final String CATEGORY_PLAN_ONLY = "PLAN_ONLY";
    public static final String CATEGORY_UNKNOWN = "UNKNOWN";

    /** 一次归因结果。{@code evidenceStep} 是作为依据的那一步（可能为 null）。 */
    public record Attribution(String conclusion,
                              String category,
                              String location,
                              String original,
                              String suggestion,
                              String evidenceStep) {

        /** 是否属于「环境/配置」类：这类失败不计入产品通过率（W52-10 的分流依据）。 */
        public boolean environmentSide() {
            return CATEGORY_ENV.equals(category) || CATEGORY_CONFIG.equals(category);
        }
    }

    private static final Pattern FILE_LINE = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.(?:py|robot|java|ts|js))(?::(\\d+))?");
    /** Python 的报错写成 {@code (test_x.py, line 42)}，行号不在冒号后面，要单独抓。 */
    private static final Pattern WORD_LINE = Pattern.compile("\\bline\\s+(\\d+)");

    /** 环境侧关键字（连接、认证、超时、权限、DNS）。全部小写比较。 */
    private static final List<String> ENV_TOKENS = List.of(
            "sshexception", "authenticationexception", "no valid connections",
            "connection refused", "connection reset", "connection timed out",
            "timed out", "timeout", "permission denied", "no route to host",
            "host is down", "name or service not known", "temporary failure in name resolution",
            "bad authentication type", "unable to connect");

    /** 配置/契约侧关键字（框架能力缺失、参数不认识、fixture 找不到）。 */
    private static final List<String> CONFIG_TOKENS = List.of(
            "unrecognized arguments", "unrecognized option", "no such option",
            "fixture '", "fixture \"", "fixtureerror", "importerror", "modulenotfounderror",
            "requires-python", "contract", "no such file or directory",
            "jsonexport", "invalid config", "run_env", "inventory");

    /** 数据侧关键字（用例库里的资产/规格数据缺失或对不上）。 */
    private static final List<String> DATA_TOKENS = List.of(
            "keyerror", "indexerror", "nonetype", "valueerror", "empty", "not found in inventory",
            "未读到", "无有效回显", "缺少", "不存在");

    private static final List<String> SCRIPT_TOKENS = List.of(
            "syntaxerror", "indentationerror", "taberror", "nameerror", "typeerror",
            "attributeerror");

    private FailureAttributor() {
    }

    /**
     * 归因。
     *
     * @param kernel          robot / pytest；仅影响 L3 的表述，不影响分类
     * @param verdict         用例结论（PASS/FAIL/ERROR/SKIP/UNKNOWN）
     * @param failureMessage  用例级失败文本（可多行，只用首行判分类）
     * @param stepContents    该用例已入库的步骤内容，用于在无堆栈时定位到「哪一步」
     */
    public static Attribution of(String kernel, String verdict, String failureMessage,
                                 List<String> stepContents) {
        String conclusion = conclusionOf(verdict);
        String first = firstLine(failureMessage);
        String haystack = (first + " " + String.valueOf(failureMessage)).toLowerCase(Locale.ROOT);
        String step = lastStep(stepContents);

        if (CONCLUSION_PASS.equals(conclusion)) {
            return new Attribution(conclusion, CATEGORY_UNKNOWN, locationOf(first, kernel, null),
                    "", "", step);
        }
        // Plan-only 的 skip 不是失败：把它归进任何失败分类都会污染通过率与环境占比。
        if (CONCLUSION_SKIP.equals(conclusion) && (haystack.contains("plan-only")
                || joinSteps(stepContents).contains("plan-only"))) {
            return new Attribution(conclusion, CATEGORY_PLAN_ONLY, "—",
                    first, "本用例只在 Plan-only 下执行过，未接触真实设备；要判定功能是否通过须跑 real 模式。",
                    step);
        }
        // 顺序即优先级：环境 → 配置 → 脚本 → 数据 → 断言。
        String category = classify(haystack);
        return new Attribution(conclusion, category, locationOf(first, kernel, step),
                first, suggestionOf(category), step);
    }

    private static String conclusionOf(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            // 结论缺失按 ERROR 处理：不知道结果时不能当成通过，也不能当成产品缺陷。
            return CONCLUSION_ERROR;
        }
        return switch (verdict.trim().toUpperCase(Locale.ROOT)) {
            case "PASS" -> CONCLUSION_PASS;
            case "SKIP", "SKIPPED" -> CONCLUSION_SKIP;
            case "FAIL", "FAILED" -> CONCLUSION_FAIL;
            default -> CONCLUSION_ERROR;
        };
    }

    private static String classify(String haystack) {
        if (containsAny(haystack, ENV_TOKENS)) {
            return CATEGORY_ENV;
        }
        if (containsAny(haystack, CONFIG_TOKENS)) {
            return CATEGORY_CONFIG;
        }
        if (containsAny(haystack, SCRIPT_TOKENS)) {
            return CATEGORY_SCRIPT;
        }
        if (containsAny(haystack, DATA_TOKENS)) {
            return CATEGORY_DATA;
        }
        // 「断言失败」是最常见的兜底：失败文本里有 assert / 判定不通过 / 期望
        if (haystack.contains("assert") || haystack.contains("判定：不通过")
                || haystack.contains("不通过") || haystack.contains("期望")) {
            return CATEGORY_ASSERT;
        }
        return CATEGORY_UNKNOWN;
    }

    private static String suggestionOf(String category) {
        return switch (category) {
            case CATEGORY_ENV -> "先查设备侧：SSH 可达性、用户名口令、端口与防火墙、管理网是否在维护窗口；"
                    + "环境恢复后重跑，不要先改用例。";
            case CATEGORY_CONFIG -> "查平台与用例库的契约：解释器版本、pytest 参数、run_env/inventory 渲染产物、"
                    + "路径是否存在；这类失败不是产品缺陷。";
            case CATEGORY_SCRIPT -> "查用例脚本自身：语法、缩进、导入名、类型；先本地最小复现再提交修改。";
            case CATEGORY_DATA -> "查资产与规格数据：设备型号/端口/PCI 地址是否录全、是否与字典一致；"
                    + "补数据后重跑同一选择集。";
            case CATEGORY_ASSERT -> "按 L3/L4 定位到具体判定项与该步回显：确认是产品不符合规格，还是规格基线写错；"
                    + "必要时附命令原文与原始输出提缺陷。";
            default -> "文本不足以归类：取该用例的证据包（步骤 + 命令原文 + 原始回显）后人工判断。";
        };
    }

    /**
     * L3 位置：优先 `文件:行`，其次「哪一步」，最后 kernel 默认表述。
     *
     * <p>不把 {@code step} 直接当位置：步骤标签是业务语义（如「校验速率」），
     * 文件行号是代码语义；两列分开给，读者要哪个取哪个。
     */
    private static String locationOf(String firstLine, String kernel, String step) {
        if (firstLine != null && !firstLine.isBlank()) {
            Matcher matcher = FILE_LINE.matcher(firstLine);
            if (matcher.find()) {
                String file = matcher.group(1).replace('\\', '/');
                int slash = file.lastIndexOf('/');
                String shortName = slash >= 0 ? file.substring(slash + 1) : file;
                String line = matcher.group(2);
                if (line == null) {
                    Matcher wordLine = WORD_LINE.matcher(firstLine);
                    if (wordLine.find()) {
                        line = wordLine.group(1);
                    }
                }
                return line == null ? shortName : shortName + ":" + line;
            }
        }
        if (step != null && !step.isBlank()) {
            return step;
        }
        return "pytest".equalsIgnoreCase(kernel) ? "该用例首次判定失败处" : "该用例首个失败关键字";
    }

    /** 最后一条步骤内容 —— 失败通常停在最后一步，比第一步更有信息量。调用方只传失败步。 */
    private static String lastStep(List<String> stepContents) {
        if (stepContents == null) {
            return null;
        }
        String found = null;
        for (String content : stepContents) {
            if (content == null || content.isBlank()) {
                continue;
            }
            found = content;
        }
        return found == null ? null : truncate(found, 120);
    }

    private static String joinSteps(List<String> stepContents) {
        return stepContents == null ? "" : String.join(" ", stepContents).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, List<String> tokens) {
        for (String token : tokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return truncate(line.trim(), 400);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
