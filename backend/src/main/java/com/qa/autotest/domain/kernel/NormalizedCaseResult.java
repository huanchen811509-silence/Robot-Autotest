package com.qa.autotest.domain.kernel;

/**
 * 归一后的单条用例结论。nodeId 对 pytest 是 nodeid（含参数化后缀），对 RF 是 longname。
 *
 * <p>v3.9.0（FR-v3.9-02）追加命令三列：{@code commandText} 为下发的命令原文（已脱敏），
 * 无命令时显式 {@code NONE}；{@code commandSource} 记录取材方式；{@code commandExitCode}
 * 为真实退出码，未知为 {@code null}（不写 0，0 是"成功"的有效取值）。
 */
public record NormalizedCaseResult(String nodeId, String name, CaseStatus status,
                                   double durationSeconds, String message,
                                   String commandText, String commandSource,
                                   Integer commandExitCode) {

    public static final String NO_COMMAND = "NONE";

    public NormalizedCaseResult {
        commandText = commandText == null || commandText.isBlank() ? NO_COMMAND : commandText;
        commandSource = commandSource == null || commandSource.isBlank() ? NO_COMMAND : commandSource;
    }

    /** 兼容 v2.7 的五参写法：没有命令信息时显式记 NONE。 */
    public NormalizedCaseResult(String nodeId, String name, CaseStatus status,
                                double durationSeconds, String message) {
        this(nodeId, name, status, durationSeconds, message, NO_COMMAND, NO_COMMAND, null);
    }
}
