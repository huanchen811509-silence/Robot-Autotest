package com.qa.autotest.domain.report;

import java.util.List;

/**
 * 测试用例节点（RPT-01 解析结果的最小结构）。
 *
 * <p>RPT-02 追加日志定位：{@code logOffset} 为该用例首条消息在 output.xml 中的序号，
 * {@code logSpan} 为 {@code 'start:end'} 左闭右开区间。日志正文不进库（DEV-PLAN §2.3），
 * 报告页按偏移从文件流式读取。无消息时 offset 为 -1、span 为 null。
 *
 * @param name           用例名
 * @param status         PASS/FAIL/SKIP/UNKNOWN
 * @param durationMs     耗时（毫秒）
 * @param failureMessage 失败消息摘要，无失败时为 null
 * @param keywords       顶层关键字
 * @param logOffset      首条消息序号（无消息为 -1）
 * @param logSpan        日志序号区间 {@code 'start:end'}，无消息为 null
 * @param retryCount     复测轮数（v5.6.0 / FR-v5.6-24）：最终结论之前实际又跑了几轮，0 表示未复测
 * @param flaky          抖动标记（首轮失败、复测通过）；为 true 时不进失败数，单列统计
 */
public record CaseNode(String name,
                       String status,
                       long durationMs,
                       String failureMessage,
                       List<KeywordNode> keywords,
                       long logOffset,
                       String logSpan,
                       String commandText,
                       String commandSource,
                       Integer commandExitCode,
                       int retryCount,
                       boolean flaky) {

    /** 无命令时的显式取值：报告页据此显示「本用例未下发外部命令」。 */
    public static final String NO_COMMAND = "NONE";

    public CaseNode {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        // 三列都补齐成显式值：NULL 会被读成「还没采集」，而 NONE 是「本来就没有」。
        // 历史数据才会是 NULL（v3.9.0 之前入库的行），新写入一律显式。
        commandText = commandText == null || commandText.isBlank() ? NO_COMMAND : commandText;
        commandSource = commandSource == null || commandSource.isBlank() ? NO_COMMAND : commandSource;
    }

    /** 兼容 RPT-01 的五参写法（无日志定位时使用）。 */
    public CaseNode(String name, String status, long durationMs,
                    String failureMessage, List<KeywordNode> keywords) {
        this(name, status, durationMs, failureMessage, keywords, -1L, null,
                NO_COMMAND, NO_COMMAND, null, 0, false);
    }

    /** 兼容 RPT-02 的七参写法（v3.9.0 之前没有命令三列）。 */
    public CaseNode(String name, String status, long durationMs, String failureMessage,
                    List<KeywordNode> keywords, long logOffset, String logSpan) {
        this(name, status, durationMs, failureMessage, keywords, logOffset, logSpan,
                NO_COMMAND, NO_COMMAND, null, 0, false);
    }

    /** 兼容 v3.9.0 的十参写法（未复测）。 */
    public CaseNode(String name, String status, long durationMs, String failureMessage,
                    List<KeywordNode> keywords, long logOffset, String logSpan,
                    String commandText, String commandSource, Integer commandExitCode) {
        this(name, status, durationMs, failureMessage, keywords, logOffset, logSpan,
                commandText, commandSource, commandExitCode, 0, false);
    }
}
