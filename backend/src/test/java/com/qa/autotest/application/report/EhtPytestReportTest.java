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
                Map.of("SW_BUILD", "build-9"), MetadataGate.REQUIRED, "abc123", diff,
                EhtSections.Assembly.ok(), EhtSections.Plan.absent("本次为单元测试，无执行计划"));
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
        // v5.22.0：对外 metadata 由五键扩到七键（加 CASE_ID / RUN_ID），仍严格过滤十项里的其余三项。
        Map<String, String> safeExternal = ReportViewRenderer.safeMetadata(raw);
        assertEquals(7, safeExternal.size());
        assertTrue(safeExternal.containsKey("CASE_ID"), "对外视图缺 CASE_ID");
        assertTrue(safeExternal.containsKey("RUN_ID"), "对外视图缺 RUN_ID");
        assertFalse(safeExternal.containsKey("OPERATOR"), "OPERATOR 不该进对外视图");
        EhtSections s = new EhtSections("pytest", counts(), List.of(), List.of(), safe,
                MetadataGate.REQUIRED, "abc123", EhtSections.Diff.none(),
                EhtSections.Assembly.ok(), EhtSections.Plan.absent("无执行计划"));
        String html = html(s);
        for (String key : MetadataGate.REQUIRED) {
            assertTrue(html.contains("<dt>" + key + "</dt>"), "附录缺门禁键: " + key);
            assertFalse(html.contains("<dt>" + key + "</dt><dd>未提供"),
                    "十项仍被误判为未提供: " + key);
        }
    }

    /**
     * W-EHT-06 判据 1：装配失败必须在报告最前面写明原因。
     * 此前失败与"没跑"都返回空结构，报告只有一串 0，读者无法分辨——这就是"假大空"。
     */
    @Test
    void assemblyFailureIsExplicitNotSilentZeros() {
        String html = html(EhtSections.failed("pytest", "读取 run 环境失败：连接超时"));
        assertTrue(html.contains("报告装配失败"), "装配失败没有显性横幅");
        assertTrue(html.contains("读取 run 环境失败"), "失败原因没有带出来");
        assertTrue(html.contains("不代表本次执行没有用例"), "没有纠正读者的误读");
        // 失败说明要排在结论层之前，读者先看到原因再看到 0。
        assertTrue(html.indexOf("报告装配失败") < html.indexOf("id=\"conclusion\""));
    }

    /** W-EHT-06 判据 2：跑空了（装配成功、0 用例结果）与装配失败必须说成两件事。 */
    @Test
    void noDataIsDistinguishedFromAssemblyFailure() {
        String html = html(EhtSections.noData("pytest"));
        assertTrue(html.contains("本次执行没有任何用例结果"));
        assertFalse(html.contains("报告装配失败"), "空跑不该被说成装配失败");
    }

    /** W-EHT-06 判据 3：证据层空字段显性化，不许留下"判据行原文："后面一片空白。 */
    @Test
    void emptyEvidenceFieldsSaySoInsteadOfRenderingBlank() {
        EhtSections s = sections("pytest", counts(), List.of(),
                List.of(new EhtSections.Evidence("case#1", null, "  ", null)),
                EhtSections.Diff.none());
        String html = html(s);
        assertTrue(html.contains("未采集到判据（缺证据）"));
        assertTrue(html.contains("未采集到实测值"));
        assertFalse(html.contains("判据行原文：<br>"), "空判据仍然渲染成了空白标签");
    }

    /**
     * W-EHT-07 判据：fake 计划必须进报告。
     * 「没连设备、没下发命令、没伪造 stdout」这三条是读者判断证据可信度的前提，
     * 候选分支则是人工回放的依据，都不能只躺在 json 文件里。
     */
    @Test
    void planIsRenderedWithConnectionFactAndBranches() {
        EhtSections.Plan plan = new EhtSections.Plan(true, "fake-plan-only", false, false,
                List.of(new EhtSections.Plan.PlanCommand("gpu01@10.202.54.101",
                        "sudo -n lspci -Dnnvv -s 45:00.0", "读取 PCIe 能力",
                        "Vendor/Device ID 与 LnkCap")),
                List.of(new EhtSections.Plan.PlanBranch("IM-NET-002", "set_link_speed",
                        "speed-32g", "设备支持 32GT/s", "链路协商到 32GT/s")),
                null);
        EhtSections s = new EhtSections("pytest", counts(), List.of(), List.of(),
                Map.of("SW_BUILD", "build-9"), MetadataGate.REQUIRED, "abc123",
                EhtSections.Diff.none(), EhtSections.Assembly.ok(), plan);

        String html = html(s);
        assertTrue(html.contains("执行计划（fake / Plan-only）"));
        assertTrue(html.contains("否（未连接设备）"), "没连设备这个事实没有进报告");
        assertTrue(html.contains("否（无伪造）"));
        assertTrue(html.contains("lspci"));
        assertTrue(html.contains("speed-32g"), "候选分支没有进报告");
        assertTrue(html.contains("设备支持 32GT/s"));
    }

    /** 没有计划时也要给一句解释，不许留一个空的段标题。 */
    @Test
    void absentPlanExplainsItself() {
        String html = html(sections("pytest", counts(), List.of(), List.of(),
                EhtSections.Diff.none()));
        assertTrue(html.contains("执行计划（fake / Plan-only）"));
        assertTrue(html.contains("本次为单元测试，无执行计划"));
    }
}
