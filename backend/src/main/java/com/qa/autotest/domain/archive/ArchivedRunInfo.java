package com.qa.autotest.domain.archive;

import java.time.Instant;

/** 已归档运行（FR-3.4-01）：热表只保留聚合数字，明细在冷表里按原主键存放。 */
public record ArchivedRunInfo(long id, long projectId, String runId, long runDbId,
                              int testResultCount, int keywordResultCount,
                              Instant archivedAt, Instant restoredAt) {

    public boolean restored() {
        return restoredAt != null;
    }
}
