package com.qa.autotest.application.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureBreakdownTest {

    private static FailureBreakdown.Sample sample(String code, String verdict, String category) {
        return new FailureBreakdown.Sample(code, "pytest", verdict, category,
                "test_im_net_131.py:42", "assert speed == 100");
    }

    @Test
    void 四类归位且合计等于样本数() {
        List<FailureBreakdown.Sample> rows = List.of(
                sample("A-1", "FAIL", FailureAttributor.CATEGORY_ASSERT),
                sample("A-2", "FAIL", FailureAttributor.CATEGORY_SCRIPT),
                sample("A-3", "ERROR", FailureAttributor.CATEGORY_ENV),
                sample("A-4", "ERROR", FailureAttributor.CATEGORY_CONFIG),
                sample("A-5", "SKIP", FailureAttributor.CATEGORY_PLAN_ONLY),
                sample("A-6", "FAIL", FailureAttributor.CATEGORY_UNKNOWN));
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(20, 14, 0, rows);
        assertEquals(2, snapshot.productFails(), "ASSERT + SCRIPT 属产品侧");
        assertEquals(2, snapshot.envFails(), "ENV + CONFIG 属环境侧");
        assertEquals(1, snapshot.skipped());
        assertEquals(1, snapshot.unknown());
        assertEquals(6, snapshot.samples());
        assertTrue(snapshot.balanced(), snapshot.imbalance());
        assertTrue(snapshot.totalConsistent());
    }

    @Test
    void 跳过结论不看分类一律归跳过() {
        FailureBreakdown.Sample row = sample("A-1", "SKIP", FailureAttributor.CATEGORY_ASSERT);
        assertEquals(FailureBreakdown.SIDE_SKIP, FailureBreakdown.sideOf(row));
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(2, 1, 0, List.of(row));
        assertEquals(1, snapshot.skipped());
        assertEquals(0, snapshot.productFails(), "SKIP 不能被算成产品失败");
    }

    @Test
    void 环境失败不进产品通过率分母() {
        List<FailureBreakdown.Sample> rows = List.of(
                sample("A-1", "FAIL", FailureAttributor.CATEGORY_ASSERT),
                sample("A-2", "ERROR", FailureAttributor.CATEGORY_ENV),
                sample("A-3", "ERROR", FailureAttributor.CATEGORY_ENV),
                sample("A-4", "ERROR", FailureAttributor.CATEGORY_ENV));
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(10, 6, 0, rows);
        assertEquals(7, snapshot.productDenominator(), "分母 = 通过 6 + 产品失败 1");
        assertEquals(85.7, snapshot.productPassRate(), 1e-9);
        assertTrue(snapshot.productPassRate() > 80.0,
                "3 条环境失败把通过率从 100% 拉到最低限，但不能真的算进产品侧");
    }

    @Test
    void 分母为零时通过率为零且不抛异常() {
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(3, 0, 3, List.of());
        assertEquals(0, snapshot.productDenominator());
        assertEquals(0.0, snapshot.productPassRate(), 1e-9);
        assertNull(snapshot.imbalance(), "空样本不该报不平衡");
    }

    @Test
    void 失败侧对不上时给出不平衡说明() {
        // total 写成 99 与「通过 6 + 失败 1 + 计划 0」不符
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(99, 6, 0,
                List.of(sample("A-1", "FAIL", FailureAttributor.CATEGORY_ASSERT)));
        assertFalse(snapshot.totalConsistent());
        assertNotNull(snapshot.imbalance());
        assertTrue(snapshot.imbalance().contains("口径不平衡"), snapshot.imbalance());
    }

    @Test
    void TopN按条数降序同数按指纹升序以保证可复现() {
        List<FailureBreakdown.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new FailureBreakdown.Sample("A-" + i, "pytest", "FAIL",
                    FailureAttributor.CATEGORY_ENV, "test_a.py:1", "connection refused"));
        }
        rows.add(sample("B-1", "FAIL", FailureAttributor.CATEGORY_ASSERT));
        rows.add(sample("B-2", "FAIL", FailureAttributor.CATEGORY_ASSERT));

        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(10, 5, 0, rows);
        List<FailureBreakdown.Cluster> top = snapshot.top(2);
        assertEquals(2, top.size());
        assertEquals(3, top.get(0).count(), "出现最多的排第一");
        assertEquals(2, top.get(1).count());

        List<FailureBreakdown.Cluster> again = FailureBreakdown.of(10, 5, 0, rows).top(2);
        assertEquals(top.get(0).fingerprint(), again.get(0).fingerprint(), "两次结果必须一致");
        assertEquals(top.get(1).fingerprint(), again.get(1).fingerprint());
    }

    @Test
    void TopN的n被夹住且负数返回空() {
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(2, 1, 0,
                List.of(sample("A-1", "FAIL", FailureAttributor.CATEGORY_ASSERT)));
        assertEquals(List.of(), snapshot.top(0));
        assertEquals(List.of(), snapshot.top(-5));
        assertEquals(1, snapshot.top(999).size(), "n 超长不该抛 IndexOutOfBounds");
    }

    @Test
    void 下钻列表截断时标注真实条数() {
        List<FailureBreakdown.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(sample("C-" + i, "FAIL", FailureAttributor.CATEGORY_ASSERT));
        }
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(30, 5, 0, rows);
        FailureBreakdown.Bucket bucket = snapshot.buckets().get(0);
        assertEquals(25, bucket.count());
        assertEquals(20, bucket.caseCodes().size(), "下钻列表上限 20");
        assertTrue(bucket.drillDown().contains("等 25 条"), bucket.drillDown());
    }

    @Test
    void 无失败时第4章标无数据而不是省略() {
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(10, 10, 0, List.of());
        ReportChapterBuilder.Chapter chapter = snapshot.chapter();
        assertEquals(4, chapter.no());
        assertEquals(ReportChapterBuilder.Status.NO_DATA, chapter.status());
        assertEquals("失败分析", chapter.title());
        assertNotNull(chapter.missing());
    }

    @Test
    void 有失败时第4章写明分母口径() {
        List<FailureBreakdown.Sample> rows = List.of(
                sample("A-1", "FAIL", FailureAttributor.CATEGORY_ASSERT),
                sample("A-2", "ERROR", FailureAttributor.CATEGORY_ENV));
        ReportChapterBuilder.Chapter chapter = FailureBreakdown.of(6, 4, 0, rows).chapter();
        assertEquals(ReportChapterBuilder.Status.OK, chapter.status());
        String text = chapter.items().stream()
                .map(item -> item.label() + "=" + item.value())
                .reduce("", (left, right) -> left + " | " + right);
        assertTrue(text.contains("产品通过率分母"), text);
        assertTrue(text.contains("已排除环境"), text);
        assertTrue(chapter.summary().contains("产品失败 1 条"));
        assertTrue(chapter.summary().contains("环境失败 1 条"));
    }

    @Test
    void 未归类在报告里显式点名不混入产品侧() {
        List<FailureBreakdown.Sample> rows = List.of(
                sample("A-1", "FAIL", FailureAttributor.CATEGORY_UNKNOWN));
        FailureBreakdown.Snapshot snapshot = FailureBreakdown.of(2, 1, 0, rows);
        ReportChapterBuilder.Chapter chapter = snapshot.chapter();
        String text = chapter.items().stream()
                .map(item -> item.label() + "=" + item.value())
                .reduce("", (left, right) -> left + " | " + right);
        assertTrue(text.contains("需人工判断"), text);
        assertEquals(1, snapshot.productDenominator(), "未归类不进分母");
    }
}
