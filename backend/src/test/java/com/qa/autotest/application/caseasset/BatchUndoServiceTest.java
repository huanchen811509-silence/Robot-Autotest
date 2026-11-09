package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.BatchUndoRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.ReviewSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 批量撤销：5 分钟窗口、token 一次性、跨项目拒绝、逐条部分失败。 */
class BatchUndoServiceTest {

    private static final long PROJECT = 1L;
    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private FakeBatchUndoRepository repository;
    private TestCaseRepository cases;
    private AuditService audit;
    private BatchUndoService service;

    @BeforeEach
    void setUp() {
        repository = new FakeBatchUndoRepository();
        cases = mock(TestCaseRepository.class);
        audit = mock(AuditService.class);
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        TenantContext.set(USER, "tester", PROJECT);
        service = new BatchUndoService(repository, cases, audit, access, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("可逆动作生成 token，撤销后逐条恢复并写审计")
    void 撤销成功() {
        String token = service.record(PROJECT, "setPriority",
                List.of(snapshot(1L), snapshot(2L)));
        assertThat(token).isNotBlank();

        BatchUndoService.UndoResult result = service.undo(PROJECT, token);

        assertThat(result.action()).isEqualTo("setPriority");
        assertThat(result.restored()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        verify(cases).updatePriority(PROJECT, 1L, "P2", USER);
        verify(cases).updatePriority(PROJECT, 2L, "P2", USER);
        verify(audit).record("case.batch.undo.setPriority", "test_case", "1",
                "undo=" + token + "; restored=setPriority");
    }

    @Test
    @DisplayName("delete 与空快照不生成撤销凭据")
    void 不可撤销动作() {
        assertThat(service.record(PROJECT, "delete", List.of(snapshot(1L)))).isNull();
        assertThat(service.record(PROJECT, "setPriority", List.of())).isNull();
    }

    @Test
    @DisplayName("token 只能消费一次，第二次返回 409")
    void 一次性消费() {
        String token = service.record(PROJECT, "setPriority", List.of(snapshot(1L)));
        service.undo(PROJECT, token);

        assertThatThrownBy(() -> service.undo(PROJECT, token))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("超过 5 分钟窗口返回 409")
    void 过期拒绝() {
        repository.put(new BatchUndoRepository.UndoRecord(99, PROJECT, "late", "setPriority",
                "test_case", "[]", 1, NOW.minusSeconds(1), null, NOW));

        assertThatThrownBy(() -> service.undo(PROJECT, "late"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("别的项目的 token 返回 409")
    void 跨项目拒绝() {
        repository.put(new BatchUndoRepository.UndoRecord(99, PROJECT + 1, "other", "setPriority",
                "test_case", "[]", 1, NOW.plusSeconds(60), null, NOW));

        assertThatThrownBy(() -> service.undo(PROJECT, "other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("逐条恢复：单条失败不影响其他，结果里带原因")
    void 部分失败() {
        doThrow(new IllegalStateException("boom"))
                .when(cases).updatePriority(eq(PROJECT), eq(1L), anyString(), any());
        String token = service.record(PROJECT, "setPriority",
                List.of(snapshot(1L), snapshot(2L)));

        BatchUndoService.UndoResult result = service.undo(PROJECT, token);

        assertThat(result.restored()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items()).filteredOn(item -> !item.ok())
                .singleElement().satisfies(item -> assertThat(item.message()).contains("boom"));
    }

    private static ReviewSnapshot snapshot(long id) {
        return new ReviewSnapshot(id, "draft", "P2", "core", "L1", "old comment", null, null);
    }

    /** 内存假仓储：token 唯一，consume 模拟 SQL 的「未消费且未过期」原子条件。 */
    private static final class FakeBatchUndoRepository implements BatchUndoRepository {

        private final Map<String, UndoRecord> records = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public long insert(long projectId, String token, String action, String targetType,
                           String payload, int itemCount, Instant expiresAt, Long operatorId) {
            long id = nextId++;
            records.put(token, new UndoRecord(id, projectId, token, action, targetType, payload,
                    itemCount, expiresAt, null, Instant.now()));
            return id;
        }

        @Override
        public Optional<UndoRecord> find(long projectId, String token) {
            UndoRecord record = records.get(token);
            return record != null && record.projectId() == projectId
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public boolean consume(long projectId, long id, Instant now) {
            Optional<UndoRecord> found = records.values().stream()
                    .filter(record -> record.id() == id && record.projectId() == projectId)
                    .findFirst();
            if (found.isEmpty() || found.get().consumedAt() != null
                    || found.get().expiresAt().isBefore(now)) {
                return false;
            }
            UndoRecord old = found.get();
            records.put(old.token(), new UndoRecord(old.id(), old.projectId(), old.token(),
                    old.action(), old.targetType(), old.payload(), old.itemCount(), old.expiresAt(),
                    now, old.createdAt()));
            return true;
        }

        void put(UndoRecord record) {
            records.put(record.token(), record);
        }
    }
}
