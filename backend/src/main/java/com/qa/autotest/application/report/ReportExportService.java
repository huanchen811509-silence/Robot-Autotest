package com.qa.autotest.application.report;

import com.qa.autotest.common.security.SecretRedactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

/**
 * 四格式导出（RPTX-05 / TPLX-02）。
 *
 * <p>HTML 走 C5 白名单视图 + 水印；DOCX / XLSX 用已有 POI 依赖；PDF 不引第三方库，
 * 手写最小 PDF 1.4（Helvetica / ASCII 文本层）——中文正文走 HTML / DOCX / XLSX，
 * PDF 文本层只放 ASCII 字段摘要，保证敏感字段扫描器能读到同一份内容。
 *
 * <p><b>行格式约定（TPLX-03 四格式一致性校验依赖它）</b>：每种格式的每一行都以
 * **字段 key 作为可机读标识**，标题只作展示：
 * <ul>
 *   <li>HTML：{@code <tr><th title="标题">fieldKey</th><td>值</td></tr>}</li>
 *   <li>PDF / DOCX：每行 {@code fieldKey | 标题 | 值}</li>
 *   <li>XLSX：A 列 fieldKey、B 列标题、C 列值</li>
 * </ul>
 * 约定必须四格式一致，否则「四格式字段集合比对」就没有共同语言。PDF 会把中文标题降级成
 * {@code ?}，但 key 是 ASCII，不受影响。
 *
 * <p><b>数值列必须是数字</b>：XLSX 里计数/通过率用 {@code setCellValue(double)} 写，
 * 不能拼成字符串——否则 Excel 里求和、排序、条件格式全部失效，用户看不出错但数据用不了。
 */
@Service
public class ReportExportService {

    public static final String WATERMARK = "INTERNAL USE ONLY";

    /** XLSX 首行的三列表头：key / 标题 / 值。 */
    static final String[] SHEET_HEADER = {"FIELD_KEY", "TITLE", "VALUE"};

    /**
     * 明细 sheet 的固定五列（v5.6.0 / FR-v5.6-23）。
     *
     * <p>列头写死在代码里而不是跟模板走：明细是「逐条结果」的稳定视图，用户拿到 xlsx
     * 第一件事是按「结论」筛选失败项，列位置一变筛选宏就废了。
     */
    static final String[] DETAIL_HEADER = {"编号", "结论", "耗时(ms)", "失败原因", "下发命令"};

    /** 明细表名。 */
    static final String DETAIL_SHEET = "用例明细";

    /**
     * 空值占位。写 {@code null} 会让单元格变成真空，Excel 里与「这一列没这功能」无法区分；
     * 写 {@code —} 至少表明「查过了，没有」。
     */
    public static final String EMPTY_CELL = "—";

    /**
     * 明细行（v5.6.0 / FR-v5.6-23）。
     *
     * @param durationMs 可能为 null（历史行没记耗时），落表时同样写 {@link #EMPTY_CELL}
     */
    public record CaseDetailRow(String code, String verdict, Long durationMs,
                                String failureMsg, String command) {
    }

    public byte[] export(String format, Map<String, Object> view) {
        return export(format, view, Map.of(), List.of());
    }

    /**
     * 导出。
     *
     * @param titles 字段 key → 展示标题；来自**快照内模板**的 titleOverride。
     *               缺失的 key 回落到 key 本身，绝不因为「没配标题」就少一行
     */
    public byte[] export(String format, Map<String, Object> view, Map<String, String> titles) {
        return export(format, view, titles, List.of());
    }

    /**
     * 导出（带用例明细）。
     *
     * <p>明细只进 xlsx 的第二个 sheet：HTML / DOCX / PDF 已由
     * {@link DocxExporter} / {@link PdfExportService} 各自渲染逐条结果，
     * 在这里再塞一份会与那两条链路打架，也会破坏「四格式字段集合一致」的机检锚点。
     *
     * @param details 为空时只出一个 sheet，行为与旧签名完全一致
     */
    public byte[] export(String format, Map<String, Object> view, Map<String, String> titles,
                         List<CaseDetailRow> details) {
        // FR-v4.0-01：导出件是最后一个可能把凭据带出去的表面。四格式共用同一道
        // 「命中即阻断」闸门，避免 HTML 过了、DOCX 漏了。已脱敏文本幂等，不会误伤。
        assertNoSecrets(view, titles);
        assertNoSecretsInDetails(details);
        String kind = format == null ? "html" : format.trim().toLowerCase(Locale.ROOT);
        Map<String, String> headers = titles == null ? Map.of() : titles;
        List<CaseDetailRow> rows = details == null ? List.of() : details;
        return switch (kind) {
            case "html" -> html(view, headers).getBytes(StandardCharsets.UTF_8);
            case "pdf" -> pdf(view, headers);
            case "docx" -> docx(view, headers);
            case "xlsx" -> xlsx(view, headers, rows);
            default -> throw new IllegalArgumentException("不支持的导出格式: " + format);
        };
    }

