package com.qa.autotest.application.repository;

import com.qa.autotest.domain.execution.CaseDurationBaseline;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用例时长基线仓储（FR-v5.3-03 / W53-03）。
 *
 * <p>样本来源是 {@code test_result} 联 {@code run}（内核在 run 上）：
 * 不新建「样本表」，因为事实已经在库里，再存一份就会出现「样本与结果不一致」。
 */
public interface CaseDurationBaselineRepository {

    /**
     * 一条历史耗时样本。
     *
     * @param kernel     run 级内核快照（{@code robot} / {@code pytest}）
     * @param verdict    结论（PASS/FAIL/SKIP…），用于剔除 SKIP 与判别快检
     * @param failureMsg 失败原文，用于环境失败粗筛
     */
    record DurationSample(long caseId, String kernel, long durationMs, String verdict,
                          String failureMsg, Instant at) {
    }

    /** 取窗口内的样本（只含 duration_ms 非空、case_id 非空的行）。 */
    List<DurationSample> samples(long projectId, Instant since);

    /** 整体替换某项目的基线（重算即全量刷新，不做增量——增量会留下永远清不掉的旧行）。 */
    void replaceAll(long projectId, List<CaseDurationBaseline> rows);

    List<CaseDurationBaseline> list(long projectId);

    Optional<CaseDurationBaseline> find(long projectId, long caseId, String kernel);

    long countByProject(long projectId);

    /**
     * 近期有 run 的项目 id（定时重算用）。
     *
     * <p>不做「全项目遍历」：没有 run 的项目基线必然是空的，扫一遍只是浪费；
     * 而「近 N 天有 run」恰好就是需要基线的集合。
     */
    List<Long> projectsWithRecentRuns(Instant since);

    /**
     * 按用例编号取 P90（{@code caseCode -> p90Ms}），一次查全项目。
     *
     * <p>给「估算一批用例要跑多久」用（W53-08 长跑识别）。执行请求里带的是
     * {@code caseCode} 而不是 {@code caseId}，逐条 {@link #find} 会变成 N+1 查询——
     * 一批 300 条用例就是 300 次往返。这里一次拿全项目映射，内存里查表。
     *
     * @param kernel 内核；null 表示不限内核（同一用例在 RF 与 pytest 下耗时不同，
     *               调用方一般应传具体内核）
     */
    java.util.Map<String, Long> p90ByCaseCode(long projectId, String kernel);
}
