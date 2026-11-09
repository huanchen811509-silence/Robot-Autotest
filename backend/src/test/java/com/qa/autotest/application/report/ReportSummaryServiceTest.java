package com.qa.autotest.application.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.execution.FailureClusterService;
import com.qa.autotest.application.execution.FailureClusterView;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 报告速览：数字必须与详情一致，失败 TopN 来自既有聚类，不重算。 */
class ReportSummaryServiceTest {

    private static final long PROJECT = 1L;
    private static final String RUN = "RUN-1";

    private ReportRepository reports;
    private FailureClusterService clusters;
    private ReportSummaryService service;

    @BeforeEach
    void setUp() {
        reports = mock(ReportRepository.class);
        clusters = mock(FailureClusterService.class);
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        service = new ReportSummaryService(reports, clusters, access);
    }

    @Test
    @DisplayName("有失败：结论 FAILED，总数/通过率/耗时与详情一致，Top 失败按数量降序")
    void 失败报告() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", false, List.of("OWNER", "BRANCH"), 8, 2, 1)));
        when(clusters.cluster(PROJECT, RUN)).thenReturn(new FailureClusterView(
                RUN, 7, "finished", "ASSERT", List.of(
                new FailureClusterView.ClassGroup("ASSERT", 5, 0.7, List.of(
                        new FailureClusterView.Cluster("assert-a", 3, 0.4, List.of(), List.of()),
                        new FailureClusterView.Cluster("assert-b", 2, 0.3, List.of(), List.of()))),
                new FailureClusterView.ClassGroup("ENV", 2, 0.3, List.of(
                        new FailureClusterView.Cluster("env-x", 2, 0.3, List.of(), List.of()))))));

        ReportSummaryService.Summary summary = service.summarize(PROJECT, RUN);

        assertThat(summary.conclusion()).isEqualTo("FAILED");
        assertThat(summary.total()).isEqualTo(11);
        assertThat(summary.passed()).isEqualTo(8);
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.passRate()).isEqualTo(72.7, within(0.01));
        assertThat(summary.durationMs()).isEqualTo(5_000L);
        assertThat(summary.topFailures()).extracting(ReportSummaryService.TopFailure::name)
                .containsExactly("assert-a", "assert-b", "env-x");
        assertThat(summary.risks())
                .anyMatch(risk -> risk.contains("OWNER"))
                .anyMatch(risk -> risk.contains("不可对外交付"))
                .anyMatch(risk -> risk.startsWith("ENV"));
    }

    @Test
    @DisplayName("全部通过：结论 PASSED，通过率 100%")
    void 通过报告() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", true, List.of(), 3, 0, 0)));

        ReportSummaryService.Summary summary = service.summarize(PROJECT, RUN);

        assertThat(summary.conclusion()).isEqualTo("PASSED");
        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.passRate()).isEqualTo(100.0, within(0.01));
        assertThat(summary.risks()).isEmpty();
        assertThat(summary.topFailures()).isEmpty();
    }

    @Test
    @DisplayName("没有用例结果：结论 NO_DATA，并给出风险提示")
    void 无数据报告() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", true, List.of(), 0, 0, 0)));

        ReportSummaryService.Summary summary = service.summarize(PROJECT, RUN);

        assertThat(summary.conclusion()).isEqualTo("NO_DATA");
        assertThat(summary.total()).isZero();
        assertThat(summary.passRate()).isZero();
        assertThat(summary.risks()).anyMatch(risk -> risk.contains("没有可统计"));
    }

    @Test
    @DisplayName("报告不存在 → 404")
    void 报告不存在() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summarize(PROJECT, RUN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static ReportRecord report(String state, boolean exportable,
                                       List<String> metadataMissing, int pass, int fail,
                                       int skip) {
        Instant started = Instant.parse("2026-11-08T10:00:00Z");
        return new ReportRecord(PROJECT, RUN, state, exportable, metadataMissing, Map.of(),
                pass, fail, skip, started, started.plusSeconds(5));
    }
}
