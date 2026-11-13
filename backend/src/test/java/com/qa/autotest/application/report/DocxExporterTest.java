package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.domain.report.ExportMode;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Word 导出结构（RPT-05）：十项 metadata、缺失标红、内部版页眉、超长失败消息截断、zip 合法。
 */
class DocxExporterTest {

    private static final String RUN_ID = "RPT05-DOCX-001";
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

    private final DocxExporter exporter = new DocxExporter();

    @Test
    @DisplayName("十项 metadata 全部写入首页表格")
    void writesAllTenMetadataKeys() throws IOException {
        byte[] bytes = exporter.render(document(fullMetadata(), ExportMode.EXTERNAL, "PASS", null));
        String xml = zipEntry(bytes, "word/document.xml");

        for (String key : MetadataGate.REQUIRED) {
            assertTrue(xml.contains(key), "缺少 metadata 键：" + key);
            assertTrue(xml.contains("V-" + key), "缺少 metadata 值：" + key);
        }
        assertTrue(xml.contains("一、十项 metadata"));
    }

    @Test
    @DisplayName("缺失项显示「缺失」并标红，不留空")
    void missingValueIsMarked() throws IOException {
        Map<String, String> metadata = fullMetadata();
        metadata.remove("DUT");
        byte[] bytes = exporter.render(
                document(metadata, ExportMode.EXTERNAL, "PASS", null, List.of("DUT")));
        String xml = zipEntry(bytes, "word/document.xml");

        assertTrue(xml.contains("DUT"));
        assertTrue(xml.contains(">缺失<") || xml.contains("缺失"), "缺失项必须显式写「缺失」");
        assertTrue(xml.contains("FF0000"), "缺失项必须标红");
        assertFalse(xml.contains("V-DUT"), "缺失项不能残留旧值");
    }

    @Test
    @DisplayName("内部版页眉含红字标识")
    void internalHeaderIsRed() throws IOException {
        byte[] bytes = exporter.render(
                document(fullMetadata(), ExportMode.INTERNAL, "PASS", null));
        String header = zipEntry(bytes, "word/header1.xml");

        assertTrue(header.contains(DocxExporter.INTERNAL_HEADER));
        assertTrue(header.contains("FF0000"), "内部版页眉必须是红色");
        assertTrue(header.contains("w:color"));
    }

    @Test
    @DisplayName("失败消息超过 500 字截断并注明原长度")
    void longFailureMessageIsTruncated() throws IOException {
        String failure = "F".repeat(600);
        byte[] bytes = exporter.render(
                document(fullMetadata(), ExportMode.INTERNAL, "FAIL", failure));
        String xml = zipEntry(bytes, "word/document.xml");

        assertTrue(xml.contains("已截断，原长度 600 字符"));
        assertFalse(xml.contains(failure), "原文 600 字不应完整落盘");
        assertTrue(xml.contains("F".repeat(DocxExporter.FAILURE_MAX_CHARS).substring(0, 100)));
    }

    @Test
    @DisplayName("生成文件是合法 zip，document.xml 含 RUN_ID")
    void producesValidDocxZip() throws IOException {
        byte[] bytes = exporter.render(
                document(fullMetadata(), ExportMode.EXTERNAL, "FAIL", "断言失败：期望 200 实际 500"));
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String xml = zipEntry(bytes, "word/document.xml");

        assertTrue(xml.contains(RUN_ID), "document.xml 必须带 RUN_ID");
        assertTrue(xml.contains("自动化测试运行报告"));
        assertTrue(xml.contains("二、用例明细"));
        assertTrue(xml.contains("断言失败"));
    }

    /**
     * FR-v3.9-03 ③：导出件必须含命令列。
     *
     * 导出件是对外交付面——报告页看得到、PDF/Word 里没有，等于对外仍然说不清
     * 「这条用例对设备发了什么」。所以这里钉表头 + 命令原文 + 无命令文案三件事。
     */
    @Test
    @DisplayName("用例表含「下发命令」列：有命令写原文，无命令写明确文案")
    void writesCommandColumn() throws IOException {
        ReportTreeService.TreeCase withCommand = new ReportTreeService.TreeCase(
                "suites/a.robot.case-cmd", "用例-有命令", "PASS", 12L, null,
                "ipmitool raw 0x30 0x93", "RF_KEYWORD", 0, List.of());
        ReportTreeService.TreeCase withoutCommand = new ReportTreeService.TreeCase(
                "suites/a.robot.case-none", "用例-无命令", "PASS", 8L, null,
                "NONE", "NONE", null, List.of());
        ReportTreeService.TreeSuite suite = new ReportTreeService.TreeSuite(
                "a.robot", "PASS", List.of(), List.of(withCommand, withoutCommand));
        ReportRecord report = new ReportRecord(7L, RUN_ID, "finished", true, List.of(),
                fullMetadata(), 2, 0, 0, NOW.minusSeconds(60), NOW);
        byte[] bytes = exporter.render(new ExportDocument(
                report, new ReportTreeService.TreeSuite(RUN_ID, "PASS", List.of(suite), List.of()),
                ExportMode.EXTERNAL, NOW, "tester"));
        String xml = zipEntry(bytes, "word/document.xml");

        assertTrue(xml.contains("下发命令"), "导出件缺「下发命令」表头");
        assertTrue(xml.contains("ipmitool raw 0x30 0x93"), "命令原文必须落盘");
        assertTrue(xml.contains("[exit=0]"), "退出码要与命令同格");
        assertTrue(xml.contains(DocxExporter.NO_COMMAND_TEXT), "无命令必须是明确文案而非空单元格");
    }

