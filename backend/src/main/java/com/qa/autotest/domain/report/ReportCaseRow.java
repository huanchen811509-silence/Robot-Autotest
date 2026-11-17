package com.qa.autotest.domain.report;

/**
 * 报告明细行（v5.22.0 / FR-v5.22-02，W72-03）：冻结快照的 {@code cases} 数组元素。
 *
 * <p>为什么明细要进快照、而不是导出时回查 {@code test_result}：
 * 报告的生命周期比 run 长（run 会被保留策略清理），冻结件必须能独立交付；
 * 回查还会让「导出过两次，结果不一样」成为可能——重跑覆盖 test_result 就变了。
 *
 * <p>{@code commandText} 只在**内部**导出里出列：C5 §3 明确禁止对外文件出现命令原文。
 * 快照里存它是因为快照属内部数据，导出时按 mode 裁剪列，而不是在冻结时就把事实删掉。
 *
 * @param caseCode    用例编号（{@code test_case.case_code}；无台账时回退 {@code case_key}）
 * @param name        用例标题
 * @param verdict     结论（PASS / FAIL / SKIP / …）
 * @param durationMs  耗时（毫秒，未知为 null）
 * @param failureMsg  失败信息（对外导出前过脱敏）
 * @param commandText 下发命令原文（仅内部导出）
 */
public record ReportCaseRow(
        String caseCode,
        String name,
        String verdict,
        Long durationMs,
        String failureMsg,
        String commandText) {
}
