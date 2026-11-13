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

    /**
     * Top 失败项（v5.6.0 / FR-v5.6-22、26）。
     *
     * @param name            兼容字段，与 {@code display} 同值。v5.6.0 之前这里是簇指纹，
     *                        界面因此显示成 `AssertionError: <n> != <n>…a1b2` 这类乱码；
     *                        保留字段名是为了不破坏既有消费方，新代码请用 {@code display}
     * @param display         可读原文（首个样本失败消息首行）
     * @param clusterKey      内部指纹，重跑与去重用；界面上不要直接展示
     * @param count           该簇失败条数
     * @param firstSeenAt     在最近 {@value #HISTORY_LIMIT} 轮里首次出现的运行结束时间；
     *                        无历史样本时为 {@code null}（不写当前时间冒充「首次」）
     * @param consecutiveRuns 从最近一轮往前数，连续出现的轮数；无历史时为 {@code null}
     * @param reproRate       出现轮数 / 统计轮数，保留 4 位小数；无历史时为 {@code null}
     */
    public record TopFailure(String name, String display, String clusterKey, int count,
                             Instant firstSeenAt, Integer consecutiveRuns, Double reproRate) {
    }

    /**
     * 速览。
     *
     * @param flaky 抖动用例条数（首轮失败、复测通过）。**不计入 failed**：它是「结果不稳定」
     *              而不是「功能不达标」，混进失败数会让结论比实际更差，单独列出来才看得清
     */
    public record Summary(String conclusion, int total, int passed, int failed, int skipped,
                          double passRate, long durationMs, List<TopFailure> topFailures,
                          List<String> risks, Instant generatedAt, int flaky) {

        /** 兼容 v5.6.0 之前的十参写法（无抖动统计）。 */
        public Summary(String conclusion, int total, int passed, int failed, int skipped,
                       double passRate, long durationMs, List<TopFailure> topFailures,
                       List<String> risks, Instant generatedAt) {
            this(conclusion, total, passed, failed, skipped, passRate, durationMs, topFailures,
                    risks, generatedAt, 0);
        }
    }

    private static final int TOP_LIMIT = 5;

    /**
     * 复现率统计窗口（轮）。取 10 是因为「最近十轮都栽在同一处」已经足够说明是稳定性问题，
     * 再往前翻会跨版本、跨台架，混进来的历史反而降低判据可信度。
     */
    private static final int HISTORY_LIMIT = 10;

    private final ReportRepository reports;
    private final FailureClusterService clusters;
    private final AccessControlService access;
    private final com.qa.autotest.application.repository.RunRepository runs;
    private final com.qa.autotest.application.repository.ReportReadRepository read;

    public ReportSummaryService(ReportRepository reports, FailureClusterService clusters,
                                AccessControlService access,
                                com.qa.autotest.application.repository.RunRepository runs,
                                com.qa.autotest.application.repository.ReportReadRepository read) {
        this.reports = reports;
        this.clusters = clusters;
        this.access = access;
        this.runs = runs;
        this.read = read;
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
            History history = history(projectId);
            clusterView.classes().stream()
                    .flatMap(group -> group.clusters().stream())
                    .sorted(Comparator.comparingInt(FailureClusterView.Cluster::count).reversed())
                    .limit(TOP_LIMIT)
                    .forEach(cluster -> topFailures.add(new TopFailure(
                            cluster.display(), cluster.display(), cluster.key(), cluster.count(),
                            history.firstSeenAt(cluster.key()),
                            history.consecutiveRuns(cluster.key()),
                            history.reproRate(cluster.key()))));
        }

        List<String> risks = risks(report, clusterView, total);
        return new Summary(conclusion, total, report.passCount(), report.failCount(),
                report.skipCount(), passRate, durationMs(report), topFailures, risks,
                Instant.now(), flakyCount(projectId, runId));
    }

    /**
     * 取最近若干轮的失败簇键，用于算「首现 / 连续出现 / 复现率」（FR-v5.6-26）。
     *
     * <p>只在有失败簇时才调用，且窗口固定 {@value #HISTORY_LIMIT} 轮——
     * 摘要接口不能变成「每次刷新都扫全库」的热点。
     * 读历史失败属正常业务读取，单轮读失败（权限变化、数据被清）只跳过该轮，不影响主流程。
     */
    private History history(long projectId) {
        List<com.qa.autotest.domain.execution.RunRecord> recent;
        try {
            recent = runs.page(projectId, null, 1, HISTORY_LIMIT).items();
        } catch (RuntimeException ex) {
            return History.EMPTY;
        }
        List<FailureClusterView> views = new ArrayList<>();
        for (com.qa.autotest.domain.execution.RunRecord run : recent) {
            try {
                views.add(clusters.cluster(projectId, run.runId()));
            } catch (RuntimeException ex) {
                views.add(null);
            }
        }
        return new History(recent, views);
    }

    /**
     * 历史窗口。{@code runs} 与 {@code views} 一一对应且**按时间倒序**（最新在前）。
     *
     * <p>为什么要显式排一次：{@code RunRepository.page} 默认 `id DESC`，但「连续出现」
     * 依赖严格的新→旧顺序，一旦有人改了排序口径，这里的结果会从「连续 3 轮」变成随机数字。
     */
    record History(List<com.qa.autotest.domain.execution.RunRecord> runs,
                   List<FailureClusterView> views) {

        static final History EMPTY = new History(List.of(), List.of());

        /** 少于两轮就不叫历史：有 1 轮时首现＝本次、复现率＝100%，都是假指标。 */
        private boolean usable() {
            return runs.size() > 1;
        }

        private List<String> keysAt(int index) {
            FailureClusterView view = views.get(index);
            if (view == null) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (FailureClusterView.ClassGroup group : view.classes()) {
                for (FailureClusterView.Cluster cluster : group.clusters()) {
                    keys.add(cluster.key());
                }
            }
            return keys;
        }

        /** 最早一轮出现该簇的运行结束时间；窗口内没出现过返回 null。 */
        Instant firstSeenAt(String clusterKey) {
            if (!usable()) {
                return null;
            }
            for (int i = runs.size() - 1; i >= 0; i--) {
                if (keysAt(i).contains(clusterKey)) {
                    return finishedAt(i);
                }
            }
            return null;
        }

        /** 从最新一轮往前连续命中的轮数；最新一轮就没有该簇时返回 0（是 0，不是 null）。 */
        Integer consecutiveRuns(String clusterKey) {
            if (!usable()) {
                return null;
            }
            int streak = 0;
            for (int i = 0; i < runs.size(); i++) {
                if (!keysAt(i).contains(clusterKey)) {
                    break;
                }
                streak++;
            }
            return streak;
        }

        /** 命中轮数 / 窗口轮数，4 位小数；窗口不可用返回 null。 */
        Double reproRate(String clusterKey) {
            if (!usable()) {
                return null;
            }
            int hits = 0;
            for (int i = 0; i < runs.size(); i++) {
                if (keysAt(i).contains(clusterKey)) {
                    hits++;
                }
            }
            return Math.round(hits * 10000.0 / runs.size()) / 10000.0;
        }

        private Instant finishedAt(int index) {
            com.qa.autotest.domain.execution.RunRecord run = runs.get(index);
            return run.finishedAt() != null ? run.finishedAt() : run.startedAt();
        }
    }

    /**
     * 抖动条数：从逐条结果里数 {@code flaky}，不从失败簇推断。
     *
     * <p>读失败只跳过（返回 0）而不抛：摘要是个「看一眼」的接口，
     * 不值得因为统计抖动而整体 500——抖动的信息量远小于结论本身。
     */
    private int flakyCount(long projectId, String runId) {
        try {
            int count = 0;
            for (com.qa.autotest.domain.report.RunCaseRow row : read.listCases(projectId, runId)) {
                if (row.flaky()) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException ex) {
            return 0;
        }
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
