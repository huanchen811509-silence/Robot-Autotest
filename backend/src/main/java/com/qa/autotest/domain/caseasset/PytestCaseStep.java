package com.qa.autotest.domain.caseasset;

/**
 * pytest 步骤/命令证据行（v5.1.0 / FR-v5.1-08）。
 *
 * <p>与 {@code KeywordRow} 并列但<b>不共用</b>：RF 关键字树是「名字 + 状态 + 深度」，
 * pytest 证据行是「一行日志 = 一条记录」，带明细原文、命令原文与附件相对路径。
 * 混表会让 RF 报告页把 pytest 明细行当关键字渲染。
 */
public record PytestCaseStep(long id,
                             long runId,
                             Long testResultId,
                             String nodeId,
                             String caseKey,
                             int seq,
                             String kind,
                             String label,
                             String content,
                             String status,
                             String commandId,
                             String stdoutPath,
                             long logLine,
                             String source) {

    /** 入库前的形态：{@code id} 由库生成，故不参与构造。 */
    public static PytestCaseStep draft(long runId, Long testResultId, String nodeId, String caseKey,
                                       int seq, String kind, String label, String content,
                                       String status, String commandId, String stdoutPath,
                                       long logLine, String source) {
        return new PytestCaseStep(0L, runId, testResultId, nodeId, caseKey, seq, kind,
                label == null ? "" : label, content, status, commandId, stdoutPath, logLine,
                source == null ? "JUNIT" : source);
    }
}
