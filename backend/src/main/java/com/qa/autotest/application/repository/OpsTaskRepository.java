package com.qa.autotest.application.repository;

import com.qa.autotest.domain.ops.OpsTask;
import java.util.List;
import java.util.Optional;

/** 运维任务仓储（v5.16.0）：所有 SQL 必须带 project_id 并过租户守卫。 */
public interface OpsTaskRepository {

    long insert(long projectId, String taskType, String paramsJson, Long operatorId);

    Optional<OpsTask> find(long projectId, long id);

    List<OpsTask> list(long projectId, int limit);

    long count(long projectId);

    /** 阶段推进：日志追加而非覆盖。 */
    void advance(long projectId, long id, String phase, int progress, String logLine,
                 String status);

    void finish(long projectId, long id, String status, String resultJson);

    /** 租约超时的 RUNNING 任务对账为 ABORTED，返回实际改动行数。 */
    int sweep(long projectId);
}
