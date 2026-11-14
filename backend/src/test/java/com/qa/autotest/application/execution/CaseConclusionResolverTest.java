package com.qa.autotest.application.execution;

import com.qa.autotest.application.execution.CaseConclusionResolver.Conclusion;
import com.qa.autotest.application.execution.CaseConclusionResolver.Coverage;
import com.qa.autotest.application.execution.CaseConclusionResolver.VerdictView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** W57-23：5 行判定矩阵、旧数据（NULL）降级未判定、覆盖率计算。 */
class CaseConclusionResolverTest {

    @Test
    @DisplayName("矩阵第 1 行：completed + PASS → PASS")
    void 矩阵第一行() {
        assertEquals(Conclusion.PASS, CaseConclusionResolver.resolve("completed", "PASS"));
    }

    @Test
    @DisplayName("矩阵第 2 行：completed + FAIL → FAIL")
    void 矩阵第二行() {
        assertEquals(Conclusion.FAIL, CaseConclusionResolver.resolve("completed", "FAIL"));
    }

    @Test
    @DisplayName("矩阵第 3 行：completed + MISSING → UNDETERMINED")
    void 矩阵第三行() {
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve("completed", "MISSING"));
    }

    @Test
    @DisplayName("矩阵第 4 行：completed + NULL → UNDETERMINED，禁止默认 PASS")
    void 矩阵第四行旧数据() {
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve("completed", null));
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve("completed", ""));
        assertNotEquals(Conclusion.PASS, CaseConclusionResolver.resolve("completed", null));
    }

    @Test
    @DisplayName("矩阵第 5 行：failed + 任意判据 → FAIL（进程失败一律 FAIL）")
    void 矩阵第五行() {
        assertEquals(Conclusion.FAIL, CaseConclusionResolver.resolve("failed", "PASS"));
        assertEquals(Conclusion.FAIL, CaseConclusionResolver.resolve("failed", null));
        assertEquals(Conclusion.FAIL, CaseConclusionResolver.resolve("failed", "MISSING"));
    }

    @Test
    @DisplayName("未知 status 不默认 PASS，降级 UNDETERMINED")
    void 未知状态() {
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve("running", "PASS"));
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve(null, "PASS"));
    }

    @Test
    @DisplayName("normalizeStatus：run 状态归一到 completed/failed")
    void 状态归一() {
        assertEquals("completed", CaseConclusionResolver.normalizeStatus("passed"));
        assertEquals("completed", CaseConclusionResolver.normalizeStatus("running"));
        assertEquals("failed", CaseConclusionResolver.normalizeStatus("failed"));
        assertEquals("failed", CaseConclusionResolver.normalizeStatus("cancelled"));
        assertEquals("completed", CaseConclusionResolver.normalizeStatus(null));
    }

    @Test
    @DisplayName("覆盖率：声明率、已判定率、各计数")
    void 覆盖率() {
        List<VerdictView> views = List.of(
                new VerdictView("completed", "PASS"),
                new VerdictView("completed", "FAIL"),
                new VerdictView("completed", "MISSING"),
                new VerdictView("completed", null),
                new VerdictView("failed", null));
        Coverage coverage = CaseConclusionResolver.coverage(views);
        assertEquals(5, coverage.total());
        assertEquals(3, coverage.declared());
        assertEquals(2, coverage.undeclared());
        assertEquals(1, coverage.pass());
        assertEquals(1, coverage.fail());
        assertEquals(1, coverage.missing());
        // UNDETERMINED：completed+MISSING 与 completed+NULL 两条；failed+NULL 是 FAIL 不算
        assertEquals(2, coverage.undetermined());
        assertEquals(0.6d, coverage.declaredRate(), 1e-9);
        assertEquals(0.6d, coverage.determinedRate(), 1e-9);
    }

    @Test
    @DisplayName("覆盖率：空列表不除零")
    void 空覆盖率() {
        Coverage coverage = CaseConclusionResolver.coverage(List.of());
        assertEquals(0, coverage.total());
        assertEquals(0d, coverage.declaredRate(), 1e-9);
        assertEquals(0d, coverage.determinedRate(), 1e-9);
    }

    @Test
    @DisplayName("resolve(VerdictView) 与 resolve(status,verdict) 一致")
    void 视图入口一致() {
        VerdictView view = new VerdictView("completed", "PASS");
        assertEquals(CaseConclusionResolver.resolve("completed", "PASS"),
                CaseConclusionResolver.resolve(view));
        assertEquals(Conclusion.UNDETERMINED, CaseConclusionResolver.resolve((VerdictView) null));
    }
}
