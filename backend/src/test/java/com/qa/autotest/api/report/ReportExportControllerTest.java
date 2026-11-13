package com.qa.autotest.api.report;

import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.report.ExportRejectedException;
import com.qa.autotest.application.report.ExportService;
import com.qa.autotest.application.report.PrintReportService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ExportFormat;
import com.qa.autotest.domain.report.ExportMode;
import com.qa.autotest.domain.report.ExportRecord;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RPT-05 导出接口层：参数默认值、缺项 400 的 {@code metadataMissing}、下载响应头与 404 映射。
 *
 * <p>服务层已覆盖「外部版缺项拒绝 / 内部版放行」，但控制器薄壳上有三处只在这一层成立：
 * {@code mode}/{@code format} 的默认值、缺项被翻译成 400 且把数组放进 {@code data}、
 * 下载必须带 {@code Content-Disposition} 与正确 Content-Type。此前这些都没被 MockMvc 钉住。
 */
class ReportExportControllerTest {

    /** standalone MockMvc 默认把 Instant 写成 epoch 秒（BigDecimal），断言用 comparesEqualTo 比数值。 */
    private static final BigDecimal EXPORTED_AT =
            BigDecimal.valueOf(Instant.parse("2026-11-06T02:00:00Z").getEpochSecond());
    private static final BigDecimal EXPIRES_AT =
            BigDecimal.valueOf(Instant.parse("2026-11-06T02:10:00Z").getEpochSecond());

    private ExportService exports;
    private PrintReportService prints;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        exports = mock(ExportService.class);
        prints = mock(PrintReportService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ReportExportController(exports, prints),
                        new ExportDownloadController(exports))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("导出：不传 mode/format 时按 external/pdf 透传，返回下载地址")
    void export_默认外部版PDF() throws Exception {
        when(exports.export(1L, "RUN-1", "external", "pdf"))
                .thenReturn(new ExportService.ExportResult(9L, "pdf", "external", 1234L,
                        "abc", "/api/exports/9/download?projectId=1",
                        new ExportService.QualityGate(true, java.util.List.of(),
                                ExportService.QualityGate.FINAL)));

        mvc.perform(post("/api/reports/{runId}/export", "RUN-1").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportId").value(9))
                .andExpect(jsonPath("$.data.format").value("pdf"))
                .andExpect(jsonPath("$.data.mode").value("external"))
                .andExpect(jsonPath("$.data.bytes").value(1234))
                .andExpect(jsonPath("$.data.downloadUrl")
                        .value("/api/exports/9/download?projectId=1"))
                .andExpect(jsonPath("$.data.qualityGate.passed").value(true))
                .andExpect(jsonPath("$.data.qualityGate.label").value("FINAL"));

        verify(exports).export(1L, "RUN-1", "external", "pdf");
    }

    @Test
    @DisplayName("导出：internal + docx 原样透传，控制器不得把缺项运行强制成外部版")
    void export_内部版Docx() throws Exception {
        when(exports.export(1L, "RUN-1", "internal", "docx"))
                .thenReturn(new ExportService.ExportResult(10L, "docx", "internal", 2048L,
                        "def", "/api/exports/10/download?projectId=1",
                        new ExportService.QualityGate(false, java.util.List.of("DUT"),
                                ExportService.QualityGate.DRAFT)));

        mvc.perform(post("/api/reports/{runId}/export", "RUN-1")
                        .param("projectId", "1")
                        .param("mode", "internal")
                        .param("format", "docx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("internal"))
                .andExpect(jsonPath("$.data.format").value("docx"));
    }

    @Test
    @DisplayName("外部版缺项：400，缺失项数组放在 data.metadataMissing 供前端逐项展示")
    void export_缺项返回400与缺失清单() throws Exception {
        when(exports.export(1L, "RUN-1", "external", "pdf"))
                .thenThrow(new ExportRejectedException(List.of("DUT", "LINK")));

        mvc.perform(post("/api/reports/{runId}/export", "RUN-1").param("projectId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.metadataMissing[0]").value("DUT"))
                .andExpect(jsonPath("$.data.metadataMissing[1]").value("LINK"))
                .andExpect(jsonPath("$.message").value(containsString("DUT, LINK")));
    }

    @Test
    @DisplayName("下载：按 exportId 返回附件流，Content-Type 与文件名都来自导出记录")
    void download_返回附件流(@TempDir Path tmp) throws Exception {
        Path artifact = tmp.resolve("report.pdf");
        Files.writeString(artifact, "%PDF-1.4 fake", StandardCharsets.UTF_8);
        when(exports.download(1L, 9L))
                .thenReturn(new ExportService.ExportDownload(artifact, "测试报告.pdf", "application/pdf"));

        mvc.perform(get("/api/exports/{exportId}/download", 9L).param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                // 中文名按 RFC 5987 编码，不能直接塞进 filename=
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''")))
                .andExpect(content().bytes("%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8)));

        verify(exports).download(1L, 9L);
    }

    @Test
    @DisplayName("下载：不属于本项目或记录不存在时 404，不能退化成 500")
    void download_不存在返回404() throws Exception {
        when(exports.download(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "导出记录不存在"));

        mvc.perform(get("/api/exports/{exportId}/download", 99L).param("projectId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("导出历史：字段名与前端一致，时间为 UTC Instant")
    void history_映射字段() throws Exception {
        when(exports.history(1L, "RUN-1")).thenReturn(List.of(new ExportRecord(9L, 1L, "RUN-1",
                ExportFormat.PDF, ExportMode.EXTERNAL, "RUN-1.pdf", "/tmp/RUN-1.pdf",
                "abc", 1234L, "created", 5L, Instant.parse("2026-11-06T02:00:00Z"))));

        mvc.perform(get("/api/reports/{runId}/exports", "RUN-1").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].exportId").value(9))
                .andExpect(jsonPath("$.data[0].format").value("pdf"))
                .andExpect(jsonPath("$.data[0].mode").value("external"))
                .andExpect(jsonPath("$.data[0].fileName").value("RUN-1.pdf"))
                .andExpect(jsonPath("$.data[0].bytes").value(1234))
                .andExpect(jsonPath("$.data[0].createdAt").value(comparesEqualTo(EXPORTED_AT)));
    }

    @Test
    @DisplayName("打印 token：不传 mode 时按外部版发放，返回一次性地址与过期时间")
    void printToken_默认外部版() throws Exception {
        when(prints.issue(1L, "RUN-1", "external"))
                .thenReturn(new PrintReportService.IssuedPrintToken("tok-1",
                        Instant.parse("2026-11-06T02:10:00Z"),
                        "/print/tok-1", "external"));

        mvc.perform(post("/api/reports/{runId}/print-token", "RUN-1").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("tok-1"))
                .andExpect(jsonPath("$.data.printUrl").value("/print/tok-1"))
                .andExpect(jsonPath("$.data.expiresAt").value(comparesEqualTo(EXPIRES_AT)));

        verify(prints).issue(anyLong(), anyString(), anyString());
    }
}
