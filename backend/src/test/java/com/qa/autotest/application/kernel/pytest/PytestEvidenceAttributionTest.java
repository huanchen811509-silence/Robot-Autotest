package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.caseasset.PytestCaseStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 证据行 → 归因（W52-09）。
 *
 * <p>只测纯函数部分：分组与「结论怎么定」是最容易写错的地方，
 * 而它不需要数据库就能验证。
 */
class PytestEvidenceAttributionTest {

    private static PytestCaseStep step(String nodeId, int seq, String kind, String label,
                                       String content, String status) {
        return PytestCaseStep.draft(9L, null, nodeId, "test_x::test_x", seq, kind, label, content,
                status, null, null, seq, "JUNIT");
    }

    @Test
    void 有FAIL判定时结论是FAIL且原文取失败行() {
        List<PytestEvidenceService.CaseAttribution> result = PytestEvidenceService.attribute(List.of(
                step("tests/a.py::test_a", 1, "DETAIL", "步骤1", "LnkSta=8GT/s", null),
                step("tests/a.py::test_a", 2, "VERDICT", "校验速率", "实测 8GT/s", "FAIL"),
                step("tests/a.py::test_a", 3, "FAILURE", "failure",
                        "校验速率：期望 16GT/s，实测 8GT/s", "FAIL")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).verdict()).isEqualTo("FAIL");
        // FAILURE 行优先于 VERDICT 行：junit 的 failure message 更接近工具链原话。
        assertThat(result.get(0).attribution().original())
                .isEqualTo("校验速率：期望 16GT/s，实测 8GT/s");
        assertThat(result.get(0).attribution().category())
                .isEqualTo(com.qa.autotest.application.report.FailureAttributor.CATEGORY_ASSERT);
    }

    @Test
    void 全部SKIP时结论是SKIP且归PlanOnly() {
        List<PytestEvidenceService.CaseAttribution> result = PytestEvidenceService.attribute(List.of(
                step("tests/b.py::test_b", 1, "DETAIL", "步骤1", "Plan-only 无真实回显", "SKIP"),
                step("tests/b.py::test_b", 2, "DETAIL", "步骤2", "Plan-only 无真实回显", "SKIP")));

        assertThat(result.get(0).verdict()).isEqualTo("SKIP");
        assertThat(result.get(0).attribution().category())
                .isEqualTo(com.qa.autotest.application.report.FailureAttributor.CATEGORY_PLAN_ONLY);
    }

    @Test
    void 只有通过判定时结论是PASS且不出建议() {
        List<PytestEvidenceService.CaseAttribution> result = PytestEvidenceService.attribute(List.of(
                step("tests/c.py::test_c", 1, "VERDICT", "读取链路", "", "PASS")));

        assertThat(result.get(0).verdict()).isEqualTo("PASS");
        assertThat(result.get(0).attribution().suggestion()).isEmpty();
    }

    @Test
    void 多个用例分别归因互不串味() {
        List<PytestEvidenceService.CaseAttribution> result = PytestEvidenceService.attribute(List.of(
                step("tests/a.py::test_a", 1, "VERDICT", "x", "不通过（实测 8GT/s）", "FAIL"),
                step("tests/d.py::test_d", 1, "VERDICT", "y", "", "PASS")));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).nodeId()).isEqualTo("tests/a.py::test_a");
        assertThat(result.get(0).verdict()).isEqualTo("FAIL");
        assertThat(result.get(1).verdict()).isEqualTo("PASS");
    }

    @Test
    void 空输入返回空表不抛() {
        assertThat(PytestEvidenceService.attribute(List.<PytestCaseStep>of())).isEmpty();
        assertThat(PytestEvidenceService.attribute(null)).isEmpty();
    }
}
