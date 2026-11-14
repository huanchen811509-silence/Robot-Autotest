package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TPLX-01 证据：把 EXTERNAL 视图**逐字段**导成 JSON，用于「抽白名单常量前后渲染结果一致」的 diff。
 *
 * <p>为什么需要这个测试而不是只看断言：把常量从 `ReportViewRenderer` 搬到
 * `ReportFieldWhitelist` 属于「只搬家」的改动，风险恰恰是**搬漏一项或搬错一个键名**——
 * 断言只能验「在白名单里」，验不出「21 个字段的顺序、取值、脱敏结果都没变」。
 * 所以这里落一份完整 JSON，配合 `diff` 使用：
 *
 * <pre>
 *   # 抽常量之前
 *   mvn -o -B test -Dtest=ReportViewRenderDumpTest
 *   cp target/evidence/report-render-dump.json docs/evidence/v2.1/TPLX-01/render-before.json
 *   # 抽常量之后
 *   mvn -o -B test -Dtest=ReportViewRenderDumpTest
 *   cp target/evidence/report-render-dump.json docs/evidence/v2.1/TPLX-01/render-after.json
 *   diff render-before.json render-after.json     # 必须为空
 * </pre>
 *
 * <p>夹具刻意带上 IP 与命令形态的 metadata：脱敏规则也是搬家的一部分，diff 能一并守住。
 */
class ReportViewRenderDumpTest {

    /** 报告标题也是渲染入参之一，固定值保证两次 dump 可 diff。 */
    private static final String TITLE = "季度回归报告";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportSnapshotService snapshots = new ReportSnapshotService(mapper);
    private final ReportViewRenderer renderer = new ReportViewRenderer(mapper);

    @Test
    @DisplayName("EXTERNAL 视图逐字段落盘，供抽常量前后 diff")
    void dumpExternalView() throws IOException {
        ReportSnapshot snapshot = snapshots.build(source(), 3);
        Map<String, Object> view = renderer.external(snapshot, 42L, TITLE,
                "2026-11-06T08:20:00Z", "INTERNAL USE ONLY");

        // 先自证：字段全在白名单里，否则 dump 出来的东西没有对照价值
        for (String key : view.keySet()) {
            assertTrue(ReportFieldWhitelist.EXTERNAL_FIELDS.contains(key), "非白名单字段: " + key);
        }
        // 复核 P0-4：没有判据汇总的运行结论是 UNDETERMINED，不再由 failCount 翻译。
        assertEquals("UNDETERMINED", view.get("conclusion"));
        assertEquals(42L, ((Number) view.get("reportId")).longValue());

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(view) + "\n";
        Path out = Path.of("target", "evidence", "report-render-dump.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
        // 顺序敏感：LinkedHashMap 的插入顺序就是对外渲染顺序，改顺序也算行为变化
        assertTrue(json.indexOf("\"reportId\"") < json.indexOf("\"title\""),
                "字段顺序变了，diff 会挡住");
    }

    private static ReportRecord source() {
        return new ReportRecord(7L, "RUN-W2-0001", "FINISHED", true, List.of(),
                Map.of("SW_BUILD", "build-2.1", "DUT", "10.202.54.106",
                        "CASE_KIND", "基本功能测试", "QA_PROJECT", "qa-e2e-report",
                        "LINK", "http://lab.local/case/1",
                        "OPERATOR", "someone", "RUN_ID", "RUN-W2-0001"),
                8, 1, 1, Instant.parse("2026-11-06T08:00:00Z"),
                Instant.parse("2026-11-06T08:10:00Z"));
    }
}