    /** 明细行也要过凭据闸门：失败原因与命令都是自由文本，最容易夹带口令。 */
    static void assertNoSecretsInDetails(List<CaseDetailRow> details) {
        if (details == null) {
            return;
        }
        for (CaseDetailRow row : details) {
            if (row == null) {
                continue;
            }
            assertNoSecrets(row.code(), "report-export.detail.code");
            assertNoSecrets(row.verdict(), "report-export.detail.verdict");
            assertNoSecrets(row.failureMsg(), "report-export.detail.failureMsg");
            assertNoSecrets(row.command(), "report-export.detail.command");
        }
    }

    /** 递归扫描视图与标题里的字符串；命中明文凭据即阻断本次导出。 */
    static void assertNoSecrets(Map<String, Object> view, Map<String, String> titles) {
        if (view != null) {
            for (Map.Entry<String, Object> entry : view.entrySet()) {
                SecretRedactor.assertClean(entry.getKey(), "report-export.key");
                assertNoSecrets(entry.getValue(), "report-export." + entry.getKey());
            }
        }
        if (titles != null) {
            for (String title : titles.values()) {
                SecretRedactor.assertClean(title, "report-export.title");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertNoSecrets(Object value, String surface) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertNoSecrets(entry.getKey(), surface + ".key");
                assertNoSecrets(entry.getValue(), surface + "." + entry.getKey());
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                assertNoSecrets(item, surface + "[]");
            }
        } else if (value != null) {
            SecretRedactor.assertClean(String.valueOf(value), surface);
        }
    }

