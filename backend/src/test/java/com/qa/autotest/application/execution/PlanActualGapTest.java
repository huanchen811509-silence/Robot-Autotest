package com.qa.autotest.application.execution;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划 vs 实际差集（FR-v5.4-06 / W54-07 / GAP-16 / P7）。
 *
 * <p>本类只验两件事：<b>键归一对不对</b>、<b>缺口说不说得清</b>。
 * 前者错了会把「同一用例」算成两条；后者错了会让人把「少跑了」当「全通过」。
 */
class PlanActualGapTest {

    @Test
    void 一条不少时没有提示() {
        PlanActualGap.Gap gap = PlanActualGap.of(
                List.of("tests/a/test_x.py::test_x"), List.of("tests.a.test_x::test_x"), "pytest");
        assertEquals(1, gap.planned());
        assertEquals(1, gap.actual());
        assertFalse(gap.hasGap());
        assertNull(gap.notice(), "没有缺口就不要生成提示，否则提示会变成噪音");
    }

    @Test
    void 斜杠与点分两种形态算同一条用例() {
        assertEquals(PlanActualGap.keyOf("tests/nic/base/test_im_fun_020.py::test_im_fun_020"),
                PlanActualGap.keyOf("tests.nic.base.test_im_fun_020::test_im_fun_020"));
    }

    @Test
    void 参数化后缀被剥掉() {
        assertEquals("test_x::test_x", PlanActualGap.keyOf("tests/a/test_x.py::test_x[a-1]"));
        assertEquals("test_x::test_x", PlanActualGap.keyOf("tests/a/test_x.py::test_x[b-2]"));
    }

    @Test
    void 类方法取文件名与末段函数名() {
        assertEquals("test_b::test_d",
                PlanActualGap.keyOf("tests/a/test_b.py::TestC::test_d[p]"));
    }

    @Test
    void 少跑时提示点明参数化与条数() {
        PlanActualGap.Gap gap = PlanActualGap.of(
                List.of("tests/a/test_x.py::test_x", "tests/a/test_y.py::test_y"),
                List.of("tests.a.test_x::test_x"), "pytest");
        assertTrue(gap.hasGap());
        assertEquals(1, gap.notRunTotal());
        assertEquals(List.of("test_y::test_y"), gap.notRun());
        assertTrue(gap.notice().contains("--case-param"), gap.notice());
        assertTrue(gap.notice().contains("2 条"), "要说清计划几条：" + gap.notice());
        assertTrue(gap.notice().contains("1 条没有任何结果"), gap.notice());
    }

    @Test
    void robot内核不提case_param() {
        PlanActualGap.Gap gap = PlanActualGap.of(
                List.of("a.robot::case1"), List.of(), "robot");
        assertTrue(gap.hasGap());
        assertFalse(gap.notice().contains("--case-param"),
                "RF 的问题跟逐例参数无关，提它只会误导：" + gap.notice());
    }

    @Test
    void 多跑不算缺口() {
        PlanActualGap.Gap gap = PlanActualGap.of(
                List.of("tests/a/test_x.py::test_x"),
                List.of("tests/a/test_x.py::test_x", "tests/a/test_z.py::test_z"), "pytest");
        assertFalse(gap.hasGap(), "多出来的结果不是漏跑");
        assertEquals(1, gap.unexpected());
        assertNull(gap.notice());
    }

    @Test
    void 大量漏跑时样本截断但总数不截断() {
        List<String> planned = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            planned.add("tests/a/test_" + i + ".py::test_" + i);
        }
        PlanActualGap.Gap gap = PlanActualGap.of(planned, List.of(), "pytest");
        assertEquals(12, gap.notRunTotal());
        assertEquals(PlanActualGap.SAMPLE_LIMIT, gap.notRun().size());
        assertTrue(gap.notice().contains("12 条没有任何结果"), gap.notice());
    }

    @Test
    void 空输入不炸() {
        PlanActualGap.Gap gap = PlanActualGap.of(null, null, "pytest");
        assertEquals(0, gap.planned());
        assertEquals(0, gap.actual());
        assertFalse(gap.hasGap());
        assertEquals("", PlanActualGap.keyOf(null));
        assertEquals("", PlanActualGap.keyOf("   "));
    }
}
