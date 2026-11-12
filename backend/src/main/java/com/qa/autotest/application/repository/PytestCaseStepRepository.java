package com.qa.autotest.application.repository;

import com.qa.autotest.domain.caseasset.PytestCaseStep;
import java.util.List;

/**
 * pytest 步骤证据仓储（FR-v5.1-08）。
 *
 * <p>写入按 run 覆盖（幂等）：同一次 run 反复抽取只保留最后一次结果，
 * 与 {@code ReportRepository.replace} 的「先删后插」同一口径，避免门禁/页面看到翻倍的步骤。
 */
public interface PytestCaseStepRepository {

    /** 覆盖写入。返回写入行数。空列表等价于「清空该 run 的证据」。 */
    int replace(long runId, List<PytestCaseStep> steps);

    /** 按 run 读取，按 nodeId + seq 排序。 */
    List<PytestCaseStep> listByRun(long runId);

    /** 按 test_result 读取（报告页单用例抽屉用）。 */
    List<PytestCaseStep> listByTestResult(long runId, long testResultId);

    /** 该 run 已入库的行数（0 表示还没抽取过，与「抽取了但没步骤」要区分）。 */
    int countByRun(long runId);

    /** 删除某 run 的全部证据（run 重跑前清理）。 */
    int deleteByRun(long runId);

    /**
     * 该 run 的 {@code test_result.case_key → test_result.id} 映射。
     *
     * <p>步骤行要挂到具体用例上才能被报告页的抽屉用上；两表没有共同的 nodeid 列，
     * 所以由上层用「case_key 末段 == 用例库编号」把 matchKey 翻译成 id。
     * 翻译放在 Java 里而不是 SQL 里：{@code case_key} 是 {@code <路径>.<用例名>} 的拼串，
     * 用 {@code LIKE '%.' || ?} 会命中前缀相同的编号（IM-NET-13 vs IM-NET-131）。
     */
    java.util.Map<String, Long> testResultIdsByCaseKey(long runId);
}
