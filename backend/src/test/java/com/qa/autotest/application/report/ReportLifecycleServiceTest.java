package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** ReportLifecycleService 单测：不依赖真实 DB，用 spy 覆盖 query/execute 两个 JDBC 通道。 */
class ReportLifecycleServiceTest {

    private JdbcTemplate jdbc;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
    }

    /** spy 掉 query/execute：按 SQL 形态分派返回行，业务逻辑全部走真实实现。 */
    private ReportLifecycleService stubbed(Map<String, Object> runRow,
                                           Map<String, Object> reportRow,
                                           long exports, long artifacts, long visible) {
        ReportLifecycleService service = spy(new ReportLifecycleService(jdbc, audit));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (sql.contains("FROM run WHERE") && sql.contains("display_name")) {
                if (runRow != null) {
                    rows.add(runRow);
                }
            } else if (sql.contains("FROM test_report WHERE") && sql.contains("state")) {
                if (reportRow != null) {
                    rows.add(reportRow);
                }
            } else if (sql.contains("test_report_export")) {
                rows.add(Map.of("c", exports));
            } else if (sql.contains("run_artifact")) {
                rows.add(Map.of("c", artifacts));
            } else {
                rows.add(Map.of("c", visible));
            }
            return rows;
        }).when(service).query(anyString(), anyList());
        doReturn(1).when(service).execute(anyString(), anyList());
        return service;
    }

    private static Map<String, Object> runRow(Object deletedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("display_name", "旧名");
        row.put("remark", null);
        row.put("tags", null);
        row.put("deleted_at", deletedAt);
        return row;
    }

    private static Map<String, Object> reportRow(String state, Object deletedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 9L);
        row.put("run_id", "RUN-1");
        row.put("title", "标题");
        row.put("remark", null);
        row.put("tags", null);
        row.put("state", state);
        row.put("deleted_at", deletedAt);
        return row;
    }

    @Test
    @DisplayName("物理删 run：按序清子表并 DELETE 主表，返回影响面并落审计")
    void deleteRun_hardDeletes() {
        ReportLifecycleService service = stubbed(runRow(null), null, 2, 3, 0);

        ReportLifecycleService.DeleteResult result = service.deleteRun(7L, "RUN-1");

        assertTrue(result.deleted());
        assertEquals(2, result.impact().testReportExportCount());
        assertEquals(3, result.impact().runArtifactCount());
        // 6 张子表 + 1 张主表，全部是物理 DELETE，不许再出现 UPDATE deleted_at。
        verify(service, org.mockito.Mockito.times(7)).execute(anyString(), anyList());
        verify(service).execute(
                org.mockito.ArgumentMatchers.argThat(
                        sql -> sql.contains("DELETE FROM run WHERE")),
                anyList());
        verify(service, never()).execute(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("deleted_at = NOW")),
                anyList());
        verify(audit).record(eq("report.run.delete"), eq("run"), eq("RUN-1"), anyString());
    }

    @Test
    @DisplayName("历史软删行也物理清除（大师 2026-11-20 裁决：不再陈列）")
    void deleteRun_purgesLegacySoftDeleted() {
        ReportLifecycleService service = stubbed(runRow("2026-11-14"), null, 0, 0, 0);

        ReportLifecycleService.DeleteResult result = service.deleteRun(7L, "RUN-1");

        assertTrue(result.deleted(), "软删行必须被物理清除，不能返回 false 让它继续陈列");
        verify(service, org.mockito.Mockito.times(7)).execute(anyString(), anyList());
    }

    @Test
    @DisplayName("run 不存在时幂等：不写库、不重复审计")
    void deleteRun_idempotentWhenMissing() {
        ReportLifecycleService service = stubbed(null, null, 0, 0, 0);

        ReportLifecycleService.DeleteResult result = service.deleteRun(7L, "RUN-1");

        assertFalse(result.deleted());
        verify(service, never()).execute(anyString(), anyList());
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("物理删后恢复不可行：给出明确错误而不是静默成功")
    void restoreRun_rejectedAfterHardDelete() {
        ReportLifecycleService service = stubbed(runRow("2026-11-14"), null, 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreRun(7L, "RUN-1"));

        assertTrue(ex.getMessage().contains("无法恢复"));
        verify(service, never()).execute(anyString(), anyList());
    }

    @Test
    @DisplayName("编辑已软删的 run 报 NOT_FOUND")
    void updateRun_rejectsDeleted() {
        ReportLifecycleService service = stubbed(runRow("2026-11-14"), null, 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRun(7L, "RUN-1",
                        new ReportLifecycleService.RunUpdate("新名", null, null)));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    @DisplayName("冻结报告允许直接物理删（大师 2026-11-20 裁决）")
    void deleteTestReport_frozenAllowed() {
        ReportLifecycleService service = stubbed(null, reportRow("FROZEN", null), 0, 0, 0);

        ReportLifecycleService.DeleteResult result = service.deleteTestReport(7L, 9L);

        assertTrue(result.deleted());
        verify(service).execute(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("DELETE FROM test_report")),
                anyList());
        verify(audit).record(eq("report.test.delete"), eq("test_report"), eq("9"), anyString());
    }

    @Test
    @DisplayName("归档报告允许直接物理删")
    void deleteTestReport_archivedAllowed() {
        ReportLifecycleService service = stubbed(null, reportRow("ARCHIVED", null), 0, 0, 0);

        assertTrue(service.deleteTestReport(7L, 9L).deleted());
    }

    @Test
    @DisplayName("冻结报告拒绝改标题")
    void updateTestReport_frozenTitleRejected() {
        ReportLifecycleService service = stubbed(null, reportRow("FROZEN", null), 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTestReport(7L, 9L,
                        new ReportLifecycleService.TestReportUpdate("新标题", null, null)));

        assertEquals("已冻结，请先解冻", ex.getMessage());
    }

    @Test
    @DisplayName("冻结报告允许只改 remark/tags")
    void updateTestReport_frozenRemarkAllowed() {
        ReportLifecycleService service = stubbed(null, reportRow("FROZEN", null), 0, 0, 0);

        ReportLifecycleService.TestReportMeta meta = service.updateTestReport(7L, 9L,
                new ReportLifecycleService.TestReportUpdate(null, "备注", "t1"));

        assertEquals("FROZEN", meta.state());
        verify(service).execute(anyString(), anyList());
    }

    @Test
    @DisplayName("影响面 count：exports 与 artifacts 分别取数")
    void impact_countsBoth() {
        ReportLifecycleService service = stubbed(null, null, 5, 11, 0);

        ReportLifecycleService.RunImpact impact = service.impact(7L, "RUN-1");

        assertEquals(5, impact.testReportExportCount());
        assertEquals(11, impact.runArtifactCount());
    }

    @Test
    @DisplayName("默认过滤：可见计数 SQL 带 deleted_at IS NULL")
    void countVisibleRuns_filtersDeleted() {
        ReportLifecycleService service = stubbed(null, null, 0, 0, 4);

        assertEquals(4, service.countVisibleRuns(7L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(service, atLeastOnce()).query(sql.capture(), anyList());
        assertTrue(sql.getAllValues().stream().anyMatch(v -> v.contains("deleted_at IS NULL")));
    }

    @Test
    @DisplayName("旧数据 deleted_at 为 NULL 视为可见（未删除）")
    void oldDataNullDeletedAt_isVisible() {
        assertFalse(ReportLifecycleService.isDeleted(runRow(null)));
        assertTrue(ReportLifecycleService.isDeleted(runRow("2026-11-14")));
        assertTrue(ReportLifecycleService.isFrozen(reportRow("FROZEN", null)));
        assertFalse(ReportLifecycleService.isFrozen(reportRow("DRAFT", null)));
    }

    @Test
    @DisplayName("空 runId 报 INVALID_ARGUMENT")
    void blankRunIdRejected() {
        ReportLifecycleService service = stubbed(null, null, 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteRun(7L, "  "));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        verify(service, never()).query(anyString(), anyList());
    }
    // ---------------------------------------------------------------- 批量软删（v5.9.0 / W59-20）

    @Test
    @DisplayName("批量软删：逐条删，deleted 与影响面都只算真删掉的条数")
    void batchDeleteCountsOnlyReallyDeleted() {
        ReportLifecycleService service = stubbed(runRow(null), reportRow("DRAFT", null), 3, 2, 10);

        ReportLifecycleService.BatchDeleteResult result = service.deleteRuns(7L,
                List.of("RUN-1", "RUN-2"));

        assertEquals(2, result.deleted());
        assertEquals(0, result.failed());
        // 每条报告各带 3 份导出、2 个产物；两条就是累加，不能报「勾选数」
        assertEquals(6, result.impact().testReportExportCount());
        assertEquals(4, result.impact().runArtifactCount());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    @DisplayName("批量物理删：单条不存在视为幂等，不中断其余条")
    void batchDeleteToleratesMissingRow() {
        ReportLifecycleService service = stubbed(null, null, 1, 1, 1);

        ReportLifecycleService.BatchDeleteResult result = service.deleteRuns(7L, List.of("RUN-9"));

        assertEquals(0, result.deleted());
        assertEquals(0, result.failed(), "不存在的行是幂等成功，不是失败");
        assertTrue(result.failures().isEmpty());
    }

    @Test
    @DisplayName("批量软删：空选择是 400，不是「删了 0 条」的假成功")
    void batchDeleteRejectsEmptySelection() {
        ReportLifecycleService service = stubbed(runRow(null), null, 0, 0, 1);

        assertThrows(BusinessException.class, () -> service.deleteRuns(7L, List.of()));
        assertThrows(BusinessException.class, () -> service.deleteRuns(7L, null));
    }
}
