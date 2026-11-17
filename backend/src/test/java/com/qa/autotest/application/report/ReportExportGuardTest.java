package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.TestReportRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.ReportState;
import com.qa.autotest.domain.report.TestReport;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.22.0 / FR-v5.22-06（W72-06）：报告级导出闸。
 *
 * <p>这组用例守的是三条**必须分开**的口径：
 * ① 撤回 / 归档 = 不可对外，任何 mode 都不放行；
 * ② metadata 缺项只拦对外（external），内部件仍要能导出（否则排查时无文件可看）；
 * ③ 判定只读快照——`REVOKED` 报告的 run 可能已被软删，回查 run 会 404 而不是 409，
 *    那正是 L-22-01 的口径分裂（同一份报告两个入口给相反答案）。
 */
class ReportExportGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("撤回的报告一律 409，internal 也不放行")
    void revokedIsBlockedInBothModes() {
        TestReportService service = service(report(ReportState.REVOKED, true, List.of()));
        TestReportService.ExportGuard external = service.exportGuard(1L, "external");
        TestReportService.ExportGuard internal = service.exportGuard(1L, "internal");
        assertEquals(409, external.status());
        assertEquals("CONFLICT", external.codeName());
        assertEquals("REVOKED", external.state());
        assertFalse(internal.allowed(), "撤回 ≠ 可导内部版：状态闸对 internal 同样生效");
        assertEquals(409, internal.status());
    }

    @Test
    @DisplayName("归档的报告同样 409")
    void archivedIsBlocked() {
        TestReportService service = service(report(ReportState.ARCHIVED, true, List.of()));
        TestReportService.ExportGuard guard = service.exportGuard(1L, "external");
        assertEquals(409, guard.status());
        assertTrue(guard.message().contains("归档"), "报错要说清是归档而不是撤回");
    }

    @Test
    @DisplayName("metadata 缺项：external 400 且透出缺失键，internal 放行")
    void metadataMissingBlocksExternalOnly() {
        TestReportService service = service(
                report(ReportState.FROZEN, false, List.of("STARTED_AT", "CASE_ID")));

        TestReportService.ExportGuard external = service.exportGuard(1L, "external");
        assertEquals(400, external.status());
        assertEquals(List.of("STARTED_AT", "CASE_ID"), external.metadataMissing());
        assertTrue(external.message().contains("STARTED_AT"), "错误信息要能直接看出缺哪项");

        assertTrue(service.exportGuard(1L, "internal").allowed(),
                "内部件不该被 metadata 闸拦住——排查时更需要文件");
    }

    @Test
    @DisplayName("可对外的冻结报告放行")
    void exportableFrozenIsAllowed() {
        TestReportService service = service(report(ReportState.FROZEN, true, List.of()));
        assertTrue(service.exportGuard(1L, "external").allowed());
        assertEquals(200, service.exportGuard(1L, "external").status());
    }

    @Test
    @DisplayName("绕过控制器直接调 export 时降级成异常，而不是静默出文件")
    void directExportStillFailsClosed() {
        TestReportService service = service(report(ReportState.REVOKED, true, List.of()));
        assertThrows(BusinessException.class, () -> service.export(1L, "xlsx", "external"));
    }

    @Test
    @DisplayName("默认 mode=external：不传 mode 的老调用方拿到与显式 external 一致的判定")
    void defaultModeIsExternal() {
        TestReportService metadataMissing = service(report(ReportState.DRAFT, false, List.of("DUT")));
        assertEquals(400, metadataMissing.exportGuard(1L, null).status());
    }

    // ---- 夹具 ----

    private static TestReport report(ReportState state, boolean exportable, List<String> missing) {
        try {
            String json = MAPPER.writeValueAsString(java.util.Map.of(
                    "runId", "RUN-W2-0001",
                    "exportable", exportable,
                    "metadataMissing", missing,
                    "passCount", 1, "failCount", 0, "skipCount", 0,
                    "criteriaConclusion", "PASS"));
            return new TestReport(1L, 7L, "RUN-W2-0001", ReportSourceType.RUN, null,
                    "验收报告", state, 1, "hash", json, "<html></html>", "summary",
                    Instant.parse("2026-11-17T00:00:00Z"), Instant.parse("2026-11-17T01:00:00Z"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TestReportService service(TestReport report) {
        TestReportRepository repository = proxy(TestReportRepository.class,
                (proxy, method, args) -> {
                    if ("find".equals(method.getName())) {
                        return Optional.of(report);
                    }
                    return defaultValue(method.getReturnType());
                });
        ReportViewRenderer renderer =
                new ReportViewRenderer(MAPPER, proxy(ProjectRepository.class,
                        (proxy, method, args) -> defaultValue(method.getReturnType())));
        return new TestReportService(
                repository,
                new ReportSnapshotService(MAPPER),
                renderer,
                new ReportExportService(),
                // 本组用例只走 exportGuard（只读快照），后五个依赖不会被触到，传 null 即可。
                null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (Optional.class.equals(type)) {
            return Optional.empty();
        }
        if (List.class.equals(type)) {
            return List.of();
        }
        return null;
    }
}
