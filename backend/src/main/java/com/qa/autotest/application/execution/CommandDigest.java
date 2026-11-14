package com.qa.autotest.application.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令清单摘要（W57-20 / W57-21）：把整份清单规范化后算 sha256，并给出增删改序差异。
 *
 * <p>规范化只消除「与语义无关」的差异，保留「用例清单顺序」这类必须被检测的变化：
 * <ul>
 *   <li><b>环境变量排序</b>：{@code KEY=VALUE} 形式的 token 抽出来按字典序排到最前；</li>
 *   <li><b>分隔符固定</b>：Windows 反斜杠统一为 {@code /}，token 间统一用 {@code |} 连接；</li>
 *   <li><b>路径去尾斜杠</b>：token（或环境变量的值）末尾连续的 {@code /} 去掉，根 {@code /} 保留；</li>
 *   <li><b>清单顺序保留</b>：用例增删、改序都会改变 canonical 行顺序，从而改变摘要。</li>
 * </ul>
 *
 * <p>纯静态算法，不连数据库、不依赖运行环境，便于单测覆盖等价与差异两类场景。
 */
public final class CommandDigest {

    /** 摘要盐，与 V11_60 迁移注释一致。 */
    public static final String PREFIX = "v5.7-run-confirm:";
    /** 规范化后的固定分隔符。 */
    private static final String SEP = "|";
    private static final int HEX_LENGTH = 64;

    private CommandDigest() {
    }

    /** 差异：三个数组里都是 <b>1-based 位置编号</b>（after 用 after 的位置，removed 用 before 的位置）。 */
    public record Diff(List<Integer> added, List<Integer> removed, List<Integer> reordered) {
    }

    /** 规范化单条 argv：环境变量排序 + 分隔符固定 + 路径去尾斜杠。 */
    public static String normalizeArgv(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return "";
        }
        List<String> env = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (String raw : argv) {
            String token = normalizeToken(raw);
            if (token.isEmpty()) {
                continue;
            }
            if (isEnvAssignment(token)) {
                env.add(token);
            } else {
                args.add(token);
            }
        }
        env.sort(Comparator.naturalOrder());
        List<String> ordered = new ArrayList<>(env.size() + args.size());
        ordered.addAll(env);
        ordered.addAll(args);
        return String.join(SEP, ordered);
    }

    /** 把整份命令清单转成规范行（顺序 = 用例清单顺序，不排序）。 */
    public static List<String> canonicalize(List<CommandPlan> plans) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(plans.size());
        for (CommandPlan plan : plans) {
            String code = plan.caseCode() == null ? "" : plan.caseCode().trim();
            String cwd = normalizePath(plan.cwd());
            String dut = plan.dut() == null ? "" : plan.dut().trim();
            lines.add(String.join(SEP, code, normalizeArgv(plan.argv()), cwd,
                    Integer.toString(plan.timeoutSeconds()), dut));
        }
        return List.copyOf(lines);
    }

    /** 整份清单摘要：sha256(PREFIX + 规范行按换行拼接)，返回 64 位小写 hex。 */
    public static String digest(List<CommandPlan> plans) {
        return sha256Hex(PREFIX + String.join("\n", canonicalize(plans)));
    }

    /** 前 12 位，供预览接口展示。 */
    public static String shortDigest(List<CommandPlan> plans) {
        return digest(plans).substring(0, 12);
    }

    public static String sha256Hex(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static boolean isSha256Hex(String value) {
        return value != null && value.length() == HEX_LENGTH
                && value.matches("[0-9a-f]{" + HEX_LENGTH + "}");
    }

    /** 两份命令清单的差异（身份 = 规范行）。 */
    public static Diff diff(List<CommandPlan> before, List<CommandPlan> after) {
        return diffLines(canonicalize(before), canonicalize(after));
    }

    /** 两份「用例 id 清单」的差异（身份 = id 字符串），供选择快照重算复用。 */
    public static Diff diffIds(List<Long> before, List<Long> after) {
        return diffLines(toStrings(before), toStrings(after));
    }

    /** 通用差异：added/removed 按「本方是否含有」判定，reordered 按同一元素位置是否变化判定。 */
    public static Diff diffLines(List<String> a, List<String> b) {
        Set<String> aSet = new LinkedHashSet<>(a);
        Set<String> bSet = new LinkedHashSet<>(b);
        Map<String, Integer> aFirstIndex = new HashMap<>();
        for (int i = 0; i < a.size(); i++) {
            aFirstIndex.putIfAbsent(a.get(i), i);
        }
        List<Integer> added = new ArrayList<>();
        List<Integer> removed = new ArrayList<>();
        List<Integer> reordered = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) {
            String item = b.get(i);
            if (!aSet.contains(item)) {
                added.add(i + 1);
            } else if (aFirstIndex.get(item) != i) {
                reordered.add(i + 1);
            }
        }
        for (int i = 0; i < a.size(); i++) {
            if (!bSet.contains(a.get(i))) {
                removed.add(i + 1);
            }
        }
        return new Diff(List.copyOf(added), List.copyOf(removed), List.copyOf(reordered));
    }

    private static List<String> toStrings(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(id == null ? "" : Long.toString(id));
        }
        return out;
    }

    private static String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().replace('\\', '/');
        int eq = token.indexOf('=');
        if (eq > 0 && isEnvAssignment(token)) {
            return token.substring(0, eq) + "=" + normalizePath(token.substring(eq + 1));
        }
        return normalizePath(token);
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /** 环境变量赋值：{@code [A-Za-z_][A-Za-z0-9_]*=...}；不会误判 {@code --opt=...} 或路径。 */
    private static boolean isEnvAssignment(String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        char first = token.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < eq; i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }
}
