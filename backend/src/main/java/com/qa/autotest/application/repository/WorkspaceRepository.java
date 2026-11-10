package com.qa.autotest.application.repository;

import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 工作区导出/导入任务与快照读取（v4.1，ADR-4X-12..15）。
 *
 * <p>活动任务即项目锁：{@link #createJob} 撞唯一键时由实现映射为 409，
 * 调用方因此无需额外的分布式锁。快照必须在同一个只读事务内一次性读出。</p>
 */
public interface WorkspaceRepository {

    /**
     * 写入 PENDING 任务；同一项目同一方向（EXPORT/IMPORT）已有活动任务时抛出
     * {@code BusinessException(CONFLICT)}。
     */
    long createJob(long projectId, String jobType, String granularity, String targetPath,
                   String strategy, String bundlePath, Long operatorId);

    Optional<WorkspaceJob> findJob(long projectId, long jobId);

    /** 最近任务，按 id 倒序；任务中心据此展示排队/运行/完成。 */
    List<WorkspaceJob> recentJobs(long projectId, int limit);

    void markRunning(long projectId, long jobId);

    /** 终态写入（SUCCESS/FAILED/CANCELLED）；同时释放活动锁。 */
    void finishJob(long projectId, long jobId, String status, String bundlePath,
                   String manifestSha256, String reportJson, String errorMessage, Long operatorId);

    /** 只读事务内的全量聚合；{@code project} 由调用方从项目仓储取。 */
    WorkspaceSnapshot snapshot(long projectId, WorkspaceSnapshot.ProjectInfo project);
}
