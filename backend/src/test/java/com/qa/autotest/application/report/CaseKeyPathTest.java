package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** v5.14.0 / W64-02：切分规则的边界样本，故意包含用例名带点的 pytest 参数化形态。 */
class CaseKeyPathTest {

    @Test
    void rfSingleSuite() {
        CaseKeyPath.Split split = CaseKeyPath.split("suiteA.test_one");
        assertEquals("suiteA", split.suitePath());
        assertEquals("test_one", split.caseName());
        assertEquals(List.of("suiteA"), CaseKeyPath.segments(split.suitePath()));
    }

    @Test
    void nestedSuiteKeepsSlashes() {
        CaseKeyPath.Split split = CaseKeyPath.split("parent/child/nested.test_case");
        assertEquals("parent/child/nested", split.suitePath());
        assertEquals("test_case", split.caseName());
        assertEquals(List.of("parent", "child", "nested"),
                CaseKeyPath.segments(split.suitePath()));
    }

    @Test
    void dotInCaseNameDoesNotMoveSuiteBoundary() {
        CaseKeyPath.Split split = CaseKeyPath.split("suiteA/test.one.two");
        assertEquals("suiteA/test", split.suitePath());
        assertEquals("one.two", split.caseName());
    }

    @Test
    void pytestParameterizedKeyUsesTheSameRule() {
        // 草案假设「pytest 按 . 分隔、RF 按 / 分隔」是错的：链路不读 kernel。
        // 这里钉住 pytest 形态也走同一规则，避免以后有人「按内核优化」把两套算法带回来。
        CaseKeyPath.Split split = CaseKeyPath.split("tests/test_im.py::test_a[1.0]");
        assertEquals("tests/test_im", split.suitePath());
        assertEquals("py::test_a[1.0]", split.caseName());
        assertEquals(List.of("tests", "test_im"), CaseKeyPath.segments(split.suitePath()));
    }

    @Test
    void unknownIsNullNotZero() {
        assertNull(CaseKeyPath.topLevelSuiteCount(null));
        assertNull(CaseKeyPath.topLevelSuiteCount(List.of()));
        assertNull(CaseKeyPath.topLevelSuiteCount(List.of("suiteA.test_one", "")));
    }

    @Test
    void countsDistinctFirstSegmentsOnly() {
        assertEquals(3, CaseKeyPath.topLevelSuiteCount(List.of(
                "suiteA/a.test_1", "suiteA/b.test_2", "suiteB.test_3", "suiteC/n.test_4")));
    }

    @Test
    void keysWithoutSeparatorAreStillTopLevelNodes() {
        // 首版断言写成了 0，实测是 2：既无 / 也无 . 时 split() 把整个 case_key 当作
        // suitePath，套件树会为它建一个一级节点。列表算法必须照抄这个行为，
        // 不能「看起来更合理」地优化成 0 —— 那正是列表与详情树打架的起点。
        // 与树的同源由 SuiteCountTest.pytestMixedKeysTreeMatchesStoredCount 锁死。
        assertEquals(2, CaseKeyPath.topLevelSuiteCount(List.of("IM-NET-131", "IM-NET-132")));
        assertEquals(List.of("IM-NET-131"),
                CaseKeyPath.segments(CaseKeyPath.split("IM-NET-131").suitePath()));
    }
}
