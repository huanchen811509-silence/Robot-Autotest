package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.RunFailureBreakdownRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 把一个 run 的失败归因成分布快照（W52-10）。
 *
 * <p><b>为什么在报告层现算归因，而不是读处置表的 category</b>：处置表是按指纹聚合的长期台账，
 * 一行的 {@code category} 记的是**上一次**判定时的分类；用它统计「本次 run 的环境失败占比」
 * 会把几个月前的旧分类算进来。报告的分布必须是「这次这批结果各是什么原因」，
 * 所以逐行取 {@code failure_msg} 现算——同一份输入跑两次得同一个分布，可复算。
 *
 * <p><b>已派发未回（DISPATCHED / PENDING / PLANNED）不算失败侧样本</b>：它们没有失败原文，
 * 归任何一类都是编造。它们在计数里进 {@code planned}，报告口径是「计划未执行」。
 */
@Service
public class RunFailureBreakdownService {

    /** 计为「计划未执行」的结论。 */
    private static final List<String> PLANNED_VERDICTS = List.of("PLANNED", "DISPATCHED", "PENDING");

    private final RunFailureBreakdownRepository repository;

    public RunFailureBreakdownService(RunFailureBreakdownRepository repository) {
        this.repository = repository;
    }

    /**
     * 取分布。
     *
     * @param projectId 项目（租户守卫用）
     * @param runId     run 的业务编号（{@code run.run_id}，不是主键 id）
     */
    public FailureBreakdown.Snapshot of(long projectId, String runId) {
        RunFailureBreakdownRepository.Counts counts = repository.counts(projectId, runId);
        List<RunFailureBreakdownRepository.Row> rows = repository.nonPassedRows(projectId, runId);

        List<FailureBreakdown.Sample> samples = new ArrayList<>();
        for (RunFailureBreakdownRepository.Row row : rows) {
            if (planned(row.verdict())) {
                // 已在 counts.planned() 里计过，这里不能再加一遍，否则总账对不上
                continue;
            }
            FailureAttributor.Attribution attribution = FailureAttributor.of(
                    row.kernel(), row.verdict(), row.failureMsg(), List.of());
            samples.add(new FailureBreakdown.Sample(row.caseCode(), row.kernel(), row.verdict(),
                    attribution.category(), attribution.location(), attribution.original()));
        }
        return FailureBreakdown.of(counts.total(), counts.passed(), counts.planned(), samples);
    }

    /** 第 4 章（失败分析）直通，省得调用方每次多写三行。 */
    public ReportChapterBuilder.Chapter chapterOf(long projectId, String runId) {
        return of(projectId, runId).chapter();
    }

    private static boolean planned(String verdict) {
        if (verdict == null) {
            return false;
        }
        return PLANNED_VERDICTS.contains(verdict.trim().toUpperCase(Locale.ROOT));
    }
}
