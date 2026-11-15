package com.qa.autotest.domain.report;

import java.time.Instant;
import java.util.List;

/**
 * 报告结果概览（PRJ-04，PRD FR-v0.2-53）。
 *
 * <p>与 OVW-01 的总览互补：总览回答「有多大规模」，这里回答「跑出来的结果好不好、哪些老坏、用例资产有没有被用起来」。
 *
 * <p>三条口径（改口径要先改 {@code docs/plan/多项目与体验基线-评估与设计-20261104.md} §6.5）：
 * <ul>
 *   <li>只统计 <b>已完成</b>的运行（{@code run_status IN ('finished','failed')}）；排队/运行中/取消不进任何分母。</li>
 *   <li>通过率是用例级（{@code test_result} 明细），单位基点；<b>无明细</b>为 {@code null}（「没跑过」），
 *       而「有明细但全 SKIP」按 OVW-01 现口径（skip 计入分母）得 0——两者不可混为一谈。</li>
 *   <li>「从未执行」按 {@code test_case.case_code = test_result.case_key} 精确匹配统计，是<b>覆盖率缺口的上界</b>，
 *       不承诺识别「换个前缀但其实是同一条用例」的情况。</li>
 * </ul>
 */
public record ReportDigest(
        long projectId,
        Instant generatedAt,
        int windowDays,
        List<ReportRow> recentReports,
        List<FailedCase> topFailedCases,
        Coverage coverage,
        List<PassPoint> passRateTrend) {

    public ReportDigest {
        recentReports = recentReports == null ? List.of() : List.copyOf(recentReports);
        topFailedCases = topFailedCases == null ? List.of() : List.copyOf(topFailedCases);
        passRateTrend = passRateTrend == null ? List.of() : List.copyOf(passRateTrend);
        coverage = coverage == null ? Coverage.empty() : coverage;
    }

    /** 一份已完成运行的读数；{@code total} 为 0 时回落到 pass+fail+skip。 */
    /** {@code suiteCount} 为 null = 历史 run 未采集，界面显示「—」而非 0。 */
    public record ReportRow(String runId, String state, Instant finishedAt,
                            int total, int pass, int fail, int skip, Integer passRateBp,
                            Integer suiteCount) {
    }

    /** 失败用例一条：窗口内失败次数 + 最近一次失败时间。 */
    public record FailedCase(String caseKey, int failures, Instant lastFailedAt) {
    }

    /** 覆盖率：启用用例数 / 命中的 / 从未出现过的。 */
    public record Coverage(int caseTotal, int covered, int neverExecuted) {
        public static Coverage empty() {
            return new Coverage(0, 0, 0);
        }
    }

    /**
     * 趋势点：一次已完成运行一个点（不做按天折叠，运行少时按天会把 5 次压成 1 个点）。
     * {@code fail} 带出来是给前端上色用（有失败 → 红），不必再让前端猜阈值。
     */
    public record PassPoint(String runId, Instant finishedAt, Integer passRateBp, int fail) {
    }

    /**
     * 通过率统一从 OVW-01 的公式算，避免两处各写一份后漂移。
     * 抽成静态方法是给「无样本 → null」这条留单测入口。
     */
    public static Integer passRateBp(int pass, int fail, int skip) {
        return com.qa.autotest.domain.overview.ProjectOverview.passRateBp(pass, fail, skip);
    }

    /** 覆盖率派生：差不得为负（结果里可能有用例库没有的临时 key）。 */
    public static Coverage coverage(int caseTotal, int covered) {
        int total = Math.max(0, caseTotal);
        int hit = Math.max(0, Math.min(covered, total));
        return new Coverage(total, hit, total - hit);
    }
}
