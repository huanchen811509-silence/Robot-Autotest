package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ExportRecordRepository;
import com.qa.autotest.application.repository.ReportReadRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.ExportFormat;
import com.qa.autotest.domain.report.ExportMode;
import com.qa.autotest.domain.report.ExportRecord;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.RunCaseRow;
import com.qa.autotest.domain.report.RunSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 导出编排（RPT-05）：门禁、成功落盘、内部版、export_record、跨项目下载拒绝。
 */
class ExportServiceTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "RPT05-EXPORT-001";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-03T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir
    Path artifactDir;

    private FakeReportRepository reports;
    private FakeExportRecordRepository exportRecords;
    private FakeAuditRepository audit;
    private ExportService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "tester", PROJECT);
        reports = new FakeReportRepository();
        exportRecords = new FakeExportRecordRepository();
        audit = new FakeAuditRepository();
        service = buildService();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("外部缺项 → 400 + metadataMissing 数组")
    void externalRejectsWhenMetadataMissing() {
        reports.report = report(false, List.of("DUT", "LINK"), metadataMissingDutAndLink());

        ExportRejectedException ex = assertThrows(ExportRejectedException.class,
                () -> service.export(PROJECT, RUN_ID, "external", "docx"));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertEquals(List.of("DUT", "LINK"), ex.metadataMissing());
        assertTrue(ex.getMessage().contains("外部版导出被拒绝"));
        assertTrue(exportRecords.rows.isEmpty(), "被拒绝的导出不能写 export_record");
        assertFalse(Files.exists(artifactDir.resolve("report_" + RUN_ID + "_external.docx")));
    }

    @Test
    @DisplayName("库列写着可导出但 metadata 缺项 → 导出期重算仍拒绝（RPT-07）")
    void externalRejectsWhenStaleColumnSaysExportable() {
        // run.exportable 由入库时写入，可能因重析/手工改库而与 metadata 脱节；
        // 导出期必须按 metadata 重算，不能信这一列。
        reports.report = report(true, List.of(), metadataMissingDutAndLink());

        ExportRejectedException ex = assertThrows(ExportRejectedException.class,
                () -> service.export(PROJECT, RUN_ID, "external", "docx"));

        assertEquals(List.of("DUT", "LINK"), ex.metadataMissing());
        assertTrue(exportRecords.rows.isEmpty(), "被拒绝的导出不能写 export_record");
        assertFalse(Files.exists(artifactDir.resolve("report_" + RUN_ID + "_external.docx")));
    }

    @Test
    @DisplayName("外部齐全 → 成功并落盘")
    void externalSucceedsWhenComplete() throws IOException {
        reports.report = report(true, List.of(), fullMetadata());

        ExportService.ExportResult result = service.export(PROJECT, RUN_ID, "external", "docx");

        Path file = artifactDir.resolve("report_" + RUN_ID + "_external.docx");
        assertTrue(Files.isRegularFile(file), "导出文件必须真实落盘");
        assertEquals(Files.size(file), result.bytes());
        assertTrue(result.bytes() > 0);
        assertEquals(64, result.sha256().length());
        assertTrue(result.downloadUrl().contains("/api/exports/" + result.exportId() + "/download"));
        assertTrue(result.downloadUrl().contains("projectId=" + PROJECT));
    }

    @Test
    @DisplayName("内部缺项 → 成功且产物标记 internal（红色页眉）")
    void internalAllowsMissingAndMarksDocument() throws IOException {
        reports.report = report(false, List.of("DUT"), metadataMissingDutAndLink());

        ExportService.ExportResult result = service.export(PROJECT, RUN_ID, "internal", "docx");

        assertEquals("internal", result.mode());
        assertEquals("docx", result.format());
        Path file = artifactDir.resolve("report_" + RUN_ID + "_internal.docx");
        assertTrue(Files.isRegularFile(file));
        String header = zipEntry(Files.readAllBytes(file), "word/header1.xml");
        assertTrue(header.contains(DocxExporter.INTERNAL_HEADER));
        assertTrue(header.contains("FF0000"));
    }

    @Test
    @DisplayName("export_record 写入 sha256 / bytes / 模式 / 操作人")
    void writesExportRecordWithDigestAndSize() throws IOException {
        reports.report = report(true, List.of(), fullMetadata());

        ExportService.ExportResult result = service.export(PROJECT, RUN_ID, "external", "docx");

        assertEquals(1, exportRecords.rows.size());
        ExportRecord record = exportRecords.rows.get(0);
        assertEquals(result.exportId(), record.id());
        assertEquals(PROJECT, record.projectId());
        assertEquals(ExportFormat.DOCX, record.format());
        assertEquals(ExportMode.EXTERNAL, record.mode());
        assertEquals(result.sha256(), record.sha256());
        assertEquals(result.bytes(), record.sizeBytes());
        assertEquals("created", record.status());
        assertEquals(1L, record.requestedBy());
        assertEquals("report_" + RUN_ID + "_external.docx", record.fileName());
        assertEquals(1, audit.entries.size(), "每次导出必须写审计");
        assertTrue(audit.entries.get(0).contains("report_export"));
        assertTrue(audit.entries.get(0).contains(result.sha256()));
    }

    @Test
    @DisplayName("下载跨项目 → 拒绝（查不到该记录）")
    void downloadRejectsOtherProject() {
        reports.report = report(true, List.of(), fullMetadata());
        ExportService.ExportResult result = service.export(PROJECT, RUN_ID, "external", "docx");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.download(PROJECT + 1, result.exportId()));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertTrue(ex.getMessage().contains("不属于该项目"));
    }

    @Test
    @DisplayName("下载归属正确时返回文件名与 Content-Type")
    void downloadReturnsOwnedFile() throws IOException {
        reports.report = report(true, List.of(), fullMetadata());
        ExportService.ExportResult result = service.export(PROJECT, RUN_ID, "external", "docx");

        ExportService.ExportDownload download = service.download(PROJECT, result.exportId());
        assertEquals("report_" + RUN_ID + "_external.docx", download.fileName());
        assertEquals(ExportFormat.DOCX.contentType(), download.contentType());
        assertTrue(Files.isRegularFile(download.path()));
    }

    private ExportService buildService() {
        ExportProperties props = new ExportProperties();
        props.setPrintBaseUrl("http://127.0.0.1:5173");
        props.setTimeoutSeconds(5);
        ReportTreeService trees = new ReportTreeService(readRepository(), passAccess());
        PdfExportService pdf = new PdfExportService(
                new PrintTokenService(CLOCK), new PdfRenderer(props, List.of("no-browser")), props);
        return new ExportService(reports, trees, runRepository(), artifactRepository(),
                exportRecords, new DocxExporter(), pdf, new ReportExportService(),
                passAccess(), new AuditService(audit), new ObjectMapper(), CLOCK);
    }

    private static AccessControlService passAccess() {
        return new AccessControlService(null, null) {
            @Override
            public void bindProject(long projectId) {
                TenantContext.setProjectId(projectId);
            }

            @Override
            public void require(PermissionResource resource, PermissionAction action) {
                // 单测聚焦导出编排，权限矩阵由 AuthorizationBoundaryTest 覆盖
            }
        };
    }

    private static ReportReadRepository readRepository() {
        return proxy(ReportReadRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findRun":
                    return Optional.of(new RunSnapshot(PROJECT, RUN_ID, "finished",
                            "GPU-01", "GPU-01 mlx5_0 -> SW-01", CLOCK.instant()));
                case "listCases":
                    return List.of(new RunCaseRow("suites/a.robot.case-1", "PASS", 12L, null),
                            new RunCaseRow("suites/b.robot.case-2", "FAIL", 34L, "断言失败"));
                case "listKeywords":
                    return List.<KeywordRow>of();
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private RunRepository runRepository() {
        RunRecord run = new RunRecord(11L, RUN_ID, PROJECT, null, RunState.FINISHED, 0, 1,
                0, 2, 1, 1, null, null, null, artifactDir.toAbsolutePath().toString(),
                CLOCK.instant(), CLOCK.instant(), CLOCK.instant(), null, null);
        return proxy(RunRepository.class, (proxy, method, args) ->
                "findByRunId".equals(method.getName())
                        ? Optional.of(run) : defaultValue(method.getReturnType()));
    }

    private static RunArtifactRepository artifactRepository() {
        return proxy(RunArtifactRepository.class, (proxy, method, args) ->
                defaultValue(method.getReturnType()));
    }

    /**
     * RPT-07 residual (2026-11-06): the export-time metadata recomputation used to be
     * verified only for docx. Both wire formats must reject on the same gate, otherwise
     * a future format-specific branch could silently bypass it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    @DisplayName("RPT-07 导出期重算：pdf/docx 两种格式都必须按 metadata 拒绝")
    void externalRejectsMissingMetadataForEveryFormat(String format) {
        reports.report = report(true, List.of(), metadataMissingDutAndLink());

        ExportRejectedException ex = assertThrows(ExportRejectedException.class,
                () -> service.export(PROJECT, RUN_ID, "external", format));

        assertEquals(List.of("DUT", "LINK"), ex.metadataMissing(), format);
        assertTrue(exportRecords.rows.isEmpty(), format + " 被拒绝时不得写 export_record");
    }

    private static ReportRecord report(boolean exportable, List<String> missing,
                                       Map<String, String> metadata) {
        return new ReportRecord(PROJECT, RUN_ID, "finished", exportable, missing, metadata,
                1, 1, 0, CLOCK.instant().minusSeconds(120), CLOCK.instant());
    }

    private static Map<String, String> fullMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : MetadataGate.REQUIRED) {
            metadata.put(key, "V-" + key);
        }
        return metadata;
    }

    private static Map<String, String> metadataMissingDutAndLink() {
        Map<String, String> metadata = fullMetadata();
        metadata.remove("DUT");
        metadata.remove("LINK");
        return metadata;
    }

    private static String zipEntry(byte[] bytes, String name) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                entry = zip.getNextEntry();
            }
        }
        throw new AssertionError("docx 内未找到 " + name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
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
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (PageResult.class.equals(type)) {
            return PageResult.empty(1, 20);
        }
        return null;
    }

    /** 报告仓储伪造：只提供 find，其余按空实现。 */
    static final class FakeReportRepository implements ReportRepository {

        ReportRecord report;

        @Override
        public void replace(long projectId, long runDbId, ParsedReport parsed,
                            GateResult gate, Instant startedAt) {
            // 导出不写报告
        }

        @Override
        public Optional<ReportRecord> find(long projectId, String runId) {
            return report != null && projectId == PROJECT && RUN_ID.equals(runId)
                    ? Optional.of(report) : Optional.empty();
        }

        @Override
        public PageResult<ReportRecord> page(long projectId, int page, int size) {
            return PageResult.empty(page, size);
        }

        @Override
        public void updateGate(long projectId, String runId, GateResult gate) {
            // 导出路径不重算门禁
        }
    }

    /** 导出记录仓储伪造：保存实际插入字段，供断言校验。 */
    static final class FakeExportRecordRepository implements ExportRecordRepository {

        final List<ExportRecord> rows = new ArrayList<>();
        private long nextId = 100L;

        @Override
        public long insert(long projectId, String runId, ExportFormat format, ExportMode mode,
                           String fileName, String filePath, String sha256, long sizeBytes,
                           Long requestedBy, String status) {
            long id = nextId++;
            rows.add(new ExportRecord(id, projectId, runId, format, mode, fileName, filePath,
                    sha256, sizeBytes, status, requestedBy, CLOCK.instant()));
            return id;
        }

        @Override
        public List<ExportRecord> list(long projectId, String runId) {
            return rows.stream()
                    .filter(row -> row.projectId() == projectId && row.runId().equals(runId))
                    .toList();
        }

        @Override
        public Optional<ExportRecord> find(long projectId, long exportId) {
            return rows.stream()
                    .filter(row -> row.projectId() == projectId && row.id() == exportId)
                    .findFirst();
        }
    }

    /** 审计伪造：把动作 + 明细拼成一条字符串。 */
    static final class FakeAuditRepository implements AuditRepository {

        final List<String> entries = new ArrayList<>();

        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
            entries.add(action + "|" + resourceType + "|" + resourceId + "|" + detail);
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return List.of();
        }
    }
}
