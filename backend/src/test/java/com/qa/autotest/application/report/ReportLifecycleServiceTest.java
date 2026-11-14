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
    @DisplayName("软删 run：写 deleted_at/deleted_by，返回影响面并落审计")
    void deleteRun_softDeletes() {
        ReportLifecycleService service = stubbed(runRow(null), null, 2, 3, 0);

        ReportLifecycleService.DeleteResult result = service.deleteRun(7L, "RUN-1");

        assertTrue(result.deleted());
        assertEquals(2, result.impact().testReportExportCount());
        assertEquals(3, result.impact().runArtifactCount());
        verify(service).execute(
                org.mockito.ArgumentMatchers.argThat(
                        sql -> sql.contains("deleted_at = NOW(3)") && sql.contains("deleted_by")),
                anyList());
        verify(audit).record(eq("report.run.delete"), eq("run"), eq("RUN-1"), anyString());
    }

    @Test
    @DisplayName("重复删除幂等：不写库、不重复审计")
    void deleteRun_idempotent() {
        ReportLifecycleService service = stubbed(runRow("2026-11-14"), null, 0, 0, 0);

        ReportLifecycleService.DeleteResult result = service.deleteRun(7L, "RUN-1");

        assertFalse(result.deleted());
        verify(service, never()).execute(anyString(), anyList());
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("恢复：清 deleted_at/deleted_by")
    void restoreRun_restores() {
        ReportLifecycleService service = stubbed(runRow("2026-11-14"), null, 0, 0, 0);

        ReportLifecycleService.RunMeta meta = service.restoreRun(7L, "RUN-1");

        assertEquals("RUN-1", meta.runId());
        verify(service).execute(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("deleted_at = NULL")),
                anyList());
    }

    @Test
    @DisplayName("恢复未删记录幂等：不写库")
    void restoreRun_idempotentWhenVisible() {
        ReportLifecycleService service = stubbed(runRow(null), null, 0, 0, 0);

        service.restoreRun(7L, "RUN-1");

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
    @DisplayName("冻结报告拒绝删除：提示请先撤销冻结")
    void deleteTestReport_frozenRejected() {
        ReportLifecycleService service = stubbed(null, reportRow("FROZEN", null), 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTestReport(7L, 9L));

        assertEquals("请先撤销冻结", ex.getMessage());
        verify(service, never()).execute(anyString(), anyList());
    }

    @Test
    @DisplayName("冻结报告拒绝改标题")
    void updateTestReport_frozenTitleRejected() {
        ReportLifecycleService service = stubbed(null, reportRow("FROZEN", null), 0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTestReport(7L, 9L,
                        new ReportLifecycleService.TestReportUpdate("新标题", null, null)));

        assertEquals("请先撤销冻结", ex.getMessage());
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
}
