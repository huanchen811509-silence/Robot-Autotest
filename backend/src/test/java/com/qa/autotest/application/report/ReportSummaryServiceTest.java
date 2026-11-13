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
    private com.qa.autotest.application.repository.RunRepository runs;
    private com.qa.autotest.application.repository.ReportReadRepository read;
    private ReportSummaryService service;

    @BeforeEach
    void setUp() {
        reports = mock(ReportRepository.class);
        clusters = mock(FailureClusterService.class);
        runs = mock(com.qa.autotest.application.repository.RunRepository.class);
        read = mock(com.qa.autotest.application.repository.ReportReadRepository.class);
        org.mockito.Mockito.when(read.listCases(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        // 默认「只有本轮」：复现率三项为 null，正是无历史时的正确口径
        when(runs.page(PROJECT, null, 1, 10)).thenReturn(
                new com.qa.autotest.domain.common.PageResult<>(
                        List.of(run("RUN-1", "2026-11-08T10:00:00Z")), 1, 1, 10));
        service = new ReportSummaryService(reports, clusters, access, runs, read);
    }

    @Test
    @DisplayName("FR-v5.6-26：无历史时三项显式 null，不能写 0 冒充「没复现过」")
    void 无历史三项为null() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", true, List.of(), 1, 1, 0)));
        when(clusters.cluster(PROJECT, RUN)).thenReturn(oneCluster());

        ReportSummaryService.TopFailure top = service.summarize(PROJECT, RUN)
                .topFailures().get(0);

        assertThat(top.firstSeenAt()).isNull();
        assertThat(top.consecutiveRuns()).isNull();
        assertThat(top.reproRate()).isNull();
    }

    @Test
    @DisplayName("FR-v5.6-26：最近三轮都出现同一簇 → 连续 3 轮、复现率 1.0、首现取最早那轮")
    void 连续出现与首现() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", true, List.of(), 1, 1, 0)));
        when(runs.page(PROJECT, null, 1, 10)).thenReturn(
                new com.qa.autotest.domain.common.PageResult<>(List.of(
                        run("R-3", "2026-11-10T10:00:00Z"),
                        run("R-2", "2026-11-09T10:00:00Z"),
                        run("R-1", "2026-11-08T10:00:00Z")), 3, 1, 10));
        when(clusters.cluster(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(oneCluster());

        ReportSummaryService.TopFailure top = service.summarize(PROJECT, RUN)
                .topFailures().get(0);

        assertThat(top.consecutiveRuns()).isEqualTo(3);
        assertThat(top.reproRate()).isEqualTo(1.0);
        assertThat(top.firstSeenAt()).isEqualTo(java.time.Instant.parse("2026-11-08T10:00:00Z"));
    }

    @Test
    @DisplayName("FR-v5.6-26：最新一轮没有该簇时连续轮数为 0（是 0，不是 null）")
    void 已修复簇连续为零() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", true, List.of(), 1, 1, 0)));
        when(runs.page(PROJECT, null, 1, 10)).thenReturn(
                new com.qa.autotest.domain.common.PageResult<>(List.of(
                        run("R-2", "2026-11-10T10:00:00Z"),
                        run("R-1", "2026-11-09T10:00:00Z")), 2, 1, 10));
        when(clusters.cluster(PROJECT, RUN)).thenReturn(oneCluster());
        when(clusters.cluster(PROJECT, "R-2"))
                .thenReturn(new FailureClusterView("R-2", 0, "finished", null, List.of()));
        when(clusters.cluster(PROJECT, "R-1")).thenReturn(oneCluster());

        ReportSummaryService.TopFailure top = service.summarize(PROJECT, RUN)
                .topFailures().get(0);

        assertThat(top.consecutiveRuns()).isZero();
        assertThat(top.reproRate()).isEqualTo(0.5);
        assertThat(top.firstSeenAt()).isEqualTo(java.time.Instant.parse("2026-11-09T10:00:00Z"));
    }

    private static FailureClusterView oneCluster() {
        return new FailureClusterView("R", 1, "failed", "ASSERT", List.of(
                new FailureClusterView.ClassGroup("ASSERT", 1, 1.0, List.of(
                        new FailureClusterView.Cluster("assert-a", "PFC 使能优先级期望 3 实际 5",
                                1, 1.0, List.of(), List.of())))));
    }

    private static com.qa.autotest.domain.execution.RunRecord run(String runId, String finishedAt) {
        java.time.Instant finished = java.time.Instant.parse(finishedAt);
        return new com.qa.autotest.domain.execution.RunRecord(1L, runId, PROJECT, null,
                com.qa.autotest.domain.execution.RunState.FINISHED, 5, 1, 1, 1, 0, 1,
                null, null, null, "/tmp/a", finished.minusSeconds(60), finished.minusSeconds(60),
                finished, null, null);
    }

    @Test
    @DisplayName("有失败：结论 FAILED，总数/通过率/耗时与详情一致，Top 失败按数量降序")
    void 失败报告() {
        when(reports.find(PROJECT, RUN)).thenReturn(Optional.of(report(
                "finished", false, List.of("OWNER", "BRANCH"), 8, 2, 1)));
        when(clusters.cluster(PROJECT, RUN)).thenReturn(new FailureClusterView(
                RUN, 7, "finished", "ASSERT", List.of(
                new FailureClusterView.ClassGroup("ASSERT", 5, 0.7, List.of(
                        new FailureClusterView.Cluster("assert-a", "PFC 使能优先级期望 3 实际 5",
                                3, 0.4, List.of(), List.of()),
                        new FailureClusterView.Cluster("assert-b", "VLAN 隔离未生效：vlan=100",
                                2, 0.3, List.of(), List.of()))),
                new FailureClusterView.ClassGroup("ENV", 2, 0.3, List.of(
                        new FailureClusterView.Cluster("env-x", "连接 DUT 超时 10.0.0.1:22",
                                2, 0.3, List.of(), List.of()))))));

        ReportSummaryService.Summary summary = service.summarize(PROJECT, RUN);

        assertThat(summary.conclusion()).isEqualTo("FAILED");
        assertThat(summary.total()).isEqualTo(11);
        assertThat(summary.passed()).isEqualTo(8);
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.passRate()).isEqualTo(72.7, within(0.01));
        assertThat(summary.durationMs()).isEqualTo(5_000L);
        // FR-v5.6-22：展示名是可读原文，簇指纹仍单独返回，两者不得混用。
        assertThat(summary.topFailures()).extracting(ReportSummaryService.TopFailure::display)
                .containsExactly("PFC 使能优先级期望 3 实际 5", "VLAN 隔离未生效：vlan=100",
                        "连接 DUT 超时 10.0.0.1:22");
        assertThat(summary.topFailures()).extracting(ReportSummaryService.TopFailure::clusterKey)
                .containsExactly("assert-a", "assert-b", "env-x");
        assertThat(summary.topFailures()).extracting(ReportSummaryService.TopFailure::name)
                .containsExactly("PFC 使能优先级期望 3 实际 5", "VLAN 隔离未生效：vlan=100",
                        "连接 DUT 超时 10.0.0.1:22");
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
