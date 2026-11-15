package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.RunCaseRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v5.14.0 / W64-02 / TC-v5.14-09：列表里的 {@code run.suite_count} 与详情套件树
 * **必须**来自同一份切分规则。
 *
 * <p>验证方式不是「读两个文件确认代码长得一样」，而是把同一批 {@code case_key}
 * 同时喂给 {@link ReportTreeService#buildTree} 和
 * {@link CaseKeyPath#topLevelSuiteCount}，断言树的一级子节点数与落列值相等。
 * 三组样本覆盖：RF 单套件、RF 多套件（含嵌套）、pytest 参数化 / nodeid 混合。
 */
class SuiteCountTest {

    @Test
    void rfSingleSuiteTreeMatchesStoredCount() {
        assertSameCount("RF 单套件", List.of("suiteA.test_one", "suiteA.test_two"));
    }

    @Test
    void rfMultiSuiteTreeMatchesStoredCount() {
        assertSameCount("RF 多套件", List.of(
                "suiteA/a.test_1", "suiteA/b.test_2",
                "suiteB.test_3", "suiteC/nested.test_4"));
    }

    @Test
    void pytestMixedKeysTreeMatchesStoredCount() {
        assertSameCount("pytest 参数化 / nodeid 混合", List.of(
                "tests/test_im.py::test_a[1.0]",
                "tests/test_im.py::test_b[2.0]",
                "IM-NET-131",
                "suiteA/test.one.two"));
    }

    @Test
    void nestedSuitesProduceOneTopLevelNode() {
        List<String> keys = List.of("parent/child/deep.test_1", "parent/other.test_2");
        ReportTreeService.TreeSuite tree = build(keys);
        assertEquals(1, tree.suites().size());
        assertEquals("parent", tree.suites().get(0).name());
        assertEquals(1, CaseKeyPath.topLevelSuiteCount(keys));
    }

    private static void assertSameCount(String label, List<String> keys) {
        ReportTreeService.TreeSuite tree = build(keys);
        Integer stored = CaseKeyPath.topLevelSuiteCount(keys);
        assertNotNull(stored, label + "：样本必须可靠");
        assertEquals(tree.suites().size(), stored,
                label + "：列表套件数必须等于详情树一级子节点数");
    }

    private static ReportTreeService.TreeSuite build(List<String> keys) {
        List<RunCaseRow> rows = keys.stream()
                .map(key -> new RunCaseRow(key, "PASS", 1L, null))
                .toList();
        return ReportTreeService.buildTree("RUN-SUITE-COUNT", rows,
                Map.<String, List<KeywordRow>>of());
    }
}
