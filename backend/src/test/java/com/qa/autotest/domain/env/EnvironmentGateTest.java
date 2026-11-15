package com.qa.autotest.domain.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** W65-15/16/17：声明才校验、409 点名四要素、无声明不拦截。 */
class EnvironmentGateTest {

    @Test
    void undeclaredPathAlwaysPassesButIsMarked() {
        EnvironmentGate.Verdict verdict = EnvironmentGate.evaluate(List.of(), Map.of(), 3L);
        assertTrue(verdict.pass());
        assertTrue(verdict.undeclared());
        assertEquals(3L, verdict.snapshotId());
    }

    @Test
    void minGbpsMismatchNamesKeyExpectedActualAndSnapshot() {
        var requirement = new EnvironmentGate.Requirement("ROCE", "MIN_GBPS", "400");
        var facts = Map.of("ROCE.maxGbps", "100", "ROCE", "[{\"rateGbps\":100}]");
        EnvironmentGate.Verdict verdict = EnvironmentGate.evaluate(List.of(requirement), facts, 7L);
        assertFalse(verdict.pass());
        assertEquals(1, verdict.failures().size());
        String described = verdict.failures().get(0).describe();
        assertTrue(described.contains("key=ROCE"));
        assertTrue(described.contains("expected=400"));
        assertTrue(described.contains("actual=100"));
        assertTrue(described.contains("snapshotId=7"));
    }

    @Test
    void matchingRatePasses() {
        var requirement = new EnvironmentGate.Requirement("ROCE", "MIN_GBPS", "400");
        var facts = Map.of("ROCE.maxGbps", "400");
        assertTrue(EnvironmentGate.evaluate(List.of(requirement), facts, 9L).pass());
    }

    @Test
    void missingFactIsRejectedNotGuessed() {
        var requirement = new EnvironmentGate.Requirement("GPU", "PRESENT", "");
        var facts = Map.of("GPU", "未采集到：nvidia-smi not found");
        assertFalse(EnvironmentGate.evaluate(List.of(requirement), facts, 5L).pass());
    }
}
