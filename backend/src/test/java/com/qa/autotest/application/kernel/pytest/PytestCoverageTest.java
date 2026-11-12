package com.qa.autotest.application.kernel.pytest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率双口径算术（FR-v5.1-05 / H11 / D12 / v5.1.2）。
 *
 * <p>三个必须在任何数据下都成立的式子：
 * <ol>
 *   <li>{@code bound + unbound == total}（恒等式）；</li>
 *   <li>口径 B 的分子**必须**是「已绑定且非待评审」，不是「已绑定」——
 *       用错分子会把 10.3% 算成 21%，而且看起来更漂亮、更难被发现；</li>
 *   <li>分母为 0 时返回 0.0，不抛不 NaN（新项目第一天就会遇到）。</li>
 * </ol>
 */
class PytestCoverageTest {

    private static Map<String, String> priorities(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    @Test
    void 实测基线_469_61_复现两个官方口径() {
        // 469 条（178 待评审）、已绑 61；待评审里已绑 31 → 排除口径 30/291
        Map<String, String> table = new LinkedHashMap<>();
        List<String> bound = new java.util.ArrayList<>();
        for (int i = 0; i < 61; i++) {
            table.put("B-" + i, i < 31 ? PytestCoverage.PRIORITY_PENDING : "P1");
            bound.add("B-" + i);
        }
        for (int i = 0; i < 178 - 31; i++) {
            table.put("P-" + i, PytestCoverage.PRIORITY_PENDING);
        }
        for (int i = 0; i < 469 - 61 - 147; i++) {
            table.put("U-" + i, "P2");
        }
        PytestCoverage coverage = PytestCoverage.of(table, bound);
        assertEquals(469, coverage.totalCases());
        assertEquals(61, coverage.bound());
        assertEquals(178, coverage.pendingCases());
        assertEquals(291, coverage.totalExcludingPending());
        assertEquals(30, coverage.boundExcludingPending());
        assertEquals(13.0, coverage.coveragePercent(), 0.05);
        assertEquals(10.3, coverage.coverageExcludingPendingPercent(), 0.05);
        assertTrue(coverage.balanced());
    }

    @Test
    void 排除口径的分子不是已绑定总数() {
        // A 已绑（非待评审）、B 已绑（待评审）、C 未绑（非待评审）
        PytestCoverage coverage = PytestCoverage.of(
                priorities("A", "P0", "B", PytestCoverage.PRIORITY_PENDING, "C", "P0"),
                List.of("A", "B"));
        assertEquals(2, coverage.bound());
        assertEquals(1, coverage.boundExcludingPending(), "待评审里那条不算进分子 B");
        assertEquals(66.7, coverage.coveragePercent(), 0.05);
        assertEquals(50.0, coverage.coverageExcludingPendingPercent(), 0.05);
    }

    @Test
    void 全部待评审时分母为零返回零而不是除零() {
        PytestCoverage coverage = PytestCoverage.of(
                priorities("A", PytestCoverage.PRIORITY_PENDING), List.of("A"));
        assertEquals(0, coverage.totalExcludingPending());
        assertEquals(0.0, coverage.coverageExcludingPendingPercent(), 0.0001);
    }

    @Test
    void 空项目不抛异常() {
        PytestCoverage coverage = PytestCoverage.of(Map.of(), List.of());
        assertEquals(0, coverage.totalCases());
        assertEquals(0.0, coverage.coveragePercent(), 0.0001);
        assertTrue(coverage.balanced());
    }

    @Test
    void 脚本侧多出来的编号不进分子() {
        // 绑定表里有一个库里不存在的编号（脏数据/改名残留）：不许抬高覆盖率
        PytestCoverage coverage = PytestCoverage.of(priorities("A", "P0"), List.of("A", "GHOST"));
        assertEquals(1, coverage.bound());
        assertEquals(100.0, coverage.coveragePercent(), 0.05);
    }

    @Test
    void 待评审按去空白后比较() {
        PytestCoverage coverage = PytestCoverage.of(priorities("A", " 待评审 "), List.of());
        assertEquals(1, coverage.pendingCases());
        assertEquals(0, coverage.totalExcludingPending());
    }

    @Test
    void 未绑定数是余数且不为负() {
        Map<String, String> table = priorities("A", "P0");
        PytestCoverage coverage = PytestCoverage.of(table, List.of("A", "GHOST", "GHOST2"));
        assertEquals(1, coverage.bound());
        assertEquals(0, coverage.unbound());
        assertFalse(coverage.unbound() < 0);
    }
}
