package com.qa.autotest.domain.kernel;

/**
 * 归一后的单条用例结论。nodeId 对 pytest 是 nodeid（含参数化后缀），对 RF 是 longname。
 *
 * <p>v3.9.0（FR-v3.9-02）追加命令三列：{@code commandText} 为下发的命令原文（已脱敏），
 * 无命令时显式 {@code NONE}；{@code commandSource} 记录取材方式；{@code commandExitCode}
 * 为真实退出码，未知为 {@code null}（不写 0，0 是"成功"的有效取值）。
 *
 * <p>v5.6.0（FR-v5.6-24）追加重试两列：
 * <ul>
 *   <li>{@code retryCount}：最终结论之前**实际又跑了几轮**（不是配置允许几轮）。
 *       配置值是意图，会随请求变；结果是事实，两者不能混用一个字段。</li>
 *   <li>{@code flaky}：首轮失败、复测通过。这是**判定**而不是次数——把判定留给每个
 *       消费方自己推断，前端、报表、对外口径就会各算一遍，同一批数据得出不同的抖动数。</li>
 * </ul>
 *
 * <p>两者默认 {@code 0 / false}：未复测的运行与历史结果天然是这个值，与事实一致。
 */
public record NormalizedCaseResult(String nodeId, String name, CaseStatus status,
                                   double durationSeconds, String message,
                                   String commandText, String commandSource,
                                   Integer commandExitCode, int retryCount, boolean flaky) {

    public static final String NO_COMMAND = "NONE";

    public NormalizedCaseResult {
        commandText = commandText == null || commandText.isBlank() ? NO_COMMAND : commandText;
        commandSource = commandSource == null || commandSource.isBlank() ? NO_COMMAND : commandSource;
        retryCount = Math.max(0, retryCount);
        // 抖动的前提是「末轮通过」：首轮失败但复测仍失败，是稳定失败而不是抖动。
        // 在这里收口一次，避免调用方把 flaky 置 true 却留着 FAIL 状态。
        flaky = flaky && status == CaseStatus.PASS;
    }

    /** 兼容 v2.7 的五参写法：没有命令信息时显式记 NONE。 */
    public NormalizedCaseResult(String nodeId, String name, CaseStatus status,
                                double durationSeconds, String message) {
        this(nodeId, name, status, durationSeconds, message, NO_COMMAND, NO_COMMAND, null, 0, false);
    }

    /** 兼容 v3.9.0 的八参写法：未复测。 */
    public NormalizedCaseResult(String nodeId, String name, CaseStatus status,
                                double durationSeconds, String message,
                                String commandText, String commandSource,
                                Integer commandExitCode) {
        this(nodeId, name, status, durationSeconds, message,
                commandText, commandSource, commandExitCode, 0, false);
    }
}
