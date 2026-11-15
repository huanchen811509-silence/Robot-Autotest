package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v5.14.0 / W64-14：EHT pytest 三段式快照的出口判据。
 *
 * <p>只测「生成器 + 数据载体」，不碰 DB：真实 DB / HTTP 读数留 W7 探针。
 * 每条断言对着 FR-09 的判据编号，避免单测绿了但判据没覆盖。
 */
class EhtPytestReportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportSnapshotService snapshots = new ReportSnapshotService(mapper);

    private static ReportRecord source() {
        return new ReportRecord(7L, "RUN-EHT-0001", "FINISHED", true, List.of(),
                Map.of("SW_BUILD", "build-9", "DUT", "dut-01", "QA_PROJECT", "EHT"),
                8, 1, 1, Instant.parse("2026-11-15T01:00:00Z"),
                Instant.parse("2026-11-15T01:10:00Z"));
    }

    private static EhtSections sections(String kernel, EhtSections.Counts counts,
                                        List<EhtSections.Undetermined> undetermined,
                                        List<EhtSections.Evidence> evidence,
                                        EhtSections.Diff diff) {
        return new EhtSections(kernel, counts, undetermined, evidence,
                Map.of("SW_BUILD", "build-9"), MetadataGate.REQUIRED, "abc123", diff);
    }

    private static EhtSections.Counts counts() {
        return new EhtSections.Counts(6, 1, 1, 2, 10);
    }

    private String html(EhtSections s) {
        return snapshots.build(source(), 1, null, s).html();
    }

    /** 判据 1：三个可辨识分节 + 目录锚点可跳，且清洗器不吃掉它们。 */
    @Test
    void threeSectionsAreDistinguishableAndAnchorsSurviveSanitizer() {
        ReportSnapshot snapshot = snapshots.build(source(), 1, null,
                sections("pytest", counts(), List.of(), List.of(), EhtSections.Diff.none()));
        String html = snapshot.html();
        assertTrue(html.contains("id=\"conclusion\""));
        assertTrue(html.contains("id=\"evidence\""));
        assertTrue(html.contains("id=\"appendix\""));
        assertTrue(html.contains("href=\"#conclusion\""));
        assertTrue(html.contains("<nav id=\"toc\">"));
        assertTrue(snapshot.json().contains("\"eht\""), "三段式数据必须进快照，保证自包含");

        String clean = ReportHtmlSanitizer.sanitize(html);
        assertTrue(clean.contains("id=\"conclusion\""));
        assertTrue(clean.contains("href=\"#conclusion\""));
        assertTrue(clean.contains("id=\"appendix\""));
    }

    /** 判据 2：四态计数之和 = 用例总数；不平衡要显式写出来而不是悄悄抹平。 */
    @Test
    void fourStatesSumToCaseTotalAndImbalanceIsVisible() {
        assertTrue(counts().balanced());
        assertEquals(10, counts().sum());
        assertTrue(html(sections("pytest", counts(), List.of(), List.of(),
                EhtSections.Diff.none())).contains("一致（10 = 10）"));

        EhtSections.Counts broken = new EhtSections.Counts(6, 1, 1, 1, 10);
        assertFalse(broken.balanced());
        assertTrue(html(sections("pytest", broken, List.of(), List.of(),
                EhtSections.Diff.none())).contains("不一致（9 ≠ 10）"));
    }

    /** 判据 3：判据未定项清单点名到 nodeId。 */
    @Test
    void undeterminedItemsAreNamedByNodeId() {
        List<EhtSections.Undetermined> items = List.of(
                new EhtSections.Undetermined("tests/net/test_bw.py::test_10g[100G]",
                        "缺少 throughput 实测值"));
        String html = html(sections("pytest", counts(), items, List.of(),
                EhtSections.Diff.none()));
        assertTrue(html.contains("tests/net/test_bw.py::test_10g[100G]"));
        assertTrue(html.contains("缺少 throughput 实测值"));
    }

    /** 判据 4：证据层给相对路径且可点击（清洗后仍在）。 */
    @Test
    void evidenceLayerRendersClickableRelativePath() {
        List<EhtSections.Evidence> rows = List.of(new EhtSections.Evidence(
                "tests/net/test_bw.py::test_10g", "吞吐 >= 100G", "98.2G",
                "artifacts/RUN-EHT-0001/cases/test_bw/raw/1.stdout.txt"));
        String html = html(sections("pytest", counts(), List.of(), rows,
                EhtSections.Diff.none()));
        assertTrue(html.contains("<a href=\"artifacts/RUN-EHT-0001/cases/test_bw/raw/1.stdout.txt\""));
        assertTrue(html.contains("判据行原文："));
        assertTrue(html.contains("实测值："));
        assertTrue(ReportHtmlSanitizer.sanitize(html).contains("1.stdout.txt"));
    }

    /** 判据 5：RF 报告明确「不适用」，不许留空的判据行原文列装样子。 */
    @Test
    void rfReportStatesNotApplicableInsteadOfEmptyCriteriaColumn() {
        String html = html(sections("robot", new EhtSections.Counts(0, 0, 0, 10, 10),
                List.of(), List.of(), EhtSections.Diff.none()));
        assertTrue(html.contains("不适用（RF 判据载体未建立）"));
        assertFalse(html.contains("判据行原文："));
    }

    /** 判据 6 的降级面：无基线时显示「无上次执行」，绝不假报新增失败。 */
    @Test
    void noBaselineShowsNoPreviousRun() {
        String html = html(sections("pytest", counts(), List.of(), List.of(),
                EhtSections.Diff.none()));
        assertTrue(html.contains("无上次执行"));
        assertFalse(html.contains("新增失败"));
        assertFalse(html.contains("基线 RUN_ID"));
    }

    /** 附录：十项门禁键逐项可辨、缺失写「未提供」、无载体的环境项写「未采集」。 */
    @Test
    void appendixListsTenRequiredKeysAndMarksUnknownExplicitly() {
        String html = html(sections("pytest", counts(), List.of(), List.of(),
                EhtSections.Diff.none()));
        for (String key : MetadataGate.REQUIRED) {
            assertTrue(html.contains("<dt>" + key + "</dt>"), "附录缺门禁键: " + key);
        }
        assertTrue(html.contains("未提供"));
        assertTrue(html.contains("未采集"));
        assertTrue(html.contains("build-9"));
        assertTrue(html.contains("RUN-EHT-0001@v1"));
    }

    /** 真实库探针回归：库里存绝对路径，展示层必须相对化，不许泄漏部署目录。 */
    @Test
    void absoluteEvidencePathsAreRelativized() {
        assertEquals("artifacts/RUN-1/cases/a/raw/1.stdout.txt",
                EhtSections.relativePath("/home/evergreen/robot-test/artifacts/RUN-1/cases/a/raw/1.stdout.txt"));
        assertEquals("junit.xml", EhtSections.relativePath("/tmp/eht-fake-runs/RUN-2/junit.xml"));
        assertEquals("artifacts/RUN-1/a.txt", EhtSections.relativePath("artifacts/RUN-1/a.txt"));
        assertEquals("artifacts/RUN-1/a.txt",
                EhtSections.relativePath("C:/work/artifacts/RUN-1/a.txt"));
        org.junit.jupiter.api.Assertions.assertNull(EhtSections.relativePath(null));
        org.junit.jupiter.api.Assertions.assertNull(EhtSections.relativePath("   "));
    }

    /** 零回归：旧的 2 参 / 3 参重载仍是平铺 dl，hash 稳定，不带 eht 节点。 */
    @Test
    void legacyFlatSnapshotIsUnchanged() {
        ReportSnapshot legacy = snapshots.build(source(), 1);
        assertTrue(legacy.html().contains("<dl>"));
        assertFalse(legacy.html().contains("id=\"conclusion\""));
        assertFalse(legacy.json().contains("\"eht\""));
        assertEquals(legacy.hash(), snapshots.build(source(), 1).hash());
        assertTrue(legacy.html().contains("RUN-EHT-0001"));
    }


    @Test
    void sectionMetadataCoversAllTenRequiredKeysAndStillScrubs() {
        // W7 真实探针：sectionsOf 曾把对外五键子集喂给十项清单，另五项永远「未提供」。
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        for (String key : MetadataGate.REQUIRED) {
            raw.put(key, "10.20.30.40-" + key);
        }
        Map<String, String> safe = ReportViewRenderer.safeSectionMetadata(raw);
        assertEquals(10, safe.size());
        for (String key : MetadataGate.REQUIRED) {
            assertTrue(safe.containsKey(key), "三段式缺项: " + key);
            assertFalse(safe.get(key).contains("10.20.30.40"), "IP 未脱敏: " + key);
        }
        // 旧对外视图仍严格五键，零回归。
        assertEquals(5, ReportViewRenderer.safeMetadata(raw).size());
        EhtSections s = new EhtSections("pytest", counts(), List.of(), List.of(), safe,
                MetadataGate.REQUIRED, "abc123", EhtSections.Diff.none());
        String html = html(s);
        for (String key : MetadataGate.REQUIRED) {
            assertTrue(html.contains("<dt>" + key + "</dt>"), "附录缺门禁键: " + key);
            assertFalse(html.contains("<dt>" + key + "</dt><dd>未提供"),
                    "十项仍被误判为未提供: " + key);
        }
    }

}