    @Test
    @DisplayName("命令列三态文案：未采集 / 未下发 / 原文，互不混淆")
    void commandTextThreeStates() {
        assertEquals("未采集（v3.9.0 之前的报告）", DocxExporter.commandText(null, null));
        assertEquals(DocxExporter.NO_COMMAND_TEXT, DocxExporter.commandText("NONE", null));
        assertEquals(DocxExporter.NO_COMMAND_TEXT, DocxExporter.commandText("  ", null));
        assertEquals("ls -l　[exit=1]", DocxExporter.commandText("ls -l", 1));
        assertEquals("ls -l", DocxExporter.commandText("ls -l", null));
    }

    @Test
    @DisplayName("超长命令截断并注明原长度，不做静默裁剪")
    void longCommandIsTruncated() {
        String command = "x".repeat(DocxExporter.COMMAND_MAX_CHARS + 50);
        String text = DocxExporter.commandText(command, null);
        assertTrue(text.startsWith("x".repeat(DocxExporter.COMMAND_MAX_CHARS)));
        assertTrue(text.contains("已截断，原长度 " + command.length() + " 字符"));
    }

    /**
     * FR-v5.6-25：多层套件必须摊平成一张表，不能再一个套件一个标题。
     *
     * <p>判据是三条，都可机检：只有一张表、没有 `▸` 套件标题、路径并进用例列。
     */
    @Test
    @DisplayName("多层套件摊平成一张表，路径并入用例列")
    void flattensSuitesIntoOneTable() throws IOException {
        ReportTreeService.TreeCase one = new ReportTreeService.TreeCase(
                "suites/a.robot.c1", "用例1", "PASS", 10L, null,
                "NONE", "NONE", null, List.of());
        ReportTreeService.TreeCase two = new ReportTreeService.TreeCase(
                "suites/b.robot.c2", "用例2", "FAIL", 20L, "boom",
                "NONE", "NONE", null, List.of());
        ReportTreeService.TreeSuite moduleA = new ReportTreeService.TreeSuite(
                "模块A", "PASS", List.of(), List.of(one));
        ReportTreeService.TreeSuite moduleB = new ReportTreeService.TreeSuite(
                "模块B", "FAIL", List.of(), List.of(two));
        ReportTreeService.TreeSuite root = new ReportTreeService.TreeSuite(
                "run-" + RUN_ID, "FAIL", List.of(moduleA, moduleB), List.of());
        ReportRecord report = new ReportRecord(7L, RUN_ID, "finished", true, List.of(),
                fullMetadata(), 1, 1, 0, NOW.minusSeconds(60), NOW);

        String xml = zipEntry(exporter.render(
                new ExportDocument(report, root, ExportMode.EXTERNAL, NOW, "tester")),
                "word/document.xml");

        // 两张表：metadata 一张 + 用例一张。原先每个套件一张表，59 条用例就是几十张表
        assertEquals(2, countOf(xml, "<w:tbl>"), "用例只能占一张表（metadata 表另算）");
        assertFalse(xml.contains("▸"), "不能再出现「每个套件一个标题」");
        assertTrue(xml.contains("模块A / 用例1"));
        assertTrue(xml.contains("模块B / 用例2"));
        // 根套件名带 run id，若并进路径，跨报告比对就永远不相等
        assertFalse(xml.contains("run-" + RUN_ID + " / 用例1"));
    }

    @Test
    @DisplayName("抖动结论写进导出件：PASS 与「PASS（抖动·复测 2 轮）」必须能分开")
    void flakyVerdictIsWritten() {
        ReportTreeService.TreeCase flaky = new ReportTreeService.TreeCase(
                "suites/a.robot.c9", "抖动用例", "PASS", 900L, null, "NONE", "NONE", null,
                List.of(), 2, true);
        assertEquals("PASS（抖动·复测 2 轮）", DocxExporter.verdictText(flaky));

        ReportTreeService.TreeCase clean = new ReportTreeService.TreeCase(
                "suites/a.robot.c10", "稳定通过", "PASS", 800L, null, "NONE", "NONE", null,
                List.of());
        assertEquals("PASS", DocxExporter.verdictText(clean));
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    private static ExportDocument document(Map<String, String> metadata, ExportMode mode,
                                           String verdict, String failure) {
        return document(metadata, mode, verdict, failure, List.of());
    }

    private static ExportDocument document(Map<String, String> metadata, ExportMode mode,
                                           String verdict, String failure,
                                           List<String> missing) {
        ReportRecord report = new ReportRecord(7L, RUN_ID, "finished",
                missing.isEmpty(), missing, metadata, 1, verdict.equals("FAIL") ? 1 : 0, 0,
                NOW.minusSeconds(60), NOW);
        ReportTreeService.TreeCase test = new ReportTreeService.TreeCase(
                "suites/a.robot.case-ok", "用例-中文校验", verdict, 1234L, failure, List.of());
        ReportTreeService.TreeSuite suite = new ReportTreeService.TreeSuite(
                "a.robot", verdict, List.of(), List.of(test));
        ReportTreeService.TreeSuite root = new ReportTreeService.TreeSuite(
                RUN_ID, verdict, List.of(suite), List.of());
        return new ExportDocument(report, root, mode, NOW, "tester");
    }

    private static Map<String, String> fullMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : MetadataGate.REQUIRED) {
            metadata.put(key, "V-" + key);
        }
        return metadata;
    }

    private static String zipEntry(byte[] bytes, String entryName) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                entry = zip.getNextEntry();
            }
        }
        throw new AssertionError("docx 内未找到 " + entryName);
    }
}
