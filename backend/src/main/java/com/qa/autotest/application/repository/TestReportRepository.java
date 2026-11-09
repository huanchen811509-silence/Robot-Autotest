package com.qa.autotest.application.repository;

import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.ReportState;
import com.qa.autotest.domain.report.TestReport;
import com.qa.autotest.domain.report.TestReportExport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 测试报告仓储（RPTX-01）。渲染路径只读本仓储，不回查 run 表。 */
public interface TestReportRepository {

    Optional<TestReport> find(long id);

    List<TestReport> listByProject(long projectId);

    int maxVersion(long projectId, String runId);

    /**
     * 建草稿写入一行。
     *
     * <p>{@code sourceType} / {@code caseSetId} 是**创建意图**（v3.10.0）：写入后不再参与渲染，
     * 只供列表页展示来源。按用例集建的报告同样写解析出来的那一条 {@code runId}。
     */
    long insert(long projectId, String runId, ReportSourceType sourceType, Long caseSetId,
                String title, int version, ReportSnapshot snapshot);

    void freeze(long id, ReportSnapshot snapshot);

    void changeState(long id, ReportState state);

    long insertExport(long reportId, String format, String fileName, long sizeBytes, String watermark);

    List<TestReportExport> exports(long reportId);

    int purgeExportsBefore(Instant cutoff);

    void audit(long reportId, String action, String fromState, String toState, Long actorId);
}
