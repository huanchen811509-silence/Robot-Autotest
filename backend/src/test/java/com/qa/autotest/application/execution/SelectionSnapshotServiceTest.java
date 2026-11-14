package com.qa.autotest.application.execution;

import com.qa.autotest.application.execution.SelectionResolver.CaseScopeQuery;
import com.qa.autotest.application.execution.SelectionResolver.SelectionMode;
import com.qa.autotest.application.execution.SelectionSnapshotService.Frozen;
import com.qa.autotest.application.execution.SelectionSnapshotService.Recompute;
import com.qa.autotest.application.execution.SelectionSnapshotService.SnapshotRow;
import com.qa.autotest.application.execution.SelectionSnapshotService.SnapshotStore;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W57-27/28：四模式冻结、同输入同 sha、一次有效、空选择、越权、重算差异。 */
class SelectionSnapshotServiceTest {

    private static final long PROJECT = 1L;
    private static final long SNAPSHOT_ID = 100L;

    private CaseScopeQuery scope;
    private SnapshotStore store;
    private SelectionSnapshotService service;

    @BeforeEach
    void setUp() {
        scope = mock(CaseScopeQuery.class);
        AccessControlService access = mock(AccessControlService.class);
        SelectionResolver resolver = new SelectionResolver(scope, access);
        store = mock(SnapshotStore.class);
        service = new SelectionSnapshotService(store, resolver, access);
        when(scope.allCaseIds(anyLong(), anyMap())).thenReturn(List.of());
        when(scope.caseIdsOfSets(anyLong(), anyList())).thenReturn(List.of());
        when(scope.retainProjectCaseIds(anyLong(), anyList())).thenReturn(List.of());
        when(store.findBySha(anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.insert(any())).thenReturn(SNAPSHOT_ID);
    }

    private static SnapshotRow row(long id, long projectId, String sha, String mode,
                                   String setIds, String caseIds, String filter,
                                   int count, Instant consumed) {
        return new SnapshotRow(id, projectId, sha, mode, setIds, caseIds, filter,
                count, consumed, Instant.EPOCH);
    }

    @Test
    @DisplayName("ALL 模式冻结：落一行、caseCount 正确、sha 为确定性内容摘要")
    void all冻结落一行() {
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(List.of(3L, 1L, 2L));
        Frozen frozen = service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of());
        assertEquals(SNAPSHOT_ID, frozen.snapshotId());
        assertEquals(3, frozen.caseCount());
        assertEquals(List.of(1L, 2L, 3L), frozen.caseIds());
        assertFalse(frozen.reused());
        assertEquals(SelectionSnapshotService.computeSha(PROJECT, "ALL", null,
                List.of(1L, 2L, 3L), "{}", 3), frozen.selectionSha256());
        ArgumentCaptor<SnapshotRow> captor = ArgumentCaptor.forClass(SnapshotRow.class);
        verify(store).insert(captor.capture());
        assertEquals("ALL", captor.getValue().mode());
        assertEquals("[1,2,3]", captor.getValue().caseIdsJson());
    }

