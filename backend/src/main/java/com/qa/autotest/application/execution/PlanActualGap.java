package com.qa.autotest.application.execution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「计划要跑」与「实际有结果」的差集（FR-v5.4-06 / W54-07 / GAP-16 / P7）。
 *
 * <p><b>为什么这件事必须显式说</b>：pytest 的用例可以带参数（{@code test_x[a-b]}），
 * 而自研框架当前<b>没有逐例参数下发能力</b>（没有 {@code --case-param}，只有 run 级配置）。
 * 于是一个参数化用例完全可能「计划里有、跑完一条结果都没有」——收集期因为缺参数被跳过，
 * 退出码仍是 0。这种情况在报告上表现为「本次执行 12 条全通过」，而真实结论是
 * 「8 条跑了、4 条根本没跑」。前者是错的口径，后者才是能拿去对外的结论。
 *
 * <p>所以这里只做一件事：<b>把差集算出来并说清楚可能原因</b>。
 * 不猜、不补、不把缺失的用例伪造成 SKIP —— 伪造状态会让差集永久消失，
 * 而「差了多少条」正是需要的那个数。
 *
 * <p>匹配键与 {@code PytestResultNormalizer.matchKey} 同一口径（{@code <文件末段>::<函数名>}）：
 * junit 的 classname 是点分、nodeid 是斜杠，两种形态都要能归一，且参数化后缀 {@code [...]} 一律剥掉。
 */
public final class PlanActualGap {

    private PlanActualGap() {
    }

    /**
     * 差集结论。
     *
     * @param planned    计划条数（去重后的键数）
     * @param actual     实际产出结果的条数
     * @param notRun     计划里有、结果里没有的键（按计划顺序，最多列 {@value #SAMPLE_LIMIT} 条）
     * @param notRunTotal 未产出结果的总条数（可能大于 {@code notRun.size()}）
     * @param unexpected 结果里有、计划里没有的条数（一般是被 {@code -k} 之外的方式带进来的用例）
     * @param notice     给人看的一句话；无差异时为 null
     */
    public record Gap(int planned, int actual, List<String> notRun, int notRunTotal,
                      int unexpected, String notice) {

        /** 有没有「计划了但没跑」。{@code unexpected} 不算缺口——多跑不是漏跑。 */
        public boolean hasGap() {
            return notRunTotal > 0;
        }
    }

    /** 差集里最多列几个键：够定位问题即可，全列出来会把一行日志撑成几十行。 */
    public static final int SAMPLE_LIMIT = 5;

    /**
     * 算差集。
     *
     * @param plannedKeys 计划执行的用例键（可用 {@link #keyOf} 归一）
     * @param actualKeys  实际产出结果的用例键
     * @param kernel      内核名，只影响提示文案里点不点 {@code --case-param}
     */
    public static Gap of(List<String> plannedKeys, List<String> actualKeys, String kernel) {
        Set<String> planned = normalize(plannedKeys);
        Set<String> actual = normalize(actualKeys);
        List<String> notRun = new ArrayList<>();
        int notRunTotal = 0;
        for (String key : planned) {
            if (!actual.contains(key)) {
                notRunTotal++;
                if (notRun.size() < SAMPLE_LIMIT) {
                    notRun.add(key);
                }
            }
        }
        int unexpected = 0;
        for (String key : actual) {
            if (!planned.contains(key)) {
                unexpected++;
            }
        }
        String notice = notRunTotal == 0 ? null
                : message(planned.size(), actual.size(), notRun, notRunTotal, kernel);
        return new Gap(planned.size(), actual.size(), List.copyOf(notRun), notRunTotal,
                unexpected, notice);
    }

    private static String message(int planned, int actual, List<String> sample, int notRunTotal,
                                  String kernel) {
        StringBuilder sb = new StringBuilder();
        sb.append("计划执行 ").append(planned).append(" 条，实际产生结果 ").append(actual)
                .append(" 条；其中 ").append(notRunTotal).append(" 条没有任何结果");
        if (!sample.isEmpty()) {
            sb.append("（例如 ").append(String.join("、", sample));
            if (notRunTotal > sample.size()) {
                sb.append(" 等 ").append(notRunTotal).append(" 条");
            }
            sb.append("）");
        }
        sb.append("。常见原因：");
        if ("pytest".equalsIgnoreCase(kernel)) {
            sb.append("参数化用例未提供逐例参数（当前框架未提供 --case-param，只能下发 run 级参数）、");
        }
        sb.append("被 -k/-m 过滤、或收集期报错。请补齐参数或从计划中移除，不要按「全部通过」对外。");
        return sb.toString();
    }

    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw) {
            String key = keyOf(item);
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * 归一键：{@code <文件末段>::<函数名>}。
     *
     * <p>同时吃两种写法——{@code tests/nic/base/test_x.py::test_x}（nodeid）与
     * {@code tests.nic.base.test_x::test_x}（junit classname）。参数化后缀
     * {@code [a-b]} 一律剥掉，否则同一用例的不同参数实例会被当成不同用例。
     */
    public static String keyOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        String file;
        String func;
        int sep = value.indexOf("::");
        if (sep < 0) {
            file = value;
            func = "";
        } else {
            // 类方法写法 tests/a/test_b.py::TestC::test_d 取首段为文件、末段为函数
            String rest = value.substring(sep + 2);
            int innerSep = rest.indexOf("::");
            file = value.substring(0, sep);
            func = innerSep < 0 ? rest : rest.substring(rest.lastIndexOf("::") + 2);
        }
        String stem = stemOf(file);
        String base = baseName(func);
        if (stem.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? stem : stem + "::" + base;
    }

    private static String stemOf(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        String name = file;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // 先剥已知扩展名再按点取末段：`tests.nic.base.test_x` 按点取末段得 test_x，
        // `a.robot` 若不先剥扩展名会被取成 "robot"，那会让所有 RF 套件撞成一个键。
        for (String ext : new String[] {".py", ".robot"}) {
            if (lower.endsWith(ext)) {
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        int bracket = name.indexOf('[');
        return (bracket < 0 ? name : name.substring(0, bracket)).trim();
    }
}
