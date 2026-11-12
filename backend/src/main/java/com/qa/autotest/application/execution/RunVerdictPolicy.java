package com.qa.autotest.application.execution;

import com.qa.autotest.application.report.FailureAttributor;

/**
 * 失败裁决（FR-v5.2-03 / 验收 B8/B9 / W52-06）。
 *
 * <p>三动作：{@code retry}（重试）、{@code stop}（止损停批）、{@code handover}（转人工）。
 *
 * <p><b>为什么必须自动化裁决</b>：一批 300 条用例里如果有 20 条都是「SSH 连不上」，
 * 让它跑完没有任何信息增益，只是把台架时间烧掉、把报告里的失败数从 20 涨到 200，
 * 掩盖真正的产品缺陷。所以要么重试（偶发），要么停（系统性），要么交人（判断不了）。
 *
 * <p>顺序是有讲究的，不是随便排的：
 * <ol>
 *   <li><b>连续性止损最先判</b>：连续 5 条同一分类失败，说明不是偶发，重试只是浪费；
 *       这一条要压过「环境类可重试」，否则环境彻底坏掉时会重试到天荒地老。</li>
 *   <li><b>断言失败不重试</b>：断言失败通常是真缺陷，重试一次拿证据足够；
 *       给断言失败重试预算等于在刷通过率。</li>
 *   <li><b>环境类优先重试</b>：网卡 reset、链路抖动是真实存在的偶发，重试有实际价值。</li>
 *   <li><b>判断不了的转人工</b>：不猜，也不假装成功。</li>
 * </ol>
 */
public final class RunVerdictPolicy {

    public static final String DECISION_RETRY = "retry";
    public static final String DECISION_STOP = "stop";
    public static final String DECISION_HANDOVER = "handover";

    private RunVerdictPolicy() {
    }

    /**
     * 单次失败的全部输入。
     *
     * @param category            归因分类（{@link FailureAttributor} 的 CATEGORY_*）
     * @param attemptNo           当前是第几次（1 = 首跑）
     * @param maxAttempts         该用例允许的总次数（含首跑），{@code 1} 表示不重试
     * @param consecutiveSameType 含本条在内、连续同分类失败的条数
     * @param stopThreshold       连续多少条同分类就该停（{@code <=0} 表示不启用）
     * @param passRatePercent     本批当前通过率（0–100 的整数）
     * @param minPassRatePercent  通过率下限，低于它就该停（{@code <=0} 表示不启用）
     */
    public record Input(String category, int attemptNo, int maxAttempts, int consecutiveSameType,
                        int stopThreshold, int passRatePercent, int minPassRatePercent) {
    }

    /** 裁决结果。{@code reason} 直接进页面与报告，必须是人话且带数字。 */
    public record Verdict(String decision, String reason) {

        public boolean retry() {
            return DECISION_RETRY.equals(decision);
        }

        public boolean stop() {
            return DECISION_STOP.equals(decision);
        }

        public boolean handover() {
            return DECISION_HANDOVER.equals(decision);
        }
    }

    public static Verdict decide(Input input) {
        String category = input.category() == null
                ? FailureAttributor.CATEGORY_UNKNOWN : input.category();
        boolean budgetLeft = input.attemptNo() < input.maxAttempts();

        // 1) 连续性止损：压过一切重试理由
        if (input.stopThreshold() > 0 && input.consecutiveSameType() >= input.stopThreshold()) {
            return new Verdict(DECISION_STOP,
                    "连续 " + input.consecutiveSameType() + " 条同类失败（" + category
                            + "），达到止损阈值 " + input.stopThreshold()
                            + "；继续跑只会把同一个原因重复 N 遍，先修再跑");
        }

        // 2) 通过率下限：整体已经崩了，没必要跑完
        if (input.minPassRatePercent() > 0
                && input.passRatePercent() < input.minPassRatePercent()) {
            return new Verdict(DECISION_STOP,
                    "当前通过率 " + input.passRatePercent() + "% 低于下限 "
                            + input.minPassRatePercent() + "%，触发止损");
        }

        // 3) 断言失败：一次性重试拿证据，不刷分
        if (FailureAttributor.CATEGORY_ASSERT.equals(category)) {
            if (budgetLeft && input.attemptNo() == 1) {
                return new Verdict(DECISION_RETRY,
                        "断言失败（第 1 次）——复跑一次确认是否偶发；若仍失败即按产品缺陷处理");
            }
            return new Verdict(DECISION_HANDOVER,
                    "断言失败已重试过（第 " + input.attemptNo() + " 次），按产品缺陷转人工确认");
        }

        // 4) 环境类：偶发性最高，值得重试
        if (FailureAttributor.CATEGORY_ENV.equals(category)) {
            if (budgetLeft) {
                return new Verdict(DECISION_RETRY,
                        "环境类失败（第 " + input.attemptNo() + "/" + input.maxAttempts()
                                + " 次），存在偶发可能，重试一次");
            }
            return new Verdict(DECISION_HANDOVER,
                    "环境类失败已用尽 " + input.maxAttempts() + " 次尝试，重试无意义，"
                            + "转人工修环境（不进产品通过率分母）");
        }

        // 5) 配置 / 脚本 / 数据类：确定性原因，重试必然复现
        if (FailureAttributor.CATEGORY_CONFIG.equals(category)
                || FailureAttributor.CATEGORY_SCRIPT.equals(category)
                || FailureAttributor.CATEGORY_DATA.equals(category)) {
            return new Verdict(DECISION_HANDOVER,
                    "「" + category + "」类失败是确定性原因（配置/脚本/数据），重试必然复现，"
                            + "直接转人工修正");
        }

        // 6) Plan-only 之类不算失败
        if (FailureAttributor.CATEGORY_PLAN_ONLY.equals(category)) {
            return new Verdict(DECISION_HANDOVER,
                    "plan-only 用例未实跑，不计失败；如属预期请从执行集剔除");
        }

        // 7) 判断不了就交人——不猜，也不假装成功
        return new Verdict(DECISION_HANDOVER,
                "失败原因未能归类（" + category + "），转人工看原始证据");
    }
}
