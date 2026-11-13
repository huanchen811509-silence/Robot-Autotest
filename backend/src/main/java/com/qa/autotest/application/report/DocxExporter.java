package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.ExportMode;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.springframework.stereotype.Service;

/**
 * Word 导出（RPT-05），直接用 POI 的 {@link XWPFDocument} 写 docx。
 *
 * <p>结构：首页十项 metadata 表格 → 分页 → 套件树/用例表 → 页脚 RUN_ID + 导出时间。
 * 缺失项一律写「缺失」并标红，<b>绝不留空</b>；内部版页眉固定红字
 * 「内部版 · 含缺失项，不可对外」。
 */
@Service
public class DocxExporter {

    /** 失败消息超过该长度即截断（字符数）。 */
    public static final int FAILURE_MAX_CHARS = 500;

    /** 导出件里命令列的长度上限：报告正文是给人读的，全文留在报告页看。 */
    public static final int COMMAND_MAX_CHARS = 300;

    /** 无命令时的导出文案（与报告页一致，不用空白冒充）。 */
    public static final String NO_COMMAND_TEXT = "本用例未下发外部命令";

    /** 内部版页眉红字文案。 */
    public static final String INTERNAL_HEADER = "内部版 · 含缺失项，不可对外";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String FONT = "Microsoft YaHei";

