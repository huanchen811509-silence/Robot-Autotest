package com.qa.autotest.application.execution;

import com.qa.autotest.application.report.FailureAttributor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败裁决（FR-v5.2-03 / 验收 B8/B9 / W52-06）。
 *
 * <p>这组用例的真正目的是钉住<b>判定顺序</b>：
 * <ol>
 *   <li>连续同类失败止损要压过「环境类可重试」——否则环境彻底坏掉时会重试到天亮；</li>
 *   <li>断言失败只重试一次，不给无限预算——否则就是在刷通过率；</li>
 *   <li>配置/脚本/数据类是确定性原因，重试必然复现，直接转人工。</li>
 * </ol>
 */
class RunVerdictPolicyTest {

    private static RunVerdictPolicy.Input input(String category, int attempt, int max,
                                                int consecutive, int passRate, int minRate) {
        return new RunVerdictPolicy.Input(category, attempt, max, consecutive, 5, passRate, minRate);
    }

    @Test
    void 环境类首跑给重试() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ENV, 1, 2, 1, 80, 0));
        assertTrue(verdict.retry(), verdict.decision());
        assertTrue(verdict.reason().contains("环境"), verdict.reason());
    }

    @Test
    void 环境类用尽预算转人工() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ENV, 2, 2, 1, 80, 0));
        assertTrue(verdict.handover(), verdict.decision());
        assertTrue(verdict.reason().contains("不进产品通过率分母"), verdict.reason());
    }

    @Test
    void 断言失败只重试一次() {
        RunVerdictPolicy.Verdict first = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ASSERT, 1, 5, 1, 80, 0));
        assertTrue(first.retry(), first.decision());
        RunVerdictPolicy.Verdict second = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ASSERT, 2, 5, 2, 80, 0));
        assertTrue(second.handover(),
                "断言失败已重试过一次就转人工，否则等于用重试刷通过率");
    }

    @Test
    void 连续性止损压过环境类可重试() {
        // 环境类本可重试，但已经连续 5 条同类失败 → 必须停
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ENV, 1, 3, 5, 80, 0));
        assertTrue(verdict.stop(), verdict.decision());
        assertTrue(verdict.reason().contains("5"), verdict.reason());
        assertTrue(verdict.reason().contains("止损"), verdict.reason());
    }

    @Test
    void 通过率跌破下限止损() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_ASSERT, 1, 1, 1, 20, 50));
        assertTrue(verdict.stop(), verdict.decision());
        assertTrue(verdict.reason().contains("20%"), verdict.reason());
    }

    @Test
    void 配置脚本数据类不重试直接转人工() {
        for (String category : new String[] {
                FailureAttributor.CATEGORY_CONFIG,
                FailureAttributor.CATEGORY_SCRIPT,
                FailureAttributor.CATEGORY_DATA}) {
            RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                    input(category, 1, 3, 1, 80, 0));
            assertTrue(verdict.handover(), category + " → " + verdict.decision());
            assertTrue(verdict.reason().contains("确定性"), verdict.reason());
        }
    }

    @Test
    void 未分类失败不猜_一律转人工() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                new RunVerdictPolicy.Input(null, 1, 3, 1, 0, 80, 0));
        assertTrue(verdict.handover(), verdict.decision());
        assertEquals(RunVerdictPolicy.DECISION_HANDOVER, verdict.decision());
    }

    @Test
    void 阈值未配时时连续失败也不止损() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                new RunVerdictPolicy.Input(FailureAttributor.CATEGORY_ENV, 1, 3, 99, 0, 80, 0));
        assertTrue(verdict.retry(), "止损阈值 0 = 不启用，不能被「连续 99 条」触发");
    }

    @Test
    void plan_only_不算失败() {
        RunVerdictPolicy.Verdict verdict = RunVerdictPolicy.decide(
                input(FailureAttributor.CATEGORY_PLAN_ONLY, 1, 3, 1, 100, 0));
        assertTrue(verdict.handover(), verdict.decision());
        assertTrue(verdict.reason().contains("plan-only"), verdict.reason());
    }
}
