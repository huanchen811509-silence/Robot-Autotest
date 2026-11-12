package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.List;

/**
 * 环境健康雷达的取数口（FR-v5.3-07 / W53-07）。
 *
 * <p>只提供「取原始失败样本」一个方法，聚合与分类全在应用层做。理由与时长基线相同：
 * 分类规则只有一份（{@link com.qa.autotest.application.execution.FailureClassifier}），
 * 若把「什么算环境失败」写进 SQL 的 {@code LIKE} 里，就会立刻出现
 * 「SQL 一套关键字、Java 一套分类」的双真源，两边迟早不一致。
 */
public interface EnvironmentRadarRepository {

    /**
     * 一条失败样本。
     *
     * @param kernel    内核，用于区分 RF 与 pytest 的失败表达差异
     * @param failureMsg 失败原文（可能很长，分类只看首行）
     */
    record FailureSample(long runId, long caseId, String kernel, String failureMsg, Instant at) {
    }

    /**
     * 近 {@code since} 之后的失败样本（只取 verdict='FAIL'）。
     *
     * <p>必须有上限：一个项目一个月可能几万条失败，全量装进内存算一遍是浪费，
     * 而且这个页面是「看趋势」不是「查明细」。超上限时按时间倒序截断，
     * 返回的样本数少于实际数要在页面上说清（{@code truncated}）。
     */
    List<FailureSample> recentFailures(long projectId, Instant since, int limit);

    /** 近 {@code since} 之后的总失败数（用于判断上面的样本是否被截断）。 */
    long countFailures(long projectId, Instant since);
}
