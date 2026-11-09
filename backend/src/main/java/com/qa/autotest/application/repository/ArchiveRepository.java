package com.qa.autotest.application.repository;

import com.qa.autotest.domain.archive.ArchivePolicy;
import com.qa.autotest.domain.archive.ArchivedRunInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 归档与保留（FR-3.4-01）：候选扫描 / 冷热搬迁 / 恢复。 */
public interface ArchiveRepository {

    record RunCandidate(long id, String runId, java.time.Instant createdAt) {
    }

    record Counts(int testResults, int keywordResults) {
    }

    record ColdResult(long id, Long caseId, String verdict, Long durationMs,
                      String failureMsg, String failureKind, java.time.Instant createdAt) {
    }

    ArchivePolicy policy(long projectId);

    void savePolicy(long projectId, ArchivePolicy policy, Long actorId);

    /** 启用归档的项目（定时任务用）。 */
    List<Long> enabledProjects();

    List<RunCandidate> candidates(long projectId, Instant before, int limit);

    Counts counts(long runDbId, long projectId);

    long insertArchive(long projectId, long runDbId, String runId,
                       int testResultCount, int keywordResultCount);

    int moveTestResults(long runDbId, long archiveId, long projectId);

    int moveKeywordResults(long runDbId, long archiveId, long projectId);

    Optional<ArchivedRunInfo> findArchive(long projectId, String runId);

    List<ArchivedRunInfo> archives(long projectId, int limit);

    /** 冷表查询：归档后仍可直接看明细，不必先恢复。 */
    List<ColdResult> coldResults(long archiveId, long projectId, int limit);

    int restoreTestResults(long archiveId, long projectId);

    int restoreKeywordResults(long archiveId, long projectId);

    void markRestored(long projectId, String runId);

    void deleteArchiveDetails(long archiveId);
}