    @Test
    @DisplayName("同输入同 sha：命中唯一键且未消费 → 复用既有快照，不重复 insert")
    void 同输入同sha复用() {
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(List.of(1L, 2L, 3L));
        Frozen first = service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of());
        when(store.findBySha(eq(PROJECT), anyString())).thenReturn(Optional.of(
                row(SNAPSHOT_ID, PROJECT, first.selectionSha256(), "ALL",
                        "[]", "[1,2,3]", "{}", 3, null)));
        Frozen second = service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of());
        assertEquals(first.selectionSha256(), second.selectionSha256());
        assertTrue(second.reused());
        verify(store, times(1)).insert(any());
    }

    @Test
    @DisplayName("四模式：SET / CASE / MIXED 均可冻结并得到非空清单")
    void 四模式冻结() {
        when(scope.caseIdsOfSets(eq(PROJECT), eq(List.of(10L)))).thenReturn(List.of(3L, 4L));
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(4L, 5L))))
                .thenReturn(List.of(4L, 5L));
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(7L))))
                .thenReturn(List.of(7L));

        Frozen set = service.freeze(PROJECT, SelectionMode.SET, List.of(10L), null, null);
        Frozen oneCase = service.freeze(PROJECT, SelectionMode.CASE, null, List.of(7L), null);
        Frozen mixed = service.freeze(PROJECT, SelectionMode.MIXED,
                List.of(10L), List.of(4L, 5L), null);
        assertEquals(2, set.caseCount());
        assertEquals(1, oneCase.caseCount());
        assertEquals(3, mixed.caseCount());
        assertEquals(List.of(3L, 4L, 5L), mixed.caseIds());
    }

    @Test
    @DisplayName("空选择：CASE 模式无 caseIds 拒绝；全部 id 越权时结果为空也拒绝")
    void 空选择拒绝() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.freeze(PROJECT, SelectionMode.CASE, null, List.of(), null));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(999L)))).thenReturn(List.of());
        assertThrows(BusinessException.class,
                () -> service.freeze(PROJECT, SelectionMode.CASE, null, List.of(999L), null));
    }

    @Test
    @DisplayName("一次有效：consume 成功返回冻结清单；已消费再 consume 拒绝")
    void 一次有效() {
        when(store.findById(eq(PROJECT), eq(SNAPSHOT_ID))).thenReturn(Optional.of(
                row(SNAPSHOT_ID, PROJECT, "sha", "CASE", "[]", "[1,2,3]", "{}", 3, null)));
        when(store.markConsumed(eq(PROJECT), eq(SNAPSHOT_ID), any(), any())).thenReturn(1);
        assertEquals(List.of(1L, 2L, 3L), service.consume(PROJECT, SNAPSHOT_ID));

        when(store.findById(eq(PROJECT), eq(SNAPSHOT_ID))).thenReturn(Optional.of(
                row(SNAPSHOT_ID, PROJECT, "sha", "CASE", "[]", "[1,2,3]", "{}", 3, Instant.now())));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consume(PROJECT, SNAPSHOT_ID));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(store, times(1)).markConsumed(eq(PROJECT), eq(SNAPSHOT_ID), any(), any());
    }

    @Test
    @DisplayName("越权：用别的项目 id 消费，findById 查不到 → 拒绝")
    void 越权拒绝() {
        when(store.findById(eq(999L), anyLong())).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.consume(999L, SNAPSHOT_ID));
        verify(store, never()).markConsumed(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("命中已消费的同输入快照 → freeze 明确冲突，不伪造新 sha")
    void 已消费命中冲突() {
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(List.of(1L, 2L, 3L));
        String sha = SelectionSnapshotService.computeSha(PROJECT, "ALL", null,
                List.of(1L, 2L, 3L), "{}", 3);
        when(store.findBySha(eq(PROJECT), anyString())).thenReturn(Optional.of(
                row(1L, PROJECT, sha, "ALL", "[]", "[1,2,3]", "{}", 3, Instant.now())));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of()));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("recomputeSha：用例库未变 → match=true、差异三数组全空")
    void 重算一致() {
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(List.of(1L, 2L, 3L));
        Frozen frozen = service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of());
        when(store.findById(eq(PROJECT), eq(SNAPSHOT_ID))).thenReturn(Optional.of(
                row(SNAPSHOT_ID, PROJECT, frozen.selectionSha256(), "ALL",
                        "[]", "[1,2,3]", "{}", 3, null)));
        Recompute recompute = service.recomputeSha(PROJECT, SNAPSHOT_ID);
        assertTrue(recompute.match());
        assertTrue(recompute.diff().added().isEmpty());
        assertTrue(recompute.diff().removed().isEmpty());
        assertTrue(recompute.diff().reordered().isEmpty());
        assertEquals(List.of(1L, 2L, 3L), recompute.currentCaseIds());
    }

    @Test
    @DisplayName("recomputeSha：用例库多出一条 → match=false 且 added 给出编号")
    void 重算不一致() {
        when(scope.allCaseIds(eq(PROJECT), anyMap()))
                .thenReturn(List.of(1L, 2L, 3L), List.of(1L, 2L, 3L, 4L));
        Frozen frozen = service.freeze(PROJECT, SelectionMode.ALL, null, null, Map.of());
        when(store.findById(eq(PROJECT), eq(SNAPSHOT_ID))).thenReturn(Optional.of(
                row(SNAPSHOT_ID, PROJECT, frozen.selectionSha256(), "ALL",
                        "[]", "[1,2,3]", "{}", 3, null)));
        Recompute recompute = service.recomputeSha(PROJECT, SNAPSHOT_ID);
        assertFalse(recompute.match());
        assertFalse(recompute.expectedSha().equals(recompute.recomputedSha()));
        assertEquals(List.of(4), recompute.diff().added());
        assertEquals(List.of(1L, 2L, 3L, 4L), recompute.currentCaseIds());
    }

    @Test
    @DisplayName("idsJson / parseIds 可逆且顺序无关")
    void id编解码() {
        assertEquals("[1,2,3]", SelectionSnapshotService.idsJson(List.of(3L, 1L, 2L)));
        assertEquals(List.of(1L, 2L, 3L),
                SelectionSnapshotService.parseIds(SelectionSnapshotService.idsJson(List.of(3L, 1L, 2L))));
        assertEquals(List.of(), SelectionSnapshotService.parseIds("[]"));
    }
}
