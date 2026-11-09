package com.qa.autotest.domain.archive;

/** 归档策略（FR-3.4-01）。默认关闭；dry-run 默认 true，必须先看清单再执行。 */
public record ArchivePolicy(long projectId, boolean enabled, int retentionDays, int batchSize) {

    public ArchivePolicy {
        retentionDays = retentionDays < 1 ? 180 : retentionDays;
        batchSize = batchSize < 1 ? 200 : Math.min(batchSize, 2000);
    }

    public static ArchivePolicy defaults(long projectId) {
        return new ArchivePolicy(projectId, false, 180, 200);
    }
}
