package com.qa.autotest.application.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨内核拆单（FR-v5.2-04 / 验收 B10/B11 / W52-05）。
 *
 * <p>规则只有一条硬约束：<b>同一个 run 里绝不混内核</b>。RF 用 pabot 拉起、pytest 用
 * {@code python -m pytest} 拉起，两者的事件流、报告格式、超时语义都不同，
 * 混在一个 run 里意味着归一器要同时处理两种产物——那不是「兼容」，是给每条判据都加一层歧义。
 *
 * <p>拆单之后有三件事必须仍然成立，本类逐条保证：
 * <ol>
 *   <li><b>用例不丢不重</b>：{@code sum(split.cases) == input.size()}，且每个用例只出现一次；</li>
 *   <li><b>组内保持用例集顺序</b>：用户在用例集里排的顺序就是执行顺序，拆单不许打乱；</li>
 *   <li><b>组间顺序稳定</b>：按用例数降序、再按内核名排序——不稳定的顺序会让
 *       「同一批用例两次执行的开始时间不同」变成常态，进度看板与时长基线全部失真。</li>
 * </ol>
 *
 * <p>纯函数，不碰数据库：拆单是决策，落库由 {@link ProjectRunCoordinator} 负责。
 */
public final class RunSplitter {

    private RunSplitter() {
    }

    /**
     * 待拆的用例。
     *
     * @param caseCode 库内用例编号（用于报告与错误消息）
     * @param kernel   生效内核（{@code robot} / {@code pytest}），由 KernelSelector 决定
     * @param suitePath 该用例所属的用例文件路径（RF 是 .robot，pytest 是 .py）
     * @param priority 用例优先级数值，越大越优先（0 = 未标）
     */
    public record PlannedCase(String caseCode, String kernel, String suitePath, int priority) {
    }

    /**
     * 拆出的一单。
     *
     * @param kernel     该单使用的内核
     * @param requestKey 幂等键后缀，用于派生 {@code runRequestId}
     * @param cases      该单包含的用例（保持输入顺序）
     * @param suitePaths 该单要传给内核的用例文件（去重、保持首次出现顺序）
     * @param priority   该单的 run 级优先级 = 组内最高
     */
    public record SplitRun(String kernel, String requestKey, List<PlannedCase> cases,
                           List<String> suitePaths, int priority) {

        public int caseCount() {
            return cases.size();
        }
    }

    /** 交叉校验失败：拆单结果与输入对不上，属于程序错误，不是用户输入问题。 */
    public static final class SplitMismatchException extends IllegalStateException {
        public SplitMismatchException(String message) {
            super(message);
        }
    }

    /**
     * 按内核拆单。
     *
     * <p>输入为空返回空列表（调用方自己决定「没选用例」要不要报错）。
     * 内核字段为空或空白按 {@code robot} 处理，与 {@code KernelSelector} 的默认一致。
     */
    public static List<SplitRun> split(List<PlannedCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        Map<String, List<PlannedCase>> grouped = new LinkedHashMap<>();
        for (PlannedCase item : cases) {
            String kernel = normalizeKernel(item.kernel());
            grouped.computeIfAbsent(kernel, key -> new ArrayList<>()).add(item);
        }
        List<SplitRun> runs = new ArrayList<>();
        for (Map.Entry<String, List<PlannedCase>> entry : grouped.entrySet()) {
            List<PlannedCase> group = entry.getValue();
            int priority = group.stream().mapToInt(PlannedCase::priority).max().orElse(0);
            runs.add(new SplitRun(entry.getKey(), requestKey(entry.getKey()), List.copyOf(group),
                    suitePathsOf(group), priority));
        }
        // 顺序稳定：用例数降序（大单一早开跑，尾巴最短），再按内核名——只按用例数排
        // 时两组相等的顺序取决于 HashMap 迭代顺序，同一批用例两次跑会得到不同的开始次序。
        runs.sort(Comparator.comparingInt(SplitRun::caseCount).reversed()
                .thenComparing(SplitRun::kernel));
        verify(cases, runs);
        return List.copyOf(runs);
    }

    /**
     * 恒等式校验：拆单结果必须是输入的**一个划分**（每例恰好一次）。
     *
     * <p>写成公开方法而不是私有断言，是因为它同时是机检脚本的判据入口：
     * 单独拿一组数据跑一遍就能证伪「拆单丢用例」。
     */
    public static void verify(List<PlannedCase> input, List<SplitRun> output) {
        int counted = 0;
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (SplitRun run : output) {
            for (PlannedCase item : run.cases()) {
                counted++;
                String key = keyOf(item);
                seen.merge(key, 1, Integer::sum);
                if (!normalizeKernel(item.kernel()).equals(run.kernel())) {
                    throw new SplitMismatchException("用例 " + item.caseCode()
                            + " 的 kernel=" + item.kernel() + " 落进了 " + run.kernel() + " 单");
                }
            }
        }
        if (counted != input.size()) {
            throw new SplitMismatchException("拆单后用例数 " + counted
                    + " 与输入 " + input.size() + " 不一致");
        }
        for (Map.Entry<String, Integer> entry : seen.entrySet()) {
            if (entry.getValue() > 1) {
                throw new SplitMismatchException("用例重复出现在多个单里：" + entry.getKey()
                        + "（" + entry.getValue() + " 次）");
            }
        }
    }

    /**
     * 幂等键后缀。用内核名即可：同一批用例同一内核只允许一单，
     * 因为再拆细就必须引入分片语义，而那属于 {@code ShardService} 的职责。
     */
    private static String requestKey(String kernel) {
        return kernel;
    }

    private static List<String> suitePathsOf(List<PlannedCase> group) {
        List<String> paths = new ArrayList<>();
        for (PlannedCase item : group) {
            String path = item.suitePath();
            if (path != null && !path.isBlank() && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private static String normalizeKernel(String kernel) {
        if (kernel == null || kernel.isBlank()) {
            return "robot";
        }
        return kernel.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String keyOf(PlannedCase item) {
        return item.caseCode() == null || item.caseCode().isBlank()
                ? item.suitePath() : item.caseCode();
    }
}
