package com.qa.autotest.domain.execution;

import java.time.Instant;

/**
 * 运行记录视图。字段与 run 表一一对应，数值未产出时为 0。
 *
 * <p>{@code runRequestId}（v4.11.0 / FR-v4.11-16）= {@code run.run_request_id} 幂等键，可空。
 * 执行计划页用前缀 {@code EP-} 标记自己发起的运行，并据此过滤「本页发起记录」，
 * 因此需要把它暴露到接口——该列自 V4_1 就存在，本版没有新增表 / 列。
 */
public record RunRecord(long id,
                        String runId,
                        long projectId,
                        Long testSetId,
                        RunState state,
                        int priority,
                        int concurrency,
                        int attemptNo,
                        int totalTests,
                        int passedTests,
                        int failedTests,
                        String failureClass,
                        String failureReason,
                        String failureMessage,
                        String artifactsDir,
                        Instant enqueuedAt,
                        Instant startedAt,
                        Instant finishedAt,
                        String deviceScope,
                        String runRequestId) {
}
