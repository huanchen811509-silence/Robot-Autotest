package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TPLX-02：冻结时把模板写进快照，渲染只读快照内模板。
 *
 * <p>这组用例守的是**一条纪律**：改模板不得影响已冻结报告。单元层面能守的是
 * 「渲染只读 snapshot_json」——只要这一点成立，模板表怎么改都影响不到历史报告。
 * 端到端那一半（真库、真冻结、真改模板）走 {@code docs/evidence/v2.1/TPLX-02/run.py}。
 */
class ReportTemplateFreezeTest {

    private static final String WATERMARK = "INTERNAL USE ONLY";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportSnapshotService snapshots = new ReportSnapshotService(mapper);
    private final ReportViewRenderer renderer = new ReportViewRenderer(mapper, StubProjects.empty());

    private static ReportRecord source() {
        return new ReportRecord(7L, "RUN-TPLX-0001", "FINISHED", true, List.of(),
                Map.of("SW_BUILD", "build-2.1", "DUT", "10.202.54.106",
                        "CASE_KIND", "基本功能测试", "QA_PROJECT", "qa-tplx"),
                8, 1, 1, Instant.parse("2026-11-06T08:00:00Z"),
                Instant.parse("2026-11-06T08:10:00Z"));
    }

    /** 必备六项 + 指定附加字段，按给定顺序。 */
    private static ReportTemplate template(long id, int version, String name, String... extras) {
        List<String> keys = new ArrayList<>(List.of(
                "reportId", "title", "version", "frozenAt", "disclaimer", "watermark"));
        keys.addAll(List.of(extras));
        List<ReportTemplate.Field> fields = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            fields.add(new ReportTemplate.Field(keys.get(i), i, null));
        }
        return new ReportTemplate(id, 7L, name, "", version, true, fields, null, Instant.EPOCH);
    }

    @Test
    @DisplayName("快照记录模板 id / 版本 / 字段清单")
    void snapshotRecordsTemplate() {
        ReportSnapshot snapshot = snapshots.build(source(), 2, template(31L, 4, "对外简版", "conclusion"));
        assertTrue(snapshot.json().contains("\"templateId\":31"));
        assertTrue(snapshot.json().contains("\"templateVersion\":4"));
        assertTrue(snapshot.json().contains("对外简版"));

        ReportViewRenderer.TemplateRef ref = renderer.templateRef(snapshot);
        assertTrue(ref.bound());
        assertEquals(31L, ref.id());
        assertEquals(4, ref.version());
        assertEquals(List.of("reportId", "title", "version", "frozenAt",
                "disclaimer", "watermark", "conclusion"), ref.fieldKeys());
    }

    @Test
    @DisplayName("改模板不影响已冻结快照：字段集合与顺序都不变")
    void laterTemplateEditsDoNotTouchFrozenSnapshot() {
        ReportTemplate first = template(31L, 1, "对外简版", "conclusion", "passCount");
        ReportSnapshot frozen = snapshots.build(source(), 1, first);
        List<String> before = List.copyOf(
                renderer.external(frozen, 9L, "报告", "", WATERMARK).keySet());
        assertEquals(List.of("reportId", "title", "version", "frozenAt", "disclaimer",
                "watermark", "conclusion", "passCount"), before);

        // 模板被改成了「另一套字段 + 另一个顺序」，版本号也涨了
        ReportTemplate edited = template(31L, 2, "对外简版", "failCount", "passRate");
        ReportSnapshot newer = snapshots.build(source(), 2, edited);
        assertEquals(List.of("reportId", "title", "version", "frozenAt", "disclaimer",
                        "watermark", "failCount", "passRate"),
                List.copyOf(renderer.external(newer, 9L, "报告", "", WATERMARK).keySet()));

        // 已冻结的那份：字段与顺序保持原样，模板版本也仍是冻结时的 1
        assertEquals(before, List.copyOf(
                renderer.external(frozen, 9L, "报告", "", WATERMARK).keySet()));
        assertEquals(1, renderer.templateRef(frozen).version());
    }

    @Test
    @DisplayName("模板的自定义标题进入导出标题表，未配标题回落 key")
    void templateTitlesAreUsedForExport() {
        List<ReportTemplate.Field> fields = new ArrayList<>(List.of(
                new ReportTemplate.Field("reportId", 0, null),
                new ReportTemplate.Field("title", 1, null),
                new ReportTemplate.Field("version", 2, null),
                new ReportTemplate.Field("frozenAt", 3, null),
                new ReportTemplate.Field("disclaimer", 4, null),
                new ReportTemplate.Field("watermark", 5, null),
                new ReportTemplate.Field("passRate", 6, "通过率")));
        ReportTemplate withTitle = new ReportTemplate(32L, 7L, "带标题", "", 1, false,
                fields, null, Instant.EPOCH);
        ReportSnapshot snapshot = snapshots.build(source(), 1, withTitle);

        Map<String, String> titles = renderer.fieldTitles(snapshot);
        assertEquals("通过率", titles.get("passRate"));
        assertNull(titles.get("reportId"));
    }

    @Test
    @DisplayName("白名单收紧后，快照里模板的旧字段在渲染层被丢弃")
    void staleTemplateFieldIsDroppedAtRenderTime() {
        String json = snapshots.build(source(), 1, template(33L, 1, "旧模板", "conclusion")).json()
                .replace("\"fieldKey\":\"conclusion\"", "\"fieldKey\":\"rawOutput\"");
        assertTrue(json.contains("rawOutput"), "夹具应把非法字段塞进快照");
        ReportSnapshot stale = new ReportSnapshot(json, "", "x", 1, "s");

        Map<String, Object> view = renderer.external(stale, 1L, "报告", "", WATERMARK);
        assertFalse(view.containsKey("rawOutput"));
        assertFalse(renderer.templateRef(stale).fieldKeys().contains("rawOutput"));
    }

    @Test
    @DisplayName("模板缺必填字段时兜底补齐：免责声明与水印不得消失")
    void requiredFieldsSurviveATemplateThatLacksThem() {
        // 直接构造一份「缺 disclaimer/watermark」的快照，模拟模板校验被绕过或历史数据
        List<ReportTemplate.Field> fields = List.of(
                new ReportTemplate.Field("reportId", 0, null),
                new ReportTemplate.Field("title", 1, null));
        ReportTemplate broken = new ReportTemplate(34L, 7L, "坏模板", "", 1, false,
                fields, null, Instant.EPOCH);
        Map<String, Object> view =
                renderer.external(snapshots.build(source(), 1, broken), 1L, "报告", "", WATERMARK);

        assertTrue(view.containsKey("disclaimer"));
        assertTrue(view.containsKey("watermark"));
        // 兜底字段追加在末尾，按白名单顺序，保证多次渲染顺序一致
        List<String> keys = List.copyOf(view.keySet());
        assertEquals(List.of("reportId", "title", "version", "frozenAt", "disclaimer", "watermark"),
                keys);
    }

    @Test
    @DisplayName("未绑模板的历史快照回落到全量白名单顺序")
    void snapshotWithoutTemplateFallsBackToWhitelistOrder() {
        ReportSnapshot snapshot = snapshots.build(source(), 1);
        Map<String, Object> view = renderer.external(snapshot, 1L, "报告", "", WATERMARK);
        assertEquals(ReportFieldWhitelist.EXTERNAL_ORDER, List.copyOf(view.keySet()));
        assertFalse(renderer.templateRef(snapshot).bound());
        assertTrue(renderer.fieldTitles(snapshot).isEmpty());
    }

    @Test
    @DisplayName("自定义标题也参与 HTML 导出表头，且 key 仍是机读列")
    void htmlKeepsFieldKeyAsMachineReadableColumn() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("passRate", 88.89);
        view.put("title", "季度回归");
        String html = new String(new ReportExportService()
                .export("html", view, Map.of("passRate", "通过率")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("<th title=\"通过率\">passRate</th>"));
    }
}
