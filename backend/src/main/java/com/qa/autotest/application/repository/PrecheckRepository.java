package com.qa.autotest.application.repository;

import java.util.List;

/** 运行预检的只读聚合查询（FR-3.1-06）：只做计数与采样，不落新表。 */
public interface PrecheckRepository {

    /** 历史单条耗时样本；{@code count=0} 表示没有可用历史。 */
    record DurationSample(int count, long avgMillis) {
    }

    int countCases(long projectId, List<Long> caseIds);

    int countTestSetCases(long projectId, long testSetId);

    int countPlanCases(long projectId, long planId);

    int countRiskyCases(long projectId, List<Long> caseIds);

    int countRiskyTestSetCases(long projectId, long testSetId);

    int countRiskyPlanCases(long projectId, long planId);

    DurationSample durationSample(long projectId, int limit);
}
