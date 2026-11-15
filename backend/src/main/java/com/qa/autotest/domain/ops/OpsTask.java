package com.qa.autotest.domain.ops;

import java.time.Instant;

/** 运维任务（v5.16.0 / FR-v5.16-09）：状态机含 ABORTED，日志只追加不覆盖。 */
public record OpsTask(long id,
                      long projectId,
                      String taskType,
                      Long operatorId,
                      String paramsJson,
                      String phase,
                      int progress,
                      String status,
                      String logText,
                      String resultJson,
                      Instant leaseUntil,
                      Instant startedAt,
                      Instant finishedAt,
                      Instant createdAt) {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String ABORTED = "ABORTED";
}
