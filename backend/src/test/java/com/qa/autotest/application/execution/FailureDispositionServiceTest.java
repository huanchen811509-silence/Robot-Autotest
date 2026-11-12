package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.FailureDispositionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.execution.FailureDisposition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 失败处置闭环（FR-v5.3-06 / 验收 C16–C18 / W53-06）。
 *
 * <p>三条关键判据：
 * <ol>
 *   <li><b>指纹要聚得起来</b>：同一条失败换个端口号/耗时/IP 必须落到同一指纹，
 *       否则「认一次覆盖一片」就是空话；</li>
 *   <li><b>已知问题复现不 reopen</b>：否则待处置列表会被同一个已知问题填满；</li>
 *   <li><b>其余定论复现必须 reopen</b>：否则「改完了但没修好」会被当成已解决。</li>
 * </ol>
 */
class FailureDispositionServiceTest {

    private static final long PROJECT = 5L;

    private FakeRepository repo;
    private FailureDispositionService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepository();
        service = new FailureDispositionService(repo, mock(AuditService.class));
    }

    @Test
    void 指纹抹掉端口耗时与IP后能聚在一起() {
        String a = FailureFingerprint.of("pytest", "ENV", "tests/a.py:42",
                "Connect to 10.202.54.201:22 timed out after 5000ms");
        String b = FailureFingerprint.of("pytest", "ENV", "/abs/path/tests/a.py:42",
                "Connect to 192.168.1.7:22 timed out after 30ms");
        assertEquals(a, b, "同一条失败换个 IP/端口/耗时必须落到同一指纹");
    }

    @Test
    void 带单位的耗时数字也要被抹掉() {
        // 回归：\b\d+\b 在「5000ms」上匹配不到（0 与 m 都是词字符），
        // 曾导致耗时数字完全没抹、指纹一条也聚不起来。带单位是失败原文的常态。
        assertEquals(
                FailureFingerprint.of("pytest", "ENV", "tests/a.py:1", "io timeout after 5000ms"),
                FailureFingerprint.of("pytest", "ENV", "tests/a.py:1", "io timeout after 30ms"),
                "耗时数字带单位也必须归一，否则指纹聚不起来");
        assertEquals(
                FailureFingerprint.of("pytest", "ASSERT", "tests/b.py:1", "吞吐 12.5Gbps 低于门限"),
                FailureFingerprint.of("pytest", "ASSERT", "tests/b.py:1", "吞吐 3.75Gbps 低于门限"),
                "小数带单位同理");
    }

    @Test
    void 不同分类或不同位置指纹必须不同() {
        String base = FailureFingerprint.of("pytest", "ENV", "tests/a.py:42", "timed out");
        assertNotEqualsSafe(base, FailureFingerprint.of("pytest", "ASSERT", "tests/a.py:42",
                "timed out"));
        assertNotEqualsSafe(base, FailureFingerprint.of("pytest", "ENV", "tests/b.py:42",
                "timed out"));
        assertNotEqualsSafe(base, FailureFingerprint.of("robot", "ENV", "tests/a.py:42",
                "timed out"));
    }

    @Test
    void 首次记录建_open_行且次数为一() {
        FailureDisposition row = service.record(PROJECT, context("IM-1", "ENV", "timeout"));
        assertEquals(FailureDisposition.STATUS_OPEN, row.status());
        assertEquals(1, row.occurrences());
        assertFalse(row.settled());
    }

    @Test
    void 同一失败重复记录只累加次数不新建行() {
        service.record(PROJECT, context("IM-1", "ENV", "timed out after 5000ms"));
        FailureDisposition second = service.record(PROJECT,
                context("IM-1", "ENV", "timed out after 9000ms"));
        assertEquals(1, repo.rows.size(), "同一指纹只能有一行");
        assertEquals(2, second.occurrences());
    }

    @Test
    void 已定论为已知问题的复现不_reopen() {
        FailureDisposition row = service.record(PROJECT, context("IM-1", "ENV", "timed out"));
        service.decide(PROJECT, row.id(), FailureDisposition.DECISION_KNOWN_ISSUE, "厂商已知问题");
        FailureDisposition again = service.record(PROJECT, context("IM-1", "ENV", "timed out"));

        assertEquals(FailureDisposition.STATUS_DECIDED, again.status(),
                "已知问题本来就会一直失败，每出现一次就 reopen 会把队列填满噪音");
        assertEquals(0, again.reopenCount());
        assertEquals(2, again.occurrences());
    }

    @Test
    void 已定论为产品缺陷的复现必须_reopen() {
        FailureDisposition row = service.record(PROJECT, context("IM-2", "ASSERT", "速率不符"));
        service.decide(PROJECT, row.id(), FailureDisposition.DECISION_PRODUCT_BUG, "见 BUG-77");
        FailureDisposition again = service.record(PROJECT, context("IM-2", "ASSERT", "速率不符"));

        assertEquals(FailureDisposition.STATUS_OPEN, again.status());
        assertEquals(1, again.reopenCount());
        assertTrue(again.reopenReason().contains("RUN-"), again.reopenReason());
    }

    @Test
    void 状态机拒绝越级迁移() {
        FailureDisposition row = service.record(PROJECT, context("IM-3", "ENV", "x"));
        // 未定论不能直接关闭
        assertThrows(BusinessException.class, () -> service.close(PROJECT, row.id(), null));
        // 已定论不能再定论（改结论要先 reopen）
        service.decide(PROJECT, row.id(), FailureDisposition.DECISION_ENV_ISSUE, "网络抖动");
        assertThrows(BusinessException.class, () -> service.decide(PROJECT, row.id(),
                FailureDisposition.DECISION_PRODUCT_BUG, "改主意"));
        // 未定论不能 assign
        assertThrows(BusinessException.class, () -> service.assign(PROJECT, row.id(), 9L));
    }

    @Test
    void reopen_必须写原因且计数() {
        FailureDisposition row = service.record(PROJECT, context("IM-4", "ASSERT", "x"));
        service.decide(PROJECT, row.id(), FailureDisposition.DECISION_WONT_FIX, "不修");
        assertThrows(BusinessException.class, () -> service.reopen(PROJECT, row.id(), " "));

        FailureDisposition reopened = service.reopen(PROJECT, row.id(), "客户要求必须修");
        assertEquals(FailureDisposition.STATUS_OPEN, reopened.status());
        assertEquals(1, reopened.reopenCount());
        assertEquals("客户要求必须修", reopened.reopenReason());
    }

    @Test
    void 未知结论被拒并列出可选值() {
        FailureDisposition row = service.record(PROJECT, context("IM-5", "ENV", "x"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.decide(PROJECT, row.id(), "maybe", null));
        assertTrue(error.getMessage().contains("known_issue"), error.getMessage());
    }

    @Test
    void 批量处置_caseId_为零时也构成豁免() {
        // 按指纹批量处置：caseId=0，覆盖所有同指纹用例
        FailureDisposition batch = service.record(PROJECT, context("(批量)", "ENV",
                "ssh connection refused", 0L));
        service.decide(PROJECT, batch.id(), FailureDisposition.DECISION_KNOWN_ISSUE, "全网 SSH 配置问题");

        String fingerprint = FailureFingerprint.of("pytest", "ENV",
                "tests/a.py:42", "ssh connection refused");
        assertTrue(service.isExempt(PROJECT, 777L, fingerprint),
                "批量处置的意义就是覆盖同类，只看用例行会让它完全失效");
    }

    @Test
    void 豁免不影响用例结论_只回答是否已知() {
        FailureDisposition row = service.record(PROJECT, context("IM-6", "ENV", "x"));
        // 未定论时不是豁免；注意查的是该行自己的 caseId，别硬编码 1（行 caseId 由 code hash 得来）
        assertFalse(service.isExempt(PROJECT, row.caseId(), row.fingerprint()));
        service.decide(PROJECT, row.id(), FailureDisposition.DECISION_KNOWN_ISSUE, "已知");
        assertTrue(service.isExempt(PROJECT, row.caseId(), row.fingerprint()));
    }

    @Test
    void 台账按状态计数并与豁免数分开() {
        FailureDisposition a = service.record(PROJECT, context("IM-7", "ENV", "a"));
        FailureDisposition b = service.record(PROJECT, context("IM-8", "ASSERT", "b"));
        service.decide(PROJECT, b.id(), FailureDisposition.DECISION_KNOWN_ISSUE, "已知");
        service.assign(PROJECT, a.id(), 3L);

        FailureDispositionService.Ledger ledger = service.ledger(PROJECT);
        assertEquals(0, ledger.open());
        assertEquals(1, ledger.assigned());
        assertEquals(1, ledger.decided());
        assertEquals(1, ledger.exempted());
    }

    // ---------------------------------------------------------------- helpers

    private static void assertNotEqualsSafe(String a, String b) {
        assertFalse(a.equals(b), "指纹不应相同：" + a);
    }

    private static FailureDispositionService.FailureContext context(String code, String category,
                                                                  String original) {
        return context(code, category, original, code.hashCode() & 0xFFFFL);
    }

    private static FailureDispositionService.FailureContext context(String code, String category,
                                                                  String original, long caseId) {
        return new FailureDispositionService.FailureContext(caseId, code, "pytest", category,
                "tests/a.py:42", original, "RUN-20261112000000-test01");
    }

    private static final class FakeRepository implements FailureDispositionRepository {

        private final Map<Long, FailureDisposition> rows = new LinkedHashMap<>();
        private long seq = 0;

        @Override
        public Optional<FailureDisposition> find(long projectId, String fingerprint, long caseId) {
            return rows.values().stream()
                    .filter(r -> r.fingerprint().equals(fingerprint) && r.caseId() == caseId)
                    .findFirst();
        }

        @Override
        public Optional<FailureDisposition> findById(long projectId, long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public long insert(FailureDisposition row, Long operatorId) {
            long id = ++seq;
            rows.put(id, withId(row, id));
            return id;
        }

        @Override
        public void update(FailureDisposition row, Long operatorId) {
            rows.put(row.id(), row);
        }

        @Override
        public List<FailureDisposition> list(long projectId, String status) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<FailureDisposition> listByCase(long projectId, long caseId) {
            return rows.values().stream().filter(r -> r.caseId() == caseId).toList();
        }

        @Override
        public List<FailureDisposition> listExempted(long projectId) {
            return rows.values().stream().filter(FailureDisposition::exempted).toList();
        }

        @Override
        public long countByStatus(long projectId, String status) {
            return rows.values().stream().filter(r -> r.status().equals(status)).count();
        }

        private static FailureDisposition withId(FailureDisposition row, long id) {
            return new FailureDisposition(id, row.projectId(), row.caseId(), row.caseCode(),
                    row.kernel(), row.fingerprint(), row.category(), row.location(), row.status(),
                    row.assigneeId(), row.decision(), row.note(), row.occurrences(),
                    row.reopenCount(), row.reopenReason(), row.lastRunId(), row.decidedAt(),
                    row.closedAt(), row.createdAt() == null ? Instant.now() : row.createdAt(),
                    Instant.now());
        }
    }
}