    public static String extension(String format) {
        String kind = format == null ? "html" : format.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "pdf" -> "pdf";
            case "docx" -> "docx";
            case "xlsx" -> "xlsx";
            case "html" -> "html";
            default -> throw new IllegalArgumentException("不支持的导出格式: " + format);
        };
    }

    public static String contentType(String format) {
        return switch (extension(format)) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "text/html;charset=UTF-8";
        };
    }

    /** 字段展示标题：模板给了就用模板的，没给就用 key（不能给空）。 */
    static String header(String fieldKey, Map<String, String> titles) {
        String title = titles.get(fieldKey);
        return title == null || title.isBlank() ? fieldKey : title;
    }

    private static String html(Map<String, Object> view, Map<String, String> titles) {
        StringBuilder b = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>Test Report</title></head><body>");
        b.append("<h1>").append(ReportViewRenderer.escape(String.valueOf(view.get("title"))))
                .append("</h1><p class=\"wm\">")
                .append(ReportViewRenderer.escape(WATERMARK)).append("</p><table>");
        for (Map.Entry<String, Object> e : view.entrySet()) {
            b.append("<tr><th title=\"")
                    .append(ReportViewRenderer.escape(header(e.getKey(), titles)))
                    .append("\">")
                    .append(ReportViewRenderer.escape(e.getKey()))
                    .append("</th><td>")
                    .append(ReportViewRenderer.escape(String.valueOf(e.getValue())))
                    .append("</td></tr>");
        }
        return b.append("</table></body></html>").toString();
    }

    private static List<String> lines(Map<String, Object> view, Map<String, String> titles) {
        List<String> result = new ArrayList<>();
        result.add("Test Report / " + WATERMARK);
        for (Map.Entry<String, Object> e : view.entrySet()) {
            result.add(e.getKey() + " | " + header(e.getKey(), titles) + " | "
                    + String.valueOf(e.getValue()));
        }
        return result;
    }

    private static String ascii(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            b.append(c >= 32 && c < 127 ? c : '?');
        }
        return b.toString();
    }

    private static String pdfEscape(String text) {
        return ascii(text).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static byte[] pdf(Map<String, Object> view, Map<String, String> titles) {
        StringBuilder content = new StringBuilder("BT\n/F1 11 Tf\n50 790 Td\n15 TL\n");
        for (String line : lines(view, titles)) {
            content.append('(').append(pdfEscape(line)).append(") Tj\nT*\n");
        }
        content.append("ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[6];
        write(out, "%PDF-1.4\n");
        obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        obj(out, offsets, 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
        obj(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        offsets[5] = out.size();
        write(out, "5 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
        out.write(stream, 0, stream.length);
        write(out, "endstream\nendobj\n");
        int startXref = out.size();
        write(out, "xref\n0 6\n0000000000 65535 f \n");
        for (int i = 1; i < 6; i++) {
            write(out, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        write(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
                + startXref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void obj(ByteArrayOutputStream out, int[] offsets, int number, String body) {
        offsets[number] = out.size();
        write(out, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] docx(Map<String, Object> view, Map<String, String> titles) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = doc.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setText("Test Report / " + WATERMARK);
            for (String line : lines(view, titles)) {
                XWPFParagraph paragraph = doc.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("DOCX 导出失败", e);
        }
    }

    /**
     * XLSX：第一个 sheet 三列（key / 标题 / 值），第二个 sheet 为用例明细。
     *
     * <p>刻意不做「标题当列头、一行数据」的宽表：字段数随模板变化，
     * 宽表一旦有 21 列就要横着滚，而 key 列的机读标识也会丢。行式布局既便于
     * Excel 侧筛选，也让 TPLX-03 的字段集合比对有稳定锚点。
     *
     * <p>明细 sheet（FR-v5.6-23）与汇总分开而不是拼在下面：汇总行数随 metadata 变化，
     * 拼在一起会让「明细行数 = 用例数」这条判据没法校验。
     */
    private static byte[] xlsx(Map<String, Object> view, Map<String, String> titles,
                               List<CaseDetailRow> details) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("report");
            Row head = sheet.createRow(0);
            for (int i = 0; i < SHEET_HEADER.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(SHEET_HEADER[i]);
            }
            int rowIndex = 1;
            for (Map.Entry<String, Object> e : view.entrySet()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(e.getKey());
                row.createCell(1).setCellValue(header(e.getKey(), titles));
                writeCell(row.createCell(2), e.getValue());
            }
            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 28 * 256);
            sheet.setColumnWidth(1, 28 * 256);
            sheet.setColumnWidth(2, 48 * 256);
            if (details != null && !details.isEmpty()) {
                writeDetailSheet(workbook, details);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("XLSX 导出失败", e);
        }
    }

    /** 明细 sheet：固定五列、首行冻结、空值写 {@link #EMPTY_CELL}。 */
    private static void writeDetailSheet(XSSFWorkbook workbook, List<CaseDetailRow> details) {
        Sheet sheet = workbook.createSheet(DETAIL_SHEET);
        Row head = sheet.createRow(0);
        for (int i = 0; i < DETAIL_HEADER.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(DETAIL_HEADER[i]);
        }
        int rowIndex = 1;
        for (CaseDetailRow detail : details) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(textOf(detail.code()));
            row.createCell(1).setCellValue(textOf(detail.verdict()));
            // 耗时是唯一保留数字语义的列：Excel 侧要能排序与求平均
            if (detail.durationMs() == null) {
                row.createCell(2).setCellValue(EMPTY_CELL);
            } else {
                row.createCell(2).setCellValue(detail.durationMs().doubleValue());
            }
            row.createCell(3).setCellValue(textOf(detail.failureMsg()));
            row.createCell(4).setCellValue(textOf(detail.command()));
        }
        sheet.createFreezePane(0, 1);
        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 56 * 256);
        sheet.setColumnWidth(4, 56 * 256);
    }

    /** 空值/空白的统一落法：写占位符，不写 null。 */
    private static String textOf(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_CELL;
        }
        return value;
    }

    /** 数值写数字、布尔写布尔，其余写字符串——数值列被拼成文本是 Excel 侧最常见的坑。 */
    static void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
        } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
            cell.setCellValue(jsonish(value));
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /** 结构化值（failureDistribution / trend）落成 JSON 文本，不静默丢字段。 */
    private static String jsonish(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append('"').append(e.getKey()).append("\":").append(jsonValue(e.getValue()));
            }
            return b.append('}').toString();
        }
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Object item : (List<?>) value) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(jsonValue(item));
        }
        return b.append(']').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return jsonish(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** run 级导出（RPT-05）用的最小视图：只有汇总计数与 metadata，不含字段标题。 */
    public static Map<String, Object> summaryView(String runId, String state, int passCount,
                                                  int failCount, int skipCount,
                                                  Map<String, String> metadata) {
        return summaryView(runId, state, passCount, failCount, skipCount, metadata, null);
    }

    /**
     * 带判据结论的重载（W57-50）：结论与页面同源，避免「页面 UNDETERMINED、导出 PASS」。
     * 旧 6 参签名保留并显式按 UNDETERMINED 处理——不回落成计数口径。
     */
    public static Map<String, Object> summaryView(String runId, String state, int passCount,
                                                  int failCount, int skipCount,
                                                  Map<String, String> metadata,
                                                  String criteriaConclusion) {
        Map<String, Object> view = new LinkedHashMap<>();
        int total = passCount + failCount + skipCount;
        view.put("runId", runId);
        view.put("state", state);
        view.put("passCount", passCount);
        view.put("failCount", failCount);
        view.put("skipCount", skipCount);
        view.put("caseTotal", total);
        view.put("passRate", total == 0 ? 0.0
                : Math.round(10000.0 * passCount / total) / 100.0);
        view.put("conclusion", criteriaConclusion == null || criteriaConclusion.isBlank()
                ? "UNDETERMINED" : criteriaConclusion);
        Map<String, String> safe = ReportViewRenderer.safeMetadata(metadata);
        safe.forEach(view::put);
        view.put("watermark", WATERMARK);
        return view;
    }
}
