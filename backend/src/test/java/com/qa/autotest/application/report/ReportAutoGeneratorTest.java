package com.qa.autotest.application.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告自动生成的四条件（FR-v5.4-01 / D1–D3 / W54-01）。
 *
 * <p>每个用例只关掉一个条件，验「少一条就不生成」，并且失败原因必须能指出是哪一条。
 * 这条要求不是洁癖：用户打开开关后发现没出报告，如果页面只说「不满足条件」，
 * 他只能挨个去猜——而四个条件里有三个在别的页面。
 */
class ReportAutoGeneratorTest {

    private static ReportAutoGenerator.Decision decide(boolean enabled, String trigger,
                                                       boolean terminal, String mode,
                                                       int results, Long template,
                                                       boolean already) {
        return ReportAutoGenerator.decide(enabled, trigger, terminal, mode, results, template,
                already);
    }

    @Test
    void 四条件全满足才生成() {
        ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, "real",
                12, 7L, false);
        assertTrue(decision.generate());
        assertNull(decision.reason());
        assertEquals(7L, decision.templateId());
    }

    @Test
    void 开关关闭不生成() {
        ReportAutoGenerator.Decision decision = decide(false, "run_finished", true, "real",
                12, 7L, false);
        assertFalse(decision.generate());
        assertTrue(decision.reason().contains("report.auto_generate"),
                "原因要指出是哪个配置键，用户才知道去哪改：" + decision.reason());
    }

    @Test
    void 非_real_模式不生成() {
        for (String mode : new String[] {"fake", "FAKE", "plan", "precheck"}) {
            ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, mode,
                    12, 7L, false);
            assertFalse(decision.generate(), "模式 " + mode + " 不该自动出正式报告");
            assertTrue(decision.reason().contains(mode));
        }
    }

    @Test
    void 模式未记录时代码保守不生成() {
        for (String mode : new String[] {null, "", "   "}) {
            ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, mode,
                    12, 7L, false);
            assertFalse(decision.generate(),
                    "拿不到 mode 就不能假设它是 real，否则试跑会产出正式报告");
        }
    }

    @Test
    void 没有用例结果不生成() {
        ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, "real",
                0, 7L, false);
        assertFalse(decision.generate());
        assertTrue(decision.reason().contains("空壳"), decision.reason());
    }

    @Test
    void 没有模板不生成() {
        ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, "real",
                12, null, false);
        assertFalse(decision.generate());
        assertTrue(decision.reason().contains("模板"));
    }

    @Test
    void run_未终态不生成() {
        ReportAutoGenerator.Decision decision = decide(true, "run_finished", false, "real",
                12, 7L, false);
        assertFalse(decision.generate());
        assertTrue(decision.reason().contains("终态"));
    }

    @Test
    void 已生成过则不重复建() {
        ReportAutoGenerator.Decision decision = decide(true, "run_finished", true, "real",
                12, 7L, true);
        assertFalse(decision.generate());
        assertTrue(decision.reason().contains("幂等"), decision.reason());
        assertEquals(7L, decision.templateId(), "重复时仍要回模板 id，便于页面展示「已有一份」");
    }

    @Test
    void 非_run_finished_触发器不由终态触发() {
        for (String trigger : new String[] {"scheduled", "manual", "MANUAL"}) {
            ReportAutoGenerator.Decision decision = decide(true, trigger, true, "real",
                    12, 7L, false);
            assertFalse(decision.generate(), "触发器 " + trigger + " 不该在 run 终态时生成");
            assertTrue(decision.reason().contains(trigger.toLowerCase()), decision.reason());
        }
    }

    @Test
    void 触发器缺省按_run_finished() {
        assertFalse(decide(true, null, false, "real", 12, 7L, false).generate(),
                "默认触发器是 run_finished，所以「未终态」这条会命中");
        assertTrue(decide(true, null, true, "real", 12, 7L, false).generate());
        assertTrue(decide(true, "  ", true, "real", 12, 7L, false).generate());
    }

    @Test
    void 布尔解析只认明确真值() {
        assertTrue(ReportAutoGenerator.parseBoolean("true", false));
        assertTrue(ReportAutoGenerator.parseBoolean("1", false));
        assertTrue(ReportAutoGenerator.parseBoolean(" YES ", false));
        assertTrue(ReportAutoGenerator.parseBoolean("on", false));
        assertFalse(ReportAutoGenerator.parseBoolean("false", true));
        assertFalse(ReportAutoGenerator.parseBoolean("0", true));
        assertFalse(ReportAutoGenerator.parseBoolean("off", true));
        // 错写按 fallback，不按「非 false 即 true」——那会让一个笔误变成「全量自动出报告」
        assertFalse(ReportAutoGenerator.parseBoolean("tru", false));
        assertTrue(ReportAutoGenerator.parseBoolean("tru", true));
        assertFalse(ReportAutoGenerator.parseBoolean(null, false));
    }
}
