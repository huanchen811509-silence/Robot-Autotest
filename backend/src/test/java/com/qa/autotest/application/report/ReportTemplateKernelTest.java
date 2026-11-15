package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.report.ReportTemplate;
import com.qa.autotest.infrastructure.ReportTemplateRepositoryJdbc;
import java.util.List;
import org.junit.jupiter.api.Test;

/** v5.14.0 / W64-10~13：kernel 维度、白名单分组与四级回退的单一真值。 */
class ReportTemplateKernelTest {

    @Test
    void genericOrderStaysAt21() {
        // 判据 2：标准 RF 工程字段数不变——pytest 专属字段只扩「允许集合」，不进默认顺序。
        assertEquals(21, ReportFieldWhitelist.EXTERNAL_ORDER.size());
        assertEquals(21, ReportFieldWhitelist.selectable("robot").size());
        assertEquals(21, ReportFieldWhitelist.selectable("any").size());
    }

    @Test
    void pytestKernelAddsFiveDedicatedFields() {
        List<String> fields = ReportFieldWhitelist.selectable("pytest");
        assertEquals(26, fields.size());
        assertTrue(fields.contains("nodeId"));
        assertTrue(fields.contains("criteriaConclusion"));
        assertTrue(ReportFieldWhitelist.allows("artifactPath"));
        assertFalse(ReportFieldWhitelist.allows("notAField"));
        // 未知内核默认拒绝扩展，不给「随便传个 kernel 就放开字段」的口子。
        assertEquals(21, ReportFieldWhitelist.selectable("something-else").size());
    }

    @Test
    void kernelNormalizationAndValidation() {
        assertEquals("robot", ReportTemplate.normalizeKernel(null));
        assertEquals("pytest", ReportTemplate.normalizeKernel(" PyTest "));
        assertEquals("robot", ReportTemplate.normalizeKernel("unknown"));
        assertTrue(ReportTemplate.validKernel("any"));
        assertFalse(ReportTemplate.validKernel(""));
        assertEquals("pytest", ReportTemplateService.requireKernel("PYTEST"));
        assertThrows(BusinessException.class,
                () -> ReportTemplateService.requireKernel("ruby"));
    }

    @Test
    void legacyNineArgConstructorKeepsRobot() {
        ReportTemplate legacy = new ReportTemplate(1L, 7L, "old", "", 1, true,
                List.of(), null, null);
        assertEquals("robot", legacy.kernel());
    }

    @Test
    void selectionSourceIsVisibleAndGenericOnlyOnFallback() {
        assertEquals("PROJECT_KERNEL_MATCH", TemplateSelection.source(0));
        assertEquals("PLATFORM_KERNEL_MATCH", TemplateSelection.source(1));
        assertEquals("PLATFORM_ANY", TemplateSelection.source(2));
        assertEquals("PLATFORM_FALLBACK", TemplateSelection.source(3));
        assertTrue(TemplateSelection.generic("PLATFORM_ANY"));
        assertTrue(TemplateSelection.generic("PLATFORM_FALLBACK"));
        assertFalse(TemplateSelection.generic("PLATFORM_KERNEL_MATCH"));
        // 判据 2：标准 RF 命中 DEFAULT(robot)，属于内核匹配而不是「回退到通用模板」。
        int rfRank = ReportTemplateRepositoryJdbc.rank(0L, "robot", 7L, "robot");
        assertEquals(1, rfRank);
        assertFalse(TemplateSelection.generic(TemplateSelection.source(rfRank)));
        // EHT pytest 命中 EHT-PYTEST，也不该误报通用模板。
        int ehtRank = ReportTemplateRepositoryJdbc.rank(0L, "pytest", 7L, "pytest");
        assertEquals(1, ehtRank);
        assertFalse(TemplateSelection.generic(TemplateSelection.source(ehtRank)));
        // 内核不匹配时才是判据 3 要提示的场景。
        assertEquals("PLATFORM_FALLBACK", TemplateSelection.source(
                ReportTemplateRepositoryJdbc.rank(0L, "robot", 7L, "pytest")));
    }

    @Test
    void fourFallbackLevelsAreStrictlyOrdered() {
        // ① 本项目 + kernel 匹配
        assertEquals(0, ReportTemplateRepositoryJdbc.rank(7L, "pytest", 7L, "pytest"));
        // ② 平台 + kernel 匹配
        assertEquals(1, ReportTemplateRepositoryJdbc.rank(0L, "pytest", 7L, "pytest"));
        // ③ 平台 any
        assertEquals(2, ReportTemplateRepositoryJdbc.rank(0L, "any", 7L, "pytest"));
        // ④ 其它（兜底，仍优先于「没有模板」）
        assertEquals(3, ReportTemplateRepositoryJdbc.rank(0L, "robot", 7L, "pytest"));
    }
}
