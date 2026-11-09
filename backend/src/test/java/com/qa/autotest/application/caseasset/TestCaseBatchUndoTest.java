package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.ReviewSnapshot;
import com.qa.autotest.domain.caseasset.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** 批量动作与撤销凭据的衔接：只有可逆动作、且仅成功条目进入快照。 */
class TestCaseBatchUndoTest {

    private static final long PROJECT = 1L;

    private final List<Recorded> recorded = new ArrayList<>();
    private final BatchUndoRecorder recorder = (projectId, action, snapshots) -> {
        recorded.add(new Recorded(action, snapshots));
        return snapshots.isEmpty() ? null : "TOKEN";
    };
    private TestCaseRepository cases;
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        cases = mock(TestCaseRepository.class);
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new TestCaseService(cases, access, mock(AuditService.class), txManager, recorder);
    }

    @Test
    @DisplayName("setPriority 成功后返回 token，快照覆盖全部成功条目")
    void 可逆动作生成凭据() {
        when(cases.find(eq(PROJECT), anyLong())).thenAnswer(invocation ->
                Optional.of(testCase(((Number) invocation.getArgument(1)).longValue())));
        when(cases.reviewSnapshot(eq(PROJECT), anyLong())).thenAnswer(invocation ->
                Optional.of(snapshot(((Number) invocation.getArgument(1)).longValue())));

        TestCaseService.BatchResult result = service.batch(PROJECT,
                new TestCaseService.BatchRequest("setPriority", List.of(1L, 2L),
                        null, "P1", null, null));

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.undoToken()).isEqualTo("TOKEN");
        assertThat(recorded).singleElement()
                .satisfies(call -> assertThat(call.snapshots())
                        .extracting(ReviewSnapshot::id).containsExactly(1L, 2L));
    }

    @Test
    @DisplayName("delete 不可撤销：不生成 token，也不记录快照")
    void 删除不可撤销() {
        when(cases.find(eq(PROJECT), eq(1L))).thenReturn(Optional.of(testCase(1L)));
        when(cases.reviewSnapshot(eq(PROJECT), eq(1L))).thenReturn(Optional.of(snapshot(1L)));

        TestCaseService.BatchResult result = service.batch(PROJECT,
                new TestCaseService.BatchRequest("delete", List.of(1L),
                        null, null, null, null));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.undoToken()).isNull();
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("失败条目不进入撤销快照")
    void 只收集成功条目() {
        when(cases.find(eq(PROJECT), eq(1L))).thenReturn(Optional.of(testCase(1L)));
        when(cases.find(eq(PROJECT), eq(2L))).thenReturn(Optional.empty());
        when(cases.reviewSnapshot(eq(PROJECT), anyLong())).thenAnswer(invocation ->
                Optional.of(snapshot(((Number) invocation.getArgument(1)).longValue())));

        TestCaseService.BatchResult result = service.batch(PROJECT,
                new TestCaseService.BatchRequest("setPriority", List.of(1L, 2L),
                        null, "P1", null, null));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(recorded).singleElement()
                .satisfies(call -> assertThat(call.snapshots())
                        .extracting(ReviewSnapshot::id).containsExactly(1L));
    }

    private static TestCase testCase(long id) {
        return new TestCase(id, PROJECT, "TC-" + id, "用例" + id, "functional", "P2",
                "", "", "core", "draft", "manual", "", "", "L1");
    }

    private static ReviewSnapshot snapshot(long id) {
        return new ReviewSnapshot(id, "draft", "P2", "core", "L1", null, null, null);
    }

    private record Recorded(String action, List<ReviewSnapshot> snapshots) {
    }
}
