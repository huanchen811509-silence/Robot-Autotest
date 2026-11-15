package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v5.14.0 / W64-15：「与上次执行差异」映射的单一真值。
 *
 * <p>不连 DB：用 CompareService 的公开 record 构造输入，锁死两条口径——
 * ① 有基线时四类计数原样透出；② 无基线时**不许**把「所有当前 FAIL」当成「新增失败」。
 */
class ReportDiffTest {

    /** 无基线时 CompareService 仍可能给出 newFail>0（history 为 null 走 else 分支）。 */
    @Test
    void noBaselineNeverFabricatesNewFailures() {
        CompareService.CompareResult result = new CompareService.CompareResult(
                null, List.of(), new CompareService.CompareSummary(3, 0, 0, 0), false);
        EhtSections.Diff diff = EhtSections.Diff.from(result);
        assertFalse(diff.hasBaseline());
        assertNull(diff.baselineRunId());
        assertEquals(0, diff.newFail());
        assertEquals(0, diff.newPass());
        assertEquals(0, diff.bothFail());
        assertEquals(0, diff.notRun());
        assertEquals(diff, EhtSections.Diff.none());
    }

    @Test
    void baselineDiffCarriesFourCountsAndRunId() {
        CompareService.CompareBaseline baseline = new CompareService.CompareBaseline(
                "RUN-PREV", Instant.parse("2026-11-14T01:00:00Z"), false, "dut-01", "link-01");
        CompareService.CompareResult result = new CompareService.CompareResult(
                baseline, List.of(), new CompareService.CompareSummary(2, 1, 3, 4), false);
        EhtSections.Diff diff = EhtSections.Diff.from(result);
        assertTrue(diff.hasBaseline());
        assertEquals("RUN-PREV", diff.baselineRunId());
        assertEquals(2, diff.newFail());
        assertEquals(1, diff.newPass());
        assertEquals(3, diff.bothFail());
        assertEquals(4, diff.notRun());
    }

    @Test
    void nullResultIsExplicitNone() {
        EhtSections.Diff diff = EhtSections.Diff.from(null);
        assertFalse(diff.hasBaseline());
        assertEquals(0, diff.newFail());
    }

    /** 有基线且至少一条「新增失败」或「修复」时，报告才能声称识别到差异。 */
    @Test
    void atLeastOneChangeIsRecognizable() {
        CompareService.CompareBaseline baseline = new CompareService.CompareBaseline(
                "RUN-PREV", Instant.parse("2026-11-14T01:00:00Z"), false, "dut-01", "link-01");
        EhtSections.Diff diff = EhtSections.Diff.from(new CompareService.CompareResult(
                baseline, List.of(), new CompareService.CompareSummary(1, 1, 0, 0), false));
        assertTrue(diff.newFail() + diff.newPass() >= 1);
    }
}
