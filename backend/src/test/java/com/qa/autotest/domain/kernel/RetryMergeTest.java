package com.qa.autotest.domain.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多轮合并（FR-v5.6-24）：抖动判定只能有一处口径。
 *
 * <p>这些用例断言的都是「对外能不能说通过」这件事，所以每条都同时校验
 * 状态、{@code flaky}、{@code retryCount} 三者——只看状态会漏掉「通过但是抖的」。
 */
class RetryMergeTest {

    private static NormalizedCaseResult one(String nodeId, CaseStatus status, String message) {
        return new NormalizedCaseResult(nodeId, nodeId, status, 1.0, message);
    }

    private static NormalizedRun round(NormalizedCaseResult... cases) {
        return new NormalizedRun("pytest", "R1", List.of(cases), Map.of("RUN_ID", "R1"));
    }

    @Test
    @DisplayName("首轮失败、复测通过：末轮 PASS + flaky + retryCount=1")
    void flakyWhenFirstRoundFails() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(
                List.of(one("t.py::a", CaseStatus.FAIL, "首轮断言失败")),
                List.of(one("t.py::a", CaseStatus.PASS, null))));

        assertEquals(1, merged.size());
        assertEquals(CaseStatus.PASS, merged.get(0).status());
        assertTrue(merged.get(0).flaky(), "首轮失败末轮通过必须标抖动");
        assertEquals(1, merged.get(0).retryCount());
        assertEquals(null, merged.get(0).message(), "通过后不该残留失败消息");
    }

    @Test
    @DisplayName("各轮都失败：末轮结论 + 不标抖动（稳定失败不是抖动）")
    void stableFailureIsNotFlaky() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(
                List.of(one("t.py::a", CaseStatus.FAIL, "第一次")),
                List.of(one("t.py::a", CaseStatus.FAIL, "第二次")),
                List.of(one("t.py::a", CaseStatus.ERROR, "第三次"))));

        assertEquals(CaseStatus.ERROR, merged.get(0).status());
        assertFalse(merged.get(0).flaky());
        assertEquals(2, merged.get(0).retryCount());
        assertEquals("第三次", merged.get(0).message(), "消息要取最后一次，第一次的已经过期");
    }

    @Test
    @DisplayName("首轮通过不被复测改回失败：先到的通过更接近事实")
    void passIsNotDowngraded() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(
                List.of(one("t.py::a", CaseStatus.PASS, null)),
                List.of(one("t.py::a", CaseStatus.FAIL, "复测炸了"))));

        assertEquals(CaseStatus.PASS, merged.get(0).status());
        assertFalse(merged.get(0).flaky(), "首轮就通过的不是抖动");
    }

    @Test
    @DisplayName("只在复测出现的用例不能丢：记该轮结论 + 轮次")
    void caseOnlyInRetryIsKept() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(
                List.of(one("t.py::a", CaseStatus.PASS, null)),
                List.of(one("t.py::b", CaseStatus.PASS, null))));

        assertEquals(2, merged.size());
        assertEquals("t.py::b", merged.get(1).nodeId());
        assertEquals(1, merged.get(1).retryCount());
        assertFalse(merged.get(1).flaky());
    }

    @Test
    @DisplayName("单轮原样返回，顺序不变")
    void singleRoundKeepsOrder() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(List.of(
                one("t.py::b", CaseStatus.PASS, null),
                one("t.py::a", CaseStatus.FAIL, "x"))));

        assertEquals(List.of("t.py::b", "t.py::a"),
                merged.stream().map(NormalizedCaseResult::nodeId).toList());
    }

    @Test
    @DisplayName("复测仍被跳过不算通过：失败不能被「跳过」掩盖")
    void skipOnRetryKeepsFailure() {
        List<NormalizedCaseResult> merged = RetryMerge.mergeCases(List.of(
                List.of(one("t.py::a", CaseStatus.FAIL, "环境没起来")),
                List.of(one("t.py::a", CaseStatus.SKIP, "跳过"))));

        assertEquals(CaseStatus.FAIL, merged.get(0).status(), "跳过的复测不能把失败变没");
        assertFalse(merged.get(0).flaky());
        assertEquals(1, merged.get(0).retryCount());
        assertEquals("环境没起来", merged.get(0).message());
    }

    @Test
    @DisplayName("flaky 只在末轮 PASS 时成立：构造矛盾输入会被收口")
    void flakyRequiresPass() {
        NormalizedCaseResult contradictory = new NormalizedCaseResult("t.py::a", "A",
                CaseStatus.FAIL, 1.0, "still failing", "NONE", "NONE", null, 2, true);
        assertFalse(contradictory.flaky(), "FAIL 状态下不允许标抖动");
    }
}
