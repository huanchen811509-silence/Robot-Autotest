package com.qa.autotest.application.repository;

import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 运行记录仓储。所有实现必须带 project_id 条件（run_id 全局唯一，但租户守卫不做例外）。
 */
public interface RunRepository {

    RunRecord insert(RunRecord draft, String jobNo, String runRequestId, Long operatorId);

    /**
     * 带触发来源的创建（PLN-01）：把 trigger_type / plan_id / triggered_by_plan_at 一起落库。
     *
     * <p>默认实现退化为手工运行，保证既有内存仓储与测试无需改动即可编译；
     * JDBC 实现必须覆盖本方法。
     */
    default RunRecord insert(RunRecord draft, String jobNo, String runRequestId,
                             Long operatorId, RunOrigin origin) {
        return insert(draft, jobNo, runRequestId, operatorId);
    }

    /**
     * 指定执行节点（NODX-02）：把 {@code run.node_id} 落库。默认空实现保证
     * 内存仓储/旧实现无需改动；JDBC 实现必须覆盖（带 project_id 租户条件）。
     */
    default void assignNode(long projectId, String runId, long nodeId) {
        throw new UnsupportedOperationException("该仓储不支持节点指派: " + runId);
    }

    /**
     * 回读 run 归属节点（NODX-03 回传校验）：空 Optional = run 不存在；
     * 有值但为 null 不可能（Optional.empty 已区分）。默认实现表示仓储未建模 node_id。
     */
    default Optional<Long> findNodeId(long projectId, String runId) {
        throw new UnsupportedOperationException("该仓储不支持节点归属查询: " + runId);
    }

    /**
     * 读取运行来源，供报告入库时把 PLAN_ID / TRIGGER_TYPE 合并进 metadata。
     * 默认返回空（内存仓储/旧实现没有来源概念）。
     */
    default Optional<RunOrigin> findOrigin(long projectId, String runId) {
        return Optional.empty();
    }

    /**
     * {@code execution_job} 主键（租约 {@code job_id} 外键）。默认实现表示仓储未建模该表，
     * JDBC 实现按 {@code job_no = run_id} 反查。
     */
    default Optional<Long> findJobId(long projectId, String runId) {
        return Optional.empty();
    }

    Optional<RunRecord> findByRunId(long projectId, String runId);

    Optional<RunRecord> findByRequestId(long projectId, String requestId);

    int updateState(String runId, RunState from, RunState to,
                    Instant startedAt, Instant finishedAt,
                    String failureClass, String failureReason, String failureMessage);

    int updateCounts(String runId, int total, int passed, int failed, String artifactsDir);

    /**
     * 回写本次运行使用的测试内核（v2.7 FR-04，run 级事实快照，路由真源仍是 KernelSelector）。
     * 默认空实现，保证内存仓储与既有测试无需改动；JDBC 实现必须覆盖。
     */
    default void updateKernel(String runId, String kernel) {
    }

    /**
     * 回读本次运行的测试内核；无记录/未建模时按 RF 处理（空 Optional 由调用方落 'robot'）。
     */
    default Optional<String> findKernel(long projectId, String runId) {
        return Optional.empty();
    }

    /**
     * 回写本次运行的租约等待耗时（{@code run.lease_wait_ms}，FR-08）。
     * 默认空实现，保证内存仓储与既有测试无需改动；JDBC 实现必须覆盖。
     */
    default int recordLeaseWait(String runId, long waitMs) {
        return 0;
    }

    /**
     * 回读已落库的租约等待耗时（{@code run.lease_wait_ms}，FR-08）。
     * 默认返回空，保证内存仓储与既有测试无需改动；JDBC 实现必须覆盖。
     */
    default Optional<Long> findLeaseWaitMs(long projectId, String runId) {
        return Optional.empty();
    }

    List<RunRecord> findActive();

    PageResult<RunRecord> page(long projectId, RunState state, int page, int size);

    /** 新入口：未指定排序时统一按 id DESC（FR-3.2-02）。 */
    default PageResult<RunRecord> page(long projectId, RunState state, PageQuery paging) {
        return page(projectId, state, paging.page(), paging.size());
    }

    int markAttempt(String runId, int attemptNo, String retryOf);

    /**
     * 上一次尝试（FR-v0.4-29）：把 {@code retry_of_run_id} 连成可读记录，
     * 运行详情才能回答「为什么重试」。没有重试来源时返回空。
     *
     * <p>默认空实现，保证内存仓储与既有测试无需改动；JDBC 实现按 {@code retry_of_run_id} 自连接。
     */
    default Optional<RunRetryOrigin> findRetryOrigin(long projectId, String runId) {
        return Optional.empty();
    }

    /** 项目内 run 按状态计数（W4 指标）；默认空实现，避免改动既有假仓储。 */
    default java.util.Map<String, Integer> countByStatus(long projectId) {
        return java.util.Map.of();
    }
}
