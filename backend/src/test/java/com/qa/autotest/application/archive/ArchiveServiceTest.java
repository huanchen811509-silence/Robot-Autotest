package com.qa.autotest.application.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ArchiveRepository;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.archive.ArchivePolicy;
import com.qa.autotest.domain.archive.ArchivedRunInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 归档：默认 dry-run、未启用策略拒绝执行、恢复冲突显式报错（FR-3.4-01）。 */
class ArchiveServiceTest {

    private static final long PROJECT = 29L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);

    private ArchiveRepository repo;
    private AuditRepository audit;
    private AccessControlService access;
    private ArchiveService service;

    @BeforeEach
    void setUp() {
        repo = mock(ArchiveRepository.class);
        audit = mock(AuditRepository.class);
        access = mock(AccessControlService.class);
        when(access.isAdmin()).thenReturn(true);
        service = new ArchiveService(repo, audit, access, CLOCK, (org.springframework.transaction.support.TransactionTemplate) null);
    }

    @Test
    void dryRunListsCandidatesWithoutMovingRows() {
        when(repo.policy(PROJECT)).thenReturn(new ArchivePolicy(PROJECT, true, 90, 10));
        when(repo.candidates(anyLong(), any(), anyInt())).thenReturn(List.of(
                new ArchiveRepository.RunCandidate(1L, "RUN-1", Instant.parse("2026-07-01T00:00:00Z"))));
        when(repo.counts(1L, PROJECT)).thenReturn(new ArchiveRepository.Counts(3, 2));

        ArchiveService.ExecuteResult result = service.run(PROJECT, true);

        assertTrue(result.dryRun());
        assertEquals(1, result.archivedRuns());
        assertEquals(3, result.movedTestResults());
        verify(repo, never()).insertArchive(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
        verify(repo, never()).moveTestResults(anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsExecutionWhenPolicyIsDisabled() {
        when(repo.policy(PROJECT)).thenReturn(new ArchivePolicy(PROJECT, false, 90, 10));

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.run(PROJECT, false));
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.errorCode());
        verify(repo, never()).insertArchive(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void restoreMovesRowsBackAndAudits() {
        ArchivedRunInfo info = new ArchivedRunInfo(7L, PROJECT, "RUN-1", 11L, 2, 1,
                Instant.parse("2026-09-01T00:00:00Z"), null);
        when(repo.findArchive(PROJECT, "RUN-1")).thenReturn(Optional.of(info));
        when(repo.restoreTestResults(7L, PROJECT)).thenReturn(2);
        when(repo.restoreKeywordResults(7L, PROJECT)).thenReturn(1);

        ArchiveService.RestoreResult result = service.restore(PROJECT, "RUN-1");

        assertEquals(2, result.restoredTestResults());
        assertEquals(1, result.restoredKeywordResults());
        verify(repo).markRestored(PROJECT, "RUN-1");
        verify(repo).deleteArchiveDetails(7L);
        verify(audit).insert(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void restoreConflictIsReportedInsteadOfSilentlyDroppingData() {
        ArchivedRunInfo info = new ArchivedRunInfo(7L, PROJECT, "RUN-1", 11L, 2, 1,
                Instant.parse("2026-09-01T00:00:00Z"), null);
        when(repo.findArchive(PROJECT, "RUN-1")).thenReturn(Optional.of(info));
        when(repo.restoreTestResults(7L, PROJECT))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key PRIMARY"));

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.restore(PROJECT, "RUN-1"));
        assertEquals(ErrorCode.CONFLICT, conflict.errorCode());
        assertTrue(conflict.getMessage().contains("PRIMARY"));
        verify(repo, never()).markRestored(anyLong(), anyString());
    }
}
