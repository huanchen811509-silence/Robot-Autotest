package com.qa.autotest.domain.report;

import java.time.Instant;

/**
 * 测试报告实体（RPTX-01）。snapshot_* 三列即「冻结后自包含」的全部依据。
 *
 * <p>{@code sourceType} / {@code caseSetId}（v3.10.0）只描述**创建意图**：无论按执行记录
 * 还是按用例集建，最终都解析成一条 {@code runId}，渲染与导出只走快照、不看这两列。
 * 因此这两列缺失或取值异常都不会影响历史报告的可读性——它们只服务于列表页的来源展示。
 */
public record TestReport(long id,
                         long projectId,
                         String runId,
                         ReportSourceType sourceType,
                         Long caseSetId,
                         String title,
                         ReportState state,
                         int version,
                         String snapshotHash,
                         String snapshotJson,
                         String snapshotHtml,
                         String sourceSummary,
                         Instant createdAt,
                         Instant frozenAt) {
}
