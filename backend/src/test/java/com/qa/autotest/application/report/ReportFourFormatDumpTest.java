package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TPLX-03 证据：用**真实导出器**产出四份文件，供 {@code tools/check_report_consistency.py} 比对。
 *
 * <p>为什么不在门禁里拿「仓库里存的四份历史文件」当样本：那样门禁守住的只是检查器，
 * 导出器改坏了照样绿。这里是每次跑测试都用当前代码现生成，门禁比对的是**这一次的产物**——
 * 某条分支漏写字段，第 14 步就会红。
 *
 * <p>本用例只负责「产出 + 自证文件有效」，字段集合是否四格式一致由第 14 步判：
 * 职责分开，出问题时能立刻分清是「导出器坏了」还是「检查器坏了」。
 */
class ReportFourFormatDumpTest {

    static final Path OUT = Path.of("target", "evidence", "four-formats");

    /** 夹具字段覆盖：数字、字符串、中文自定义标题、结构化值，尽量把四个分支的差异面撑开。 */
    private static Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportId", 42);
        view.put("title", "TPLX-03 四格式一致性");
        view.put("version", 2);
        view.put("frozenAt", "2026-11-06T13:38:34Z");
        view.put("conclusion", "FAIL");
        view.put("passCount", 8);
        view.put("failCount", 1);
        view.put("skipCount", 1);
        view.put("passRate", 80.0);
        view.put("caseTotal", 10);
        view.put("projectName", "EHT pytest 用例库工程");
        view.put("swBuild", "build-2.1-tplx03");
        view.put("caseKind", "基本功能测试");
        view.put("qaProject", "EHT-PYTEST-LIB");
        view.put("dut", "gpu01");
        view.put("link", "dual-up-pfc");
        view.put("caseId", "TC-v5.22-01");
        view.put("runId", "RUN-TPLX-0003");
        view.put("startedAt", "2026-11-06T13:30:00Z");
        view.put("finishedAt", "2026-11-06T13:38:00Z");
        view.put("disclaimer", "INTERNAL USE ONLY - NOT FOR EXTERNAL DISTRIBUTION");
        view.put("watermark", ReportExportService.WATERMARK);
        return view;
    }

    private static Map<String, String> titles() {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("passRate", "通过率");
        titles.put("swBuild", "构建号");
        return titles;
    }

    @Test
    @DisplayName("四格式产物落盘：字段集合由门禁第 14 步比对")
    void dumpFourFormats() throws IOException {
        ReportExportService service = new ReportExportService();
        Files.createDirectories(OUT);
        Map<String, Object> view = view();
        Map<String, String> titles = titles();

        int fields = view.size();
        for (String format : List.of("html", "docx", "xlsx", "pdf")) {
            byte[] bytes = service.export(format, view, titles);
            assertTrue(bytes.length > 100, format + " 产物过小: " + bytes.length);
            Files.write(OUT.resolve("report." + format), bytes);
        }

        String html = new String(service.export("html", view, titles), StandardCharsets.UTF_8);
        // 自证：HTML 每一行都带字段 key，且中文标题进了 title 属性而不是顶掉 key
        assertEquals(fields, countOccurrences(html, "<tr>"), "HTML 行数与字段数不一致");
        assertTrue(html.contains("<th title=\"通过率\">passRate</th>"), "HTML 标题行格式变了");
        System.out.println("[TPLX-03] 四格式产物已写入 " + OUT.toAbsolutePath()
                + "，字段数=" + fields);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
