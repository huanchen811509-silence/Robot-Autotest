package com.qa.autotest.application.report;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 报告级 PDF 渲染（v5.22.0 / FR-v5.22-03，W72-05）。
 *
 * <p><b>为什么不是原来的 {@code ReportExportService.pdf()}：</b>那份实现手写 PDF obj、
 * 用 {@code /F1 Helvetica} 当唯一字体，文本又要过 {@code ascii()}——凡是
 * {@code c < 32 || c >= 127} 的字符一律换成 {@code '?'}，中文根本写不进去。
 * 实测导出恒定 1,521 字节、单页、无一个汉字。补齐 CJK 字体嵌入成本极高，
 * 所以改为「HTML → 无头浏览器打印」，与 run 级 PDF 共用同一套 {@link PdfRenderer}。
 *
 * <p><b>为什么走 {@code file://} 而不是 HTTP：</b>报告级快照 HTML 已经落在
 * {@code test_report.snapshot_html}，是冻结时固化的产物。为它再开一个带一次性
 * token 的打印端点，等于给对外文件多开一条可访问链路；写到临时文件用
 * {@code file://} 打印，浏览器进程结束即删，既不引入新入口也不用处理鉴权。
 *
 * <p>渲染失败一律 {@link ErrorCode#DEPENDENCY_UNAVAILABLE}（503），**不退回手写空壳**：
 * v5.22 的整条立意是「报告可信」，产出一个没有汉字的 PDF 比报错更糟。
 */
@Service
public class ReportPdfService {

    private final PdfRenderer renderer;

    public ReportPdfService(PdfRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 按**对外视图**渲染 PDF。
     *
     * <p>刻意不用快照 HTML：那个 HTML 是「结论 / 证据 / 附录」三段式，字段集合与
     * xlsx / html / docx 的 22 项视图不同。四格式内容不一致时，同一份报告交给客户
     * 两个格式会对不上账——PRD 01-5（四格式字段集合与顺序一致）正是要堵这个。
     *
     * @param view   对外视图（字段 key → 值，顺序即渲染顺序）
     * @param titles 模板里配的自定义标题（字段 key → 标题），没有则回落到 key
     */
    public byte[] renderView(java.util.Map<String, Object> view,
                             java.util.Map<String, String> titles, String title) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(ReportViewRenderer.escape(title == null ? "" : title))
                .append("</h1>");
        body.append("<table><tr><th>字段</th><th>名称</th><th>值</th></tr>");
        java.util.Map<String, String> header = titles == null ? java.util.Map.of() : titles;
        for (java.util.Map.Entry<String, Object> entry : view.entrySet()) {
            String label = header.getOrDefault(entry.getKey(), entry.getKey());
            body.append("<tr><td>").append(ReportViewRenderer.escape(entry.getKey()))
                    .append("</td><td>").append(ReportViewRenderer.escape(label))
                    .append("</td><td>").append(ReportViewRenderer.escape(text(entry.getValue())))
                    .append("</td></tr>");
        }
        body.append("</table>");
        return renderHtml(body.toString(), title);
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.util.Map<?, ?> || value instanceof Iterable<?>) {
            // 结构化值在 PDF 里没有表格形态，按 JSON 文本落，至少不丢信息
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }

    public byte[] renderHtml(String html, String title) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("rpt-v522-pdf-");
            Path file = dir.resolve("report.html");
            Files.writeString(file, wrap(html, title), StandardCharsets.UTF_8);
            return renderer.render(file.toUri().toString());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "PDF 渲染失败：无法写临时文件（" + ex.getMessage() + "）");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 补一层打印样式与中文字体。
     *
     * <p>快照 HTML 只有结构没有样式（{@code ReportHtmlSanitizer} 还会整块删 {@code <style>}，
     * 所以样式必须加在 sanitize **之后**）。字体按 Linux / Windows 常见中文字体列候选，
     * 缺字体时浏览器会回落，至少不会像 Helvetica 那样把汉字变成 {@code ?}。
     */
    private static String wrap(String html, String title) {
        String style = "<style>"
                + "body{font-family:'Noto Sans CJK SC','Noto Sans SC','Microsoft YaHei',"
                + "'PingFang SC',sans-serif;margin:24px;line-height:1.7;font-size:12px}"
                + "h1{font-size:20px;margin:0 0 12px 0}h2{font-size:16px;margin:16px 0 8px 0}"
                + "h3{font-size:13px;margin:12px 0 6px 0}"
                + "dt{font-weight:600;margin-top:8px}dd{margin:0 0 4px 0}"
                + "table{border-collapse:collapse;width:100%}"
                + "th,td{border:1px solid #999;padding:4px 6px;text-align:left;font-size:11px}"
                + "</style>";
        String safeTitle = ReportViewRenderer.escape(title == null ? "Test Report" : title);
        if (html != null && html.contains("<head>")) {
            return html.replaceFirst("<head>", "<head><title>" + safeTitle + "</title>" + style);
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + safeTitle
                + "</title>" + style + "</head><body>"
                + (html == null ? "" : html) + "</body></html>";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响本次导出结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