    public byte[] render(ExportDocument doc) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (doc.mode() == ExportMode.INTERNAL) {
                writeHeader(document);
            }
            writeTitle(document, doc);
            writeMetadataTable(document, doc.report());
            writeCounts(document, doc.report());
            // 首页信息独立成页，正文从第 2 页开始。
            paragraph(document).createRun().addBreak(BreakType.PAGE);
            writeTree(document, doc.tree());
            writeFooter(document, doc);
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Word 导出失败：" + ex.getMessage(), ex);
        }
    }

    private static void writeHeader(XWPFDocument document) {
        XWPFHeader header = document.createHeader(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = header.getParagraphArray(0);
        if (paragraph == null) {
            paragraph = header.createParagraph();
        }
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(INTERNAL_HEADER);
        run.setBold(true);
        run.setFontSize(14);
        run.setColor("FF0000");
        applyFont(run);
    }

    private static void writeTitle(XWPFDocument document, ExportDocument doc) {
        XWPFParagraph title = paragraph(document);
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setText("自动化测试运行报告");
        run.setBold(true);
        run.setFontSize(20);
        applyFont(run);

        XWPFParagraph sub = paragraph(document);
        sub.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subRun = sub.createRun();
        subRun.setText("RUN_ID：" + doc.report().runId() + "　|　导出时间：" + TIME_FORMAT.format(doc.exportedAt()));
        subRun.setFontSize(10);
        applyFont(subRun);
    }

    private static void writeMetadataTable(XWPFDocument document, ReportRecord report) {
        XWPFParagraph heading = paragraph(document);
        XWPFRun headingRun = heading.createRun();
        headingRun.setText("一、十项 metadata");
        headingRun.setBold(true);
        headingRun.setFontSize(14);
        applyFont(headingRun);

        XWPFTable table = document.createTable(MetadataGate.REQUIRED.size() + 1, 2);
        fillCell(table.getRow(0).getCell(0), "键名", true, null);
        fillCell(table.getRow(0).getCell(1), "值", true, null);

        Map<String, String> metadata = report.metadata();
        List<String> missing = report.metadataMissing();
        for (int i = 0; i < MetadataGate.REQUIRED.size(); i++) {
            String key = MetadataGate.REQUIRED.get(i);
            String value = metadata.get(key);
            XWPFTableRow row = table.getRow(i + 1);
            fillCell(row.getCell(0), key, false, null);
            if (value == null || value.isBlank() || missing.contains(key)) {
                // 缺失项必须显式写「缺失」并标红，不允许留空。
                fillCell(row.getCell(1), "缺失", false, "FF0000");
            } else {
                fillCell(row.getCell(1), value, false, null);
            }
        }
    }

    private static void writeCounts(XWPFDocument document, ReportRecord report) {
        XWPFParagraph paragraph = paragraph(document);
        XWPFRun run = paragraph.createRun();
        run.setText("PASS " + report.passCount() + "　FAIL " + report.failCount()
                + "　SKIP " + report.skipCount()
                + "　|　" + (report.exportable() ? "可对外交付" : "存在缺失项，不可对外交付"));
        run.setFontSize(11);
        applyFont(run);
    }

    /**
     * 用例明细（v5.6.0 / FR-v5.6-25）。
     *
     * <p><b>为什么改成「一张表」而不是「每个套件一个标题」</b>：原先一个套件节点占一个
     * 加粗标题，RF 的报告里每个用例自带一层套件，59 条用例就是 59 个标题——正文被标题
     * 切成碎片，人翻不到重点。现在套件路径并进「用例」列（{@code 路径 / 用例名}），
     * 信息一条不少，标题只剩固定章节。
     */
    private static void writeTree(XWPFDocument document, ReportTreeService.TreeSuite root) {
        XWPFParagraph heading = paragraph(document);
        XWPFRun headingRun = heading.createRun();
        headingRun.setText("二、用例明细");
        headingRun.setBold(true);
        headingRun.setFontSize(14);
        applyFont(headingRun);

        if (root == null) {
            paragraphText(document, "（无套件树数据）", null);
            return;
        }
        List<CaseRow> rows = new ArrayList<>();
        flatten(root, "", rows);
        writeCasesTable(document, rows);
    }

    /** 摊平后的一行：套件路径 + 用例。 */
    private record CaseRow(String path, ReportTreeService.TreeCase test) {
    }

    private static void flatten(ReportTreeService.TreeSuite suite, String prefix,
                                List<CaseRow> out) {
        String here = prefix;
        String name = suite.name();
        // 根套件名是 run-<runId>，与 case_key 口径一致地不进路径：
        // 否则每条用例的「路径」里都带着本次运行的随机后缀，跨报告无法比对。
        if (name != null && !name.isBlank() && !name.startsWith("run-")) {
            here = prefix.isEmpty() ? name : prefix + " / " + name;
        }
        for (ReportTreeService.TreeCase test : suite.cases()) {
            out.add(new CaseRow(here, test));
        }
        for (ReportTreeService.TreeSuite child : suite.suites()) {
            flatten(child, here, out);
        }
    }

    private static void writeCasesTable(XWPFDocument document, List<CaseRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        XWPFTable table = document.createTable(rows.size() + 1, 5);
        XWPFTableRow header = table.getRow(0);
        fillCell(header.getCell(0), "用例", true, null);
        fillCell(header.getCell(1), "结论", true, null);
        fillCell(header.getCell(2), "耗时(ms)", true, null);
        fillCell(header.getCell(3), "下发命令", true, null);
        fillCell(header.getCell(4), "失败消息", true, null);

        for (int i = 0; i < rows.size(); i++) {
            CaseRow item = rows.get(i);
            ReportTreeService.TreeCase test = item.test();
            XWPFTableRow row = table.getRow(i + 1);
            String label = item.path().isEmpty() ? test.name() : item.path() + " / " + test.name();
            fillCell(row.getCell(0), label, false, null);
            fillCell(row.getCell(1), verdictText(test), false,
                    "FAIL".equalsIgnoreCase(test.verdict()) ? "FF0000" : null);
            fillCell(row.getCell(2), Long.toString(test.durationMs()), false, null);
            fillCell(row.getCell(3), commandText(test.commandText(), test.commandExitCode()),
                    false, null);
            String failure = failureText(test.failureMsg());
            fillCell(row.getCell(4), failure, false,
                    "FAIL".equalsIgnoreCase(test.verdict()) ? "FF0000" : null);
        }
    }

    /**
     * 结论列文案：抖动用例带上复测轮次（v5.6.0 / FR-v5.6-24）。
     *
     * <p>只写 {@code PASS} 会把「第一次失败、重跑才过」与「一次就过」看成一回事；
     * 对外报告里这两者的可信度差得很远，必须写清。
     */
    public static String verdictText(ReportTreeService.TreeCase test) {
        String verdict = test.verdict() == null ? "" : test.verdict();
        if (!test.flaky()) {
            return verdict;
        }
        return verdict + "（抖动·复测 " + test.retryCount() + " 轮）";
    }

    /**
     * 导出件命令列文案（FR-v3.9-03 ③）：超长截断并注明原长度，退出码拼在命令后面。
     *
     * <p>三种取值语义不同，导出件里必须分得清：null = v3.9.0 之前入库、还没采集；
     * {@code NONE} = 本来就没下发；其余 = 命令原文。留空会让评审以为「丢了」。
     */
    public static String commandText(String command, Integer exitCode) {
        if (command == null) {
            return "未采集（v3.9.0 之前的报告）";
        }
        if (command.isBlank() || "NONE".equalsIgnoreCase(command.trim())) {
            return NO_COMMAND_TEXT;
        }
        String text = command.trim();
        if (text.length() > COMMAND_MAX_CHARS) {
            text = text.substring(0, COMMAND_MAX_CHARS)
                    + "（已截断，原长度 " + command.length() + " 字符）";
        }
        return exitCode == null ? text : text + "　[exit=" + exitCode + "]";
    }

    /** 失败消息截断：超过 {@link #FAILURE_MAX_CHARS} 字符截断并注明原长度。 */
    public static String failureText(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if (message.length() <= FAILURE_MAX_CHARS) {
            return message;
        }
        return message.substring(0, FAILURE_MAX_CHARS)
                + "（已截断，原长度 " + message.length() + " 字符）";
    }

    private static void writeFooter(XWPFDocument document, ExportDocument doc) {
        XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = footer.getParagraphArray(0);
        if (paragraph == null) {
            paragraph = footer.createParagraph();
        }
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText("RUN_ID：" + doc.report().runId() + "　|　导出时间：" + TIME_FORMAT.format(doc.exportedAt()));
        run.setFontSize(9);
        applyFont(run);
    }

    private static void fillCell(XWPFTableCell cell, String text, boolean bold, String color) {
        cell.setText("");
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontSize(10);
        if (color != null) {
            run.setColor(color);
        }
        applyFont(run);
    }

    private static XWPFParagraph paragraph(XWPFDocument document) {
        return document.createParagraph();
    }

    private static void paragraphText(XWPFDocument document, String text, String color) {
        XWPFRun run = paragraph(document).createRun();
        run.setText(text);
        run.setFontSize(10);
        if (color != null) {
            run.setColor(color);
        }
        applyFont(run);
    }

    /** 中文字体必须写进 eastAsia，否则 Word 会用默认西文字体渲染中文。 */
    private static void applyFont(XWPFRun run) {
        run.setFontFamily(FONT);
        CTRPr properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        if (properties.sizeOfRFontsArray() > 0) {
            properties.getRFontsArray(0).setEastAsia(FONT);
        } else {
            properties.addNewRFonts().setEastAsia(FONT);
        }
    }
}
