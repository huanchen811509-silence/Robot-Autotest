package com.qa.autotest.application.repository;

import com.qa.autotest.domain.execution.ManualExecution;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 手工执行仓储（FR-v5.4-04 / W54-04）。
 *
 * <p>两张表在仓储层被折叠成一个聚合：一条 {@code manual_execution} 带着它的
 * {@code manual_step_result} 列表一起读写。理由：步骤结果从不单独被查询
 * （没人会问「所有执行的第 3 步结论是什么」），拆成两个仓储只会让每个调用点都要拼装。
 */
public interface ManualExecutionRepository {

    /** 新建（含全部未确认步骤）。返回新 id。 */
    long insert(ManualExecution execution, Long operatorId);

    /** 更新执行头（结论、备注、结束时间）。 */
    void update(ManualExecution execution, Long operatorId);

    /** 更新单步确认结果。 */
    void updateStep(long executionId, ManualExecution.StepResult step, Long operatorId);

    /** 读一条（含步骤）。 */
    Optional<ManualExecution> find(long projectId, long executionId);

    /** 按 run 列出（一个手工 run 可能含多条用例的执行）。 */
    List<ManualExecution> listByRun(long projectId, long runId);

    /** 按用例列出（看这条用例历史上被手工验过几次）。 */
    List<ManualExecution> listByCase(long projectId, long caseId, Instant since, int limit);

    /** 某项目近 N 天手工执行的结论分布（D12/D13 分离统计用）。 */
    long countByVerdict(long projectId, String verdict, Instant since);
}
