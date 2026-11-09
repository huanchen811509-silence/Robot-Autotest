package com.qa.autotest.application.report;

import com.qa.autotest.application.execution.FailureClusterService;
import com.qa.autotest.application.execution.FailureClusterView;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ReportRecord;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 报告速览（FR-3.1-07）。
 *
 * <p>数字全部来自报告详情（{@link ReportRecord}），失败 TopN 来自既有失败聚类；
 * 不新建表、不重新解析报告，保证速览与详情永远一致。
 */
@Service
public class ReportSummaryService {

    public record TopFailure(String name, int count) {
    }

    public record Summary(String conclusion, int total, int passed, int failed, int skipped,
                          double passRate, long durationMs, List<TopFailure> topFailures,
                          List<String> risks, Instant generatedAt) {
    }

    private static final int TOP_LIMIT = 5;

    private final ReportRepository reports;
    private final FailureClusterService clusters;
    private final AccessControlService access;

    public ReportSummaryService(ReportRepository reports, FailureClusterService clusters,
                                AccessControlService access) {
        this.reports = reports;
        this.clusters = clusters;
        this.access = access;
    }

    public Summary summarize(long projectId, String runId) {
        access.bindProject(projectId);
        ReportRecord report = reports.find(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        FailureClusterView clusterView = clusters.cluster(projectId, runId);

        int total = report.passCount() + report.failCount() + report.skipCount();
        double passRate = total == 0 ? 0.0
                : Math.round((double) report.passCount() * 1000 / total) / 10.0;
        String conclusion = total == 0 ? "NO_DATA"
                : report.failCount() == 0 ? "PASSED" : "FAILED";

        List<TopFailure> topFailures = new ArrayList<>();
        if (clusterView != null) {
            clusterView.classes().stream()
                    .flatMap(group -> group.clusters().stream())
                    .sorted(Comparator.comparingInt(FailureClusterView.Cluster::count).reversed())
                    .limit(TOP_LIMIT)
                    .forEach(cluster -> topFailures.add(
                            new TopFailure(cluster.key(), cluster.count())));
        }

        List<String> risks = risks(report, clusterView, total);
        return new Summary(conclusion, total, report.passCount(), report.failCount(),
                report.skipCount(), passRate, durationMs(report), topFailures, risks,
                Instant.now());
    }

    private static long durationMs(ReportRecord report) {
        if (report.startedAt() == null || report.finishedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(report.startedAt(), report.finishedAt()).toMillis());
    }

    private static List<String> risks(ReportRecord report, FailureClusterView clusterView, int total) {
        List<String> risks = new ArrayList<>();
        if (!report.metadataMissing().isEmpty()) {
            risks.add("报告元数据缺失：" + String.join("、", report.metadataMissing()));
        }
        if (!report.exportable()) {
            risks.add("报告当前不可对外交付");
        }
        if (report.state() != null && !"finished".equals(report.state())
                && !"failed".equals(report.state()) && !"canceled".equals(report.state())) {
            risks.add("运行尚未结束，报告数字可能不完整");
        }
        if (clusterView != null) {
            for (FailureClusterView.ClassGroup group : clusterView.classes()) {
                if ("ENV".equals(group.failureClass()) || "INFRA".equals(group.failureClass())) {
                    if (group.count() > 0) {
                        risks.add(group.failureClass() + " 类失败 " + group.count()
                                + " 条，建议先排查环境/基础设施");
                    }
                }
            }
        }
        if (total == 0) {
            risks.add("没有可统计的用例结果");
        }
        return risks;
    }
}
