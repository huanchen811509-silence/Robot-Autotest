package com.qa.autotest.application.kernel.pytest;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 覆盖率双口径（FR-v5.1-05 / H11 / D12 拍板 / v5.1.2）。
 *
 * <p>为什么必须两个数一起给：把 178 条「待评审」从分母里剔掉以后，覆盖率**不是变高而是变低**
 * （13.0% → 10.3%）——因为 31 条已实现用例本身就落在待评审里，分母和分子一起缩，分子缩得更狠。
 * 这是反直觉的，所以口径必须写进页面页脚并锁死；只报一个数就是挑好看的报。
 *
 * <p>本类是两个口径的**唯一计算处**：健康检查、绑定同步、看板都从这里取数，
 * 避免三处各算一遍、算法悄悄漂移。
 *
 * @param totalCases              全部用例（含待评审）—— 469 的口径
 * @param bound                   已绑定脚本的用例数
 * @param pendingCases            优先级为「待评审」的用例数
 * @param boundExcludingPending   已绑定且非待评审的用例数
 */
public record PytestCoverage(long totalCases,
                             long bound,
                             long pendingCases,
                             long boundExcludingPending) {

    /** 口径 A 的分母：全部用例。 */
    public static final String CALIBER_ALL = "含待评审";

    /** 口径 B 的分母：排除待评审。 */
    public static final String CALIBER_EXCLUDING_PENDING = "排除待评审";

    /** 优先级取值之一：待评审。与用例库 Excel 第 5 列同字面。 */
    public static final String PRIORITY_PENDING = "待评审";

    /** 排除待评审后的分母。 */
    public long totalExcludingPending() {
        return Math.max(0, totalCases - pendingCases);
    }

    public long unbound() {
        return Math.max(0, totalCases - bound);
    }

    /** 口径 A：bound / total（含待评审）。 */
    public double coveragePercent() {
        return percent(bound, totalCases);
    }

    /** 口径 B：boundExcludingPending / totalExcludingPending。 */
    public double coverageExcludingPendingPercent() {
        return percent(boundExcludingPending, totalExcludingPending());
    }

    /** 恒等式：已绑定 + 未绑定 == 全部。破这条就是口径错，不是数据错。 */
    public boolean balanced() {
        return bound + unbound() == totalCases;
    }

    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    /**
     * @param codeToPriority 编号 → 优先级（{@code TestCaseRepository.casePriorities}）
     * @param boundCodes     已绑定的用例编号
     */
    public static PytestCoverage of(Map<String, String> codeToPriority,
                                    Collection<String> boundCodes) {
        Map<String, String> priorities = codeToPriority == null ? Map.of() : codeToPriority;
        Set<String> boundSet = boundCodes == null ? Set.of() : Set.copyOf(boundCodes);
        long pending = 0;
        long boundExcludingPending = 0;
        for (Map.Entry<String, String> entry : priorities.entrySet()) {
            boolean isPending = PRIORITY_PENDING.equals(trim(entry.getValue()));
            if (isPending) {
                pending++;
            }
            if (boundSet.contains(entry.getKey()) && !isPending) {
                boundExcludingPending++;
            }
        }
        // bound 只数「库里确实存在的编号」：脚本侧多出来的编号是孤儿，不进分子
        long bound = boundSet.stream().filter(priorities::containsKey).count();
        return new PytestCoverage(priorities.size(), bound, pending, boundExcludingPending);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
