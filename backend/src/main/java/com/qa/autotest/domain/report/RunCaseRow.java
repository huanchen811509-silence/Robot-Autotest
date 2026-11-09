package com.qa.autotest.domain.report;

/**
 * 套件树重建用的扁平用例行（RPT-02）。
 *
 * <p>v3.9.0 追加命令三列（FR-v3.9-02 / FR-v3.9-03）：报告页的「下发命令」列直接读这里，
 * 不再回头去翻日志正文。{@code commandText} 为 {@link CaseNode#NO_COMMAND} 表示本用例
 * 本来就没下发外部命令（与「历史数据还没采集」区分：后者是 {@code null}）。
 *
 * @param caseKey           连接键：suitePath.caseName
 * @param verdict           PASS/FAIL/SKIP/UNKNOWN
 * @param durationMs        耗时（毫秒）
 * @param failureMsg        失败摘要，无失败为 null
 * @param commandText       命令原文，无命令为 {@code NONE}，历史行为 null
 * @param commandSource     命令来源：RF_KEYWORD / PYTEST_MARK / STDOUT / NONE，历史行为 null
 * @param commandExitCode   退出码，未知为 null
 */
public record RunCaseRow(String caseKey, String verdict, long durationMs, String failureMsg,
                         String commandText, String commandSource, Integer commandExitCode) {

    /** 兼容 RPT-02 的四参写法（v3.9.0 之前的调用方与测试伪造）。 */
    public RunCaseRow(String caseKey, String verdict, long durationMs, String failureMsg) {
        this(caseKey, verdict, durationMs, failureMsg, null, null, null);
    }
}
