package com.qa.autotest.domain.workspace;

/** 工作区导出/导入任务（V11_28 `workspace_job`）；活动任务唯一键即项目锁（ADR-4X-12..15）。 */
public record WorkspaceJob(
        Long id,
        Long projectId,
        String jobType,
        String granularity,
        String targetPath,
        String status,
        int progress,
        String bundlePath,
        String strategy,
        String manifestSha256,
        String reportJson,
        String errorMessage,
        java.time.Instant startedAt,
        java.time.Instant finishedAt,
        java.time.Instant createdAt) {

    public boolean active() {
        return "PENDING".equals(status) || "RUNNING".equals(status);
    }
}
