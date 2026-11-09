package com.qa.autotest.application.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.domain.ai.AiAnswer;
import com.qa.autotest.domain.ai.AiCitation;
import com.qa.autotest.domain.ai.AiEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 事实一致性守门：数字必须可追溯、引用必须存在、无证据必须拒答（EW33-08）。 */
class AiFactVerifierTest {

    private final AiFactVerifier verifier = new AiFactVerifier();

    private static AiEvidence evidence() {
        return new AiEvidence("RUN-1", List.of(
                new AiEvidence.Item("run", "RUN-1", "运行 RUN-1", "/runs/RUN-1",
                        "总数 100，通过 97，失败 3，通过率 97.0%")));
    }

    private static AiAnswer answer(String text, List<String> facts, boolean degraded) {
        return new AiAnswer("GROUNDED", text, facts, List.of(), List.of(),
                List.of(new AiCitation("E1", "run", "RUN-1", "运行 RUN-1", "/runs/RUN-1")),
                degraded);
    }

    @Test
    void passesWhenEveryNumberComesFromEvidence() {
        AiFactVerifier.Report report = verifier.verify(
                answer("事实：总数 100，失败 3，通过率 97.0%（[E1]）", List.of("总数 100"), true),
                evidence());
        assertTrue(report.pass(), report.toString());
        assertEquals(1, report.citationCount());
        assertTrue(report.unsupportedNumbers().isEmpty());
    }

    @Test
    void flagsNumberMissingFromEvidence() {
        AiFactVerifier.Report report = verifier.verify(
                answer("事实：失败 3，另有 7 个环境错误（[E1]）", List.of("失败 3"), true),
                evidence());
        assertFalse(report.pass());
        assertTrue(report.unsupportedNumbers().contains("7"), report.toString());
    }

    @Test
    void flagsDanglingCitation() {
        AiFactVerifier.Report report = verifier.verify(
                answer("事实：失败 3（[E9]）", List.of("失败 3"), true), evidence());
        assertFalse(report.pass());
        assertTrue(report.missingCitations().contains("E9"), report.toString());
    }

    @Test
    void requiresExplicitRefusalWhenNoEvidence() {
        AiFactVerifier.Report report = verifier.verify(
                answer("本次运行一切正常。", List.of(), true), AiEvidence.empty("RUN-X"));
        assertFalse(report.pass());
        assertTrue(report.missingCitations().contains("NO_EVIDENCE_DECLARATION"));

        AiFactVerifier.Report refused = verifier.verify(
                answer("没有可用证据，无法给出分诊结论。", List.of(), true), AiEvidence.empty("RUN-X"));
        assertTrue(refused.pass(), refused.toString());
    }
}
