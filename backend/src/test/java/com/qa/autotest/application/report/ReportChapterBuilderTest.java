package com.qa.autotest.application.report;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告 8 章模型（FR-v5.4-02 / W54-02）。
 *
 * <p>核心判据只有一条，但它最容易做错：<b>缺数据的章仍然存在，并标「无数据」</b>。
 * 「没失败」和「失败分析没跑」在报告里必须长得不一样——否则读者会得出相反结论。
 */
class ReportChapterBuilderTest {

    @Test
    void 八章顺序固定() {
        List<ReportChapterBuilder.Chapter> chapters = ReportChapterBuilder.build(
                ReportChapterBuilder.summary("RUN-1", 10, 10, 0, 0, 100.0),
                null, null, null, null, null, null,
                ReportChapterBuilder.appendix(List.of(), List.of()));

        assertEquals(8, chapters.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, chapters.get(i).no());
            assertEquals(ReportChapterBuilder.TITLES.get(i), chapters.get(i).title());
        }
    }

    @Test
    void 缺数据的章仍在并标无数据() {
        List<ReportChapterBuilder.Chapter> chapters = ReportChapterBuilder.build(
                ReportChapterBuilder.summary("RUN-1", 10, 10, 0, 0, 100.0),
                null, null, null, null, null, null,
                ReportChapterBuilder.appendix(List.of(), List.of()));

        // 第 2~7 章都没数据，但都必须存在
        for (int index = 1; index <= 6; index++) {
            ReportChapterBuilder.Chapter chapter = chapters.get(index);
            assertEquals(ReportChapterBuilder.Status.NO_DATA, chapter.status(),
                    "第 " + chapter.no() + " 章应标 NO_DATA");
            assertTrue(chapter.needsPlaceholder());
            assertNotNull(chapter.missing(), "无数据必须给原因");
            assertFalse(chapter.missing().isBlank());
        }
        assertEquals("无失败（本次执行没有 FAIL 用例）", chapters.get(3).missing());
        assertEquals("未采集（本次执行未开启监视器）", chapters.get(6).missing());
    }

    @Test
    void 有数据的章保持_OK() {
        ReportChapterBuilder.Chapter env = new ReportChapterBuilder.Chapter(
                2, ReportChapterBuilder.TITLES.get(1), ReportChapterBuilder.Status.OK,
                "run_metadata", "commit=abc1234",
                null, List.of(new ReportChapterBuilder.Item("内核", "pytest")));
        List<ReportChapterBuilder.Chapter> chapters = ReportChapterBuilder.build(
                ReportChapterBuilder.summary("RUN-1", 5, 5, 0, 0, 100.0),
                env, null, null, null, null, null,
                ReportChapterBuilder.appendix(List.of(), List.of()));

        assertEquals(ReportChapterBuilder.Status.OK, chapters.get(1).status());
        assertFalse(chapters.get(1).needsPlaceholder());
        assertEquals("commit=abc1234", chapters.get(1).summary());
    }

    @Test
    void 第一与第八章是必填_缺失标_MISSING_而不是_NO_DATA() {
        List<ReportChapterBuilder.Chapter> chapters = ReportChapterBuilder.build(
                null, null, null, null, null, null, null, null);

        assertEquals(ReportChapterBuilder.Status.MISSING, chapters.get(0).status());
        assertEquals(ReportChapterBuilder.Status.MISSING, chapters.get(7).status(),
                "附录与口径是必填章，缺了是报告不完整，不能当「这次没内容」");
    }

    @Test
    void 第八章必含三条口径声明() {
        ReportChapterBuilder.Chapter appendix = ReportChapterBuilder.appendix(
                List.of(new ReportChapterBuilder.Item("output.xml", "/runs/1/output.xml")),
                List.of(new ReportChapterBuilder.Item("用例数", "10")));

        assertEquals(3, ReportChapterBuilder.DECLARATIONS.size());
        long declarations = appendix.items().stream()
                .filter(item -> item.label().startsWith("口径声明"))
                .count();
        assertEquals(3, declarations, "三条声明必须实际出现在 items 里，不能只定义常量");
        String joined = String.join(" ", appendix.items().stream()
                .map(ReportChapterBuilder.Item::value).toList());
        assertTrue(joined.contains("覆盖率"));
        assertTrue(joined.contains("PLANNED"));
        assertTrue(joined.contains("AI"));
    }

    @Test
    void 判定建议按规则推导() {
        assertTrue(ReportChapterBuilder.verdictAdvice(2, 10, 0, 80.0).startsWith("不建议通过"),
                "有失败就必须不建议通过");
        assertTrue(ReportChapterBuilder.verdictAdvice(0, 10, 3, 100.0).startsWith("建议复验"),
                "无失败但有计划缺口要建议复验");
        assertTrue(ReportChapterBuilder.verdictAdvice(0, 10, 0, 100.0).startsWith("建议通过"));
        assertTrue(ReportChapterBuilder.verdictAdvice(0, 0, 0, 0).startsWith("不建议通过"),
                "没有参与判定的用例时不能给「通过」");
    }

    @Test
    void 判定建议不出现_AI_字样() {
        for (int failed = 0; failed <= 2; failed++) {
            String advice = ReportChapterBuilder.verdictAdvice(failed, 10, 1, 90.0);
            assertFalse(advice.toUpperCase(java.util.Locale.ROOT).contains("AI"),
                    "判定建议由规则推导，不得出现 AI 生成内容：" + advice);
        }
    }

    @Test
    void 结论章的字段可核对() {
        ReportChapterBuilder.Chapter summary = ReportChapterBuilder.summary(
                "RUN-20261112000000-abc", 12, 10, 2, 1, 83.3);
        assertEquals("RUN-20261112000000-abc", summary.items().get(0).value());
        assertEquals("10 / 2", summary.items().get(2).value());
        assertEquals("83.3%", summary.items().get(3).value());
        assertEquals("1 条", summary.items().get(4).value());
        assertTrue(summary.summary().contains("2 条失败用例"));
    }

    @Test
    void 无参与判定用例时结论章是_MISSING() {
        ReportChapterBuilder.Chapter summary = ReportChapterBuilder.summary(
                "RUN-1", 0, 0, 0, 0, 0);
        assertEquals(ReportChapterBuilder.Status.MISSING, summary.status());
        assertTrue(summary.missing().contains("没有参与判定"));
    }
}
