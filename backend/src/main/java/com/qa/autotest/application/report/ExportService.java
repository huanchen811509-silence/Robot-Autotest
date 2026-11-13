package com.qa.autotest.application.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.execution.ExecutionPaths;
import com.qa.autotest.application.execution.RunArtifact;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ExportRecordRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.ExportFormat;
import com.qa.autotest.domain.report.ExportMode;
import com.qa.autotest.domain.report.ExportRecord;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 报告导出（RPT-05）。
 *
 * <p>门禁口径：{@code mode=external} 且 {@code exportable=false} → 400 + 缺失键数组；
 * {@code mode=internal} 允许缺项，但 Word 页眉 / PDF 打印页都带红色内部标识。
 * 产物落到该 run 的产物目录（run.artifacts_dir 或 run_artifact 同级目录），
 * 同时写 export_record 与 audit_log，绝不返回没有落盘路径的成功响应。
 */
@Service
public class ExportService {

    private final ReportRepository reports;
    private final ReportTreeService trees;
    private final RunRepository runs;
    private final RunArtifactRepository artifacts;
    private final ExportRecordRepository exportRecords;
    private final DocxExporter docxExporter;
    private final PdfExportService pdfExportService;
    private final ReportExportService reportExports;
    private final AccessControlService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExportService(ReportRepository reports,
                         ReportTreeService trees,
                         RunRepository runs,
                         RunArtifactRepository artifacts,
                         ExportRecordRepository exportRecords,
                         DocxExporter docxExporter,
                         PdfExportService pdfExportService,
                         ReportExportService reportExports,
                         AccessControlService access,
                         AuditService audit,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.reports = reports;
        this.trees = trees;
        this.runs = runs;
        this.artifacts = artifacts;
        this.exportRecords = exportRecords;
        this.docxExporter = docxExporter;
        this.pdfExportService = pdfExportService;
        this.reportExports = reportExports;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 导出前质量自检结果（v5.6.0 / FR-v5.6-27）。
     *
     * @param passed  三项机检全过
     * @param missing 未通过项的可读说明；空表示全过
     * @param label   对外口径标记：{@code FINAL} 可直接交付，{@code DRAFT} 仅供内部评审
     */
    public record QualityGate(boolean passed, List<String> missing, String label) {

        public static final String FINAL = "FINAL";
        public static final String DRAFT = "DRAFT";

        public QualityGate {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /**
     * 导出结果：与接口返回字段一一对应。
     *
     * <p>v5.6.0 追加 {@code qualityGate}：导出不再「要么成功要么失败」，
     * 而是「导出了，但这份能不能对外发」——缺项时仍允许导出（内部评审要用），
     * 只降级为 DRAFT 并在文件名上标出来，避免草稿被当成正式件发出去。
     */
    public record ExportResult(long exportId,
                               String format,
                               String mode,
                               long bytes,
                               String sha256,
                               String downloadUrl,
                               QualityGate qualityGate) {
    }

    /** 下载定位结果。 */
    public record ExportDownload(Path path, String fileName, String contentType) {
    }

    public ExportResult export(long projectId, String runId, String modeRaw, String formatRaw) {
        validate(projectId, runId);
        ExportMode mode = ExportMode.fromWire(modeRaw)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "mode 只能是 external 或 internal"));
        ExportFormat format = ExportFormat.fromWire(formatRaw)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "format 只能是 pdf / docx / xlsx"));

        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);

        ReportRecord report = reports.find(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "报告不存在: " + runId));
        // 导出期二次拒绝（RPT-07）：按当前 metadata 重算门禁，不依赖 run.exportable 列的旧结论，
        // 保证「入库时算过」与「导出时再算一次」是同一条口径，库列被改错也放不过去。
        GateResult gate = MetadataGate.check(report.metadata());
        if (mode == ExportMode.EXTERNAL && !gate.exportable()) {
            List<String> missing = gate.missing();
            if (missing.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "外部版导出被拒绝：metadata 存在超长项，请先修正");
            }
            throw new ExportRejectedException(missing);
        }

        Path directory = resolveArtifactDirectory(projectId, runId);
        // 树只读一次：三格式共用同一份逐条结果，既省一次全表扫描，
        // 也让质量自检与正文必然一致（自检读 A、正文读 B 是最难查的一类不一致）。
        ReportTreeService.TreeSuite tree = trees.treeForPrint(projectId, runId);
        QualityGate qualityGate = qualityGate(report, gate, tree);
        String fileName = "report_" + safeName(runId) + "_" + mode.wireName()
                + (qualityGate.passed() ? "" : "_" + QualityGate.DRAFT)
                + "." + format.extension();
        Instant exportedAt = clock.instant();
        byte[] bytes = switch (format) {
            case PDF -> pdfExportService.render(projectId, runId, mode);
            case DOCX -> docxExporter.render(new ExportDocument(report, tree, mode, exportedAt,
                    operator()));
            // run 级导出的 xlsx 只给汇总计数 + metadata：模板化（按字段裁剪 + 自定义标题）
            // 的报告导出在 /api/test-reports/{id}/export，那条链路才有快照内模板可用。
            // v5.6.0 / FR-v5.6-23：追加「用例明细」sheet——只有汇总的 xlsx 拿到手
            // 第一件事就是问「哪几条失败了」，而失败原因与命令只存在于逐条结果里。
            case XLSX -> reportExports.export("xlsx",
                    summarySheet(report, mode, qualityGate.label()), Map.of(), detailRows(tree));
        };
        Path target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(target, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "导出文件落盘失败：" + ex.getMessage());
        }

        String sha256 = sha256(bytes);
        long exportId = exportRecords.insert(projectId, runId, format, mode, fileName,
                target.toAbsolutePath().toString(), sha256, bytes.length,
                TenantContext.userId(), "created");
        String downloadUrl = "/api/exports/" + exportId + "/download?projectId=" + projectId;
        audit.record("report_export", "report", runId, auditDetail(runId, format, mode, sha256));
        return new ExportResult(exportId, format.wireName(), mode.wireName(),
                bytes.length, sha256, downloadUrl, qualityGate);
    }

    /**
     * 导出前三项机检（FR-v5.6-27）。
     *
     * <p>三项选的是「拿出去会被挑回来」的三类问题，不是全部质量问题：
     * <ol>
     *   <li><b>metadata 十项</b>：缺 CASE_ID/DUT/... 的报告无法追溯，对外等于黑盒；</li>
     *   <li><b>必填列</b>：用例没有编号，评审对着 xlsx 无法回指台账；</li>
     *   <li><b>失败原因非空</b>：失败却没有原因，等于让人重跑一遍才知道发生了什么。</li>
     * </ol>
     * 缺项不阻断导出（内部评审正是要看问题），只把口径降为 DRAFT。
     */
    static QualityGate qualityGate(ReportRecord report, GateResult metadataGate,
                                   ReportTreeService.TreeSuite tree) {
        List<String> missing = new java.util.ArrayList<>(metadataGate.missing());
        int unnamed = 0;
        int reasonless = 0;
        for (ReportExportService.CaseDetailRow row : detailRows(tree)) {
            if (row.code() == null || row.code().isBlank()) {
                unnamed++;
            }
            boolean failed = "FAIL".equalsIgnoreCase(row.verdict())
                    || "ERROR".equalsIgnoreCase(row.verdict());
            if (failed && (row.failureMsg() == null || row.failureMsg().isBlank())) {
                reasonless++;
            }
        }
        if (unnamed > 0) {
            missing.add("用例编号缺失 " + unnamed + " 条");
        }
        if (reasonless > 0) {
            missing.add("失败原因缺失 " + reasonless + " 条");
        }
        return new QualityGate(missing.isEmpty(), missing,
                missing.isEmpty() ? QualityGate.FINAL : QualityGate.DRAFT);
    }

    public List<ExportRecord> history(long projectId, String runId) {
        validate(projectId, runId);
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return exportRecords.list(projectId, runId);
    }

    public ExportDownload download(long projectId, long exportId) {
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        if (exportId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "exportId 非法");
        }
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        ExportRecord record = exportRecords.find(projectId, exportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "导出记录不存在或不属于该项目"));
        if (record.filePath() == null || record.filePath().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出记录没有产物路径，请重新导出");
        }
        Path path = Path.of(record.filePath());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "导出文件已不存在（可能已被清理），请重新导出");
        }
        String fileName = record.fileName() == null || record.fileName().isBlank()
                ? "report_" + safeName(record.runId()) + "_" + record.mode().wireName()
                + "." + record.format().extension()
                : record.fileName();
        return new ExportDownload(path, fileName, record.format().contentType());
    }

    /**
     * 解析产物目录：优先 run.artifacts_dir，其次已登记的 run_artifact 同级目录。
     * 不硬编码任何项目路径。
     */
    private Path resolveArtifactDirectory(long projectId, String runId) {
        RunRecord run = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "运行不存在: " + runId));
        if (run.artifactsDir() != null && !run.artifactsDir().isBlank()) {
            return ExecutionPaths.resolve(run.artifactsDir());
        }
        for (RunArtifact artifact : artifacts.list(projectId, run.id())) {
            if (artifact.path() != null && !artifact.path().isBlank()) {
                Path parent = Path.of(artifact.path()).toAbsolutePath().getParent();
                if (parent != null) {
                    return parent;
                }
            }
        }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                "该运行尚未登记产物目录，无法导出（请先完成一次执行或解析报告）");
    }

    /**
     * run 级 xlsx 的视图：汇总计数 + C5 metadata 白名单，内部版把水印换成内部标识。
     * 与 docx / pdf 走同一份 ReportRecord，不另查库，避免三格式数字对不上。
     */
    private static Map<String, Object> summarySheet(ReportRecord report, ExportMode mode,
                                                    String qualityLabel) {
        Map<String, Object> sheet = ReportExportService.summaryView(report.runId(), report.state(),
                report.passCount(), report.failCount(), report.skipCount(), report.metadata());
        if (mode == ExportMode.INTERNAL) {
            sheet.put("watermark", ReportExportService.WATERMARK + " / INTERNAL");
        }
        // FR-v5.6-27：草稿要在件里也标出来，不能只靠文件名——文件被改名转发后
        // 只剩这一行还能证明它是草稿。
        sheet.put("qualityGate", qualityLabel);
        return sheet;
    }

    /**
     * 摊平套件树得到明细行（FR-v5.6-23）。
     *
     * <p>顺序即树的先序顺序，与报告页看到的顺序一致——用户拿着 xlsx 对屏幕时不该重排。
     * 命令列原样透传 {@code commandText}：{@code NONE} 也照写，
     * 因为明细 sheet 是「事实表」，把 NONE 改成空会让「没下发」与「没采集」混淆。
     */
    private static List<ReportExportService.CaseDetailRow> detailRows(
            ReportTreeService.TreeSuite root) {
        List<ReportExportService.CaseDetailRow> rows = new java.util.ArrayList<>();
        collectDetailRows(root, rows);
        return rows;
    }

    private static void collectDetailRows(ReportTreeService.TreeSuite suite,
                                          List<ReportExportService.CaseDetailRow> out) {
        if (suite == null) {
            return;
        }
        for (ReportTreeService.TreeCase item : suite.cases()) {
            out.add(new ReportExportService.CaseDetailRow(item.name(), item.verdict(),
                    item.durationMs(), item.failureMsg(), item.commandText()));
        }
        for (ReportTreeService.TreeSuite child : suite.suites()) {
            collectDetailRows(child, out);
        }
    }

    private String auditDetail(String runId, ExportFormat format, ExportMode mode, String sha256) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("format", format.wireName());
        detail.put("mode", mode.wireName());
        detail.put("sha256", sha256);
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return "{\"runId\":\"" + runId + "\"}";
        }
    }

    private static String operator() {
        String username = TenantContext.username();
        return username == null || username.isBlank() ? "unknown" : username;
    }

    private static String safeName(String runId) {
        return runId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static void validate(long projectId, String runId) {
        if (projectId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 非法");
        }
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }
    }
}
