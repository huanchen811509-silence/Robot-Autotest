package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.MetadataOverlay;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HRD-02：{@code run:metadata-patch} 的补录语义与审计摘要。
 *
 * <p>用内存仓储 + 同步执行器的真实 AuditService，不连数据库；权限服务用 mock，
 * 只关心「补录做了什么、留了什么痕」。
 */
class RunMetadataServiceTest {

    private static final long PROJECT = 7L;
    private static final long OPERATOR = 3L;
    private static final String RUN = "run-1";

    private RunMetadataService service;
    private RunRepository runs;
    private MemoryMetadata metadata;
    private MemoryReports reports;
    private RecordingAudit auditRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set(OPERATOR, "engineer", PROJECT);
        runs = mock(RunRepository.class);
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run()));
        metadata = new MemoryMetadata();
        reports = new MemoryReports(metadata);
        auditRepo = new RecordingAudit();
        service = new RunMetadataService(runs, metadata, reports,
                mock(AccessControlService.class), new AuditService(auditRepo, Runnable::run));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("补录写入并留痕：摘要含新值与 created=true")
    void patchWritesAndAudits() {
        Map<String, String> result = service.patch(PROJECT, RUN,
                Map.of("DUT", "GPU-01", "LINK", "GPU-01 mlx5_0 -> SW-01"));

        assertEquals("GPU-01", result.get("DUT"));
        assertEquals("GPU-01 mlx5_0 -> SW-01", result.get("LINK"));
        AuditEntry entry = single();
        assertEquals("run:metadata-patch", entry.action());
        assertEquals("run", entry.resourceType());
        assertEquals(RUN, entry.resourceId());
        // 补录体是 Map，遍历顺序不定，只断言键值都在摘要里
        assertTrue(entry.summary().contains("after={"), entry.summary());
        assertTrue(entry.summary().contains("DUT=GPU-01"), entry.summary());
        assertTrue(entry.summary().contains("LINK=GPU-01 mlx5_0 -> SW-01"), entry.summary());
        assertTrue(entry.summary().contains("created=true"), entry.summary());
    }

    @Test
    @DisplayName("覆盖写：摘要同时含前值与新值")
    void patchOverwriteRecordsBeforeAndAfter() {
        metadata.store.put("DUT", "GPU-OLD");

        service.patch(PROJECT, RUN, Map.of("DUT", "GPU-NEW"));

        assertEquals("GPU-NEW", metadata.store.get("DUT"));
        AuditEntry entry = single();
        assertTrue(entry.summary().contains("before={DUT=GPU-OLD}"), entry.summary());
        assertTrue(entry.summary().contains("after={DUT=GPU-NEW}"), entry.summary());
    }

    @Test
    @DisplayName("空串表示删除：只记前值，后值标记删除")
    void emptyValueDeletes() {
        metadata.store.put("BASELINE", "188~195 Gbps");

        Map<String, String> result = service.patch(PROJECT, RUN, Map.of("BASELINE", ""));

        assertFalse(result.containsKey("BASELINE"));
        AuditEntry entry = single();
        assertTrue(entry.summary().contains("before={BASELINE=188~195 Gbps}"), entry.summary());
        assertTrue(entry.summary().contains("after={BASELINE=（删除）}"), entry.summary());
    }

    @Test
    @DisplayName("值未变化时不落库、不写审计")
    void unchangedWritesNothing() {
        metadata.store.put("DUT", "GPU-01");

        service.patch(PROJECT, RUN, Map.of("DUT", "GPU-01"));

        assertEquals(0, metadata.saves, "未变化不应落库");
        assertTrue(auditRepo.entries.isEmpty(), "未变化不应产生审计");
    }

    @Test
    @DisplayName("键名必须全大写，小写键拒绝且不写审计")
    void lowerCaseKeyRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.patch(PROJECT, RUN, Map.of("dut", "GPU-01")));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertEquals(0, metadata.saves);
        assertTrue(auditRepo.entries.isEmpty());
    }

    @Test
    @DisplayName("值超过 200 字符拒绝")
    void tooLongValueRejected() {
        String longValue = "x".repeat(201);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.patch(PROJECT, RUN, Map.of("NOTE", longValue)));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertEquals(0, metadata.saves);
    }

    @Test
    @DisplayName("运行不存在时返回 NOT_FOUND，不碰 metadata")
    void missingRunNotFound() {
        when(runs.findByRunId(PROJECT, "run-404")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.patch(PROJECT, "run-404", Map.of("DUT", "GPU-01")));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertEquals(0, metadata.saves);
        assertTrue(auditRepo.entries.isEmpty());
    }

    @Test
    @DisplayName("空的补录体直接拒绝")
    void emptyPatchRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.patch(PROJECT, RUN, Map.of()));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
    }

    @Test
    @DisplayName("补录补齐十项后门禁转为可导出")
    void patchFillingAllTenTurnsGateGreen() {
        service.patch(PROJECT, RUN, tenItems());

        assertTrue(reports.lastGate.exportable(), "十项齐全应可对外导出");
        assertTrue(reports.lastGate.missing().isEmpty());
        assertTrue(reports.lastGate.tooLong().isEmpty());
    }

    @Test
    @DisplayName("删除必填项后门禁回落：missing 指向被删项")
    void deletingRequiredKeyTurnsGateRed() {
        metadata.store.putAll(tenItems());

        service.patch(PROJECT, RUN, Map.of("DUT", ""));

        assertFalse(reports.lastGate.exportable(), "缺 DUT 不可对外导出");
        assertEquals(List.of("DUT"), reports.lastGate.missing());
    }

    // ------------------------------------------------------------------

    private static Map<String, String> tenItems() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("CASE_ID", "CAS-001");
        all.put("RUN_ID", "RUN-1-20261106");
        all.put("QA_PROJECT", "robot-autotest");
        all.put("DUT", "GPU-01");
        all.put("LINK", "GPU-01 mlx5_0 -> SW-01");
        all.put("SW_BUILD", "Ubuntu 24.04 / OFED 24.10");
        all.put("BASELINE", "188~195 Gbps");
        all.put("OPERATOR", "evergreen");
        all.put("STARTED_AT", "2026-11-06T02:15:40Z");
        all.put("CASE_KIND", "perf-throughput");
        return all;
    }

    private AuditEntry single() {
        assertEquals(1, auditRepo.entries.size(), "应恰好产生一条审计");
        return auditRepo.entries.get(0);
    }

    private static RunRecord run() {
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        return new RunRecord(1L, RUN, PROJECT, null, RunState.FINISHED, 5, 1, 1, 3, 3, 0,
                null, null, null, "/tmp/run-1", now, now, now, null, null);
    }

    /** 内存补录仓储。 */
    private static final class MemoryMetadata implements RunMetadataRepository {
        private final Map<String, String> store = new LinkedHashMap<>();
        private int saves;

        @Override
        public Map<String, String> findAll(long projectId, String runId) {
            return new LinkedHashMap<>(store);
        }

        @Override
        public void save(long projectId, String runId, Map<String, String> values,
                         Collection<String> removed, Long operatorId) {
            removed.forEach(store::remove);
            store.putAll(values);
            saves++;
        }
    }

    /** 内存报告仓储：find 走真实的 MetadataOverlay，只记录最近一次回写的门禁结论。 */
    private static final class MemoryReports implements ReportRepository {
        private final MemoryMetadata metadata;
        private GateResult lastGate;

        private MemoryReports(MemoryMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public void replace(long projectId, long runDbId, ParsedReport parsed,
                            GateResult gate, Instant startedAt) {
            throw new UnsupportedOperationException("补录不改解析结果");
        }

        @Override
        public Optional<ReportRecord> find(long projectId, String runId) {
            Instant now = Instant.parse("2026-11-06T00:00:00Z");
            ReportRecord parsed = new ReportRecord(projectId, runId, "FINISHED", false, List.of(),
                    Map.of(), 1, 0, 0, now, now);
            return Optional.of(MetadataOverlay.apply(parsed, metadata.findAll(projectId, runId)));
        }

        @Override
        public PageResult<ReportRecord> page(long projectId, int page, int size) {
            throw new UnsupportedOperationException("补录不涉及列表");
        }

        @Override
        public void updateGate(long projectId, String runId, GateResult gate) {
            this.lastGate = gate;
        }
    }

    /** 记录型审计仓库。 */
    private static final class RecordingAudit implements AuditRepository {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
            insertEntry(new AuditEntry(null, projectId, actorId, TenantContext.username(), action,
                    resourceType, resourceId, detail, detail, null, Instant.now()));
        }

        @Override
        public void insertEntry(AuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return entries.stream().map(AuditEntry::action).limit(limit).toList();
        }
    }
}
