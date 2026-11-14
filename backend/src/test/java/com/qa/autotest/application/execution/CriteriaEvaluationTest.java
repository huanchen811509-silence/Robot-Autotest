package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判据评估（W57-50 / TC-v5.7-257 ~ 260）。
 *
 * <p>核心口径：<b>没有明确达标证据就不许判 PASS</b>。这是白泽调研那条「status 是事实、
 * conclusion 是结论」的落地检查点——如果这组用例被改成「默认 PASS」，整条判据主题就失效。
 */
class CriteriaEvaluationTest {

    @Test
    @DisplayName("未声明判据：三列全 NULL，不判 PASS")
    void undeclaredWhenPayloadBlank() {
        CriteriaEvaluation.Result blank = CriteriaEvaluation.evaluate(null);
        assertThat(blank.declared()).isFalse();
        assertThat(blank.verdict()).isNull();
        assertThat(blank.json()).isNull();

        assertThat(CriteriaEvaluation.evaluate("   ").declared()).isFalse();
        // JSON 有但没有 criteria 数组 = 没声明判据，同样不能当 PASS。
        CriteriaEvaluation.Result noArray = CriteriaEvaluation.evaluate("{\"ok\":true}");
        assertThat(noArray.declared()).isFalse();
        assertThat(noArray.verdict()).isNull();
    }

    @Test
    @DisplayName("全部指标达标 → PASS，并保存快照 JSON")
    void passWhenAllMetricsPassed() {
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(
                "log line\n{\"criteria\":[{\"metric\":\"throughput\",\"op\":\">=\","
                        + "\"threshold\":\"90\",\"actual\":\"95\",\"passed\":true}]}\ntail");
        assertThat(result.declared()).isTrue();
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.missing()).isNull();
        assertThat(result.json()).contains("throughput").contains("95");
    }

    @Test
    @DisplayName("任一指标不达标 → FAIL")
    void failWhenAnyMetricFailed() {
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(
                "{\"criteria\":[{\"metric\":\"a\",\"actual\":\"1\",\"passed\":true},"
                        + "{\"metric\":\"b\",\"actual\":\"2\",\"passed\":false}]}");
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("缺实测值或未给结论 → MISSING，且点名缺哪个指标")
    void missingWhenMetricIncomplete() {
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(
                "{\"criteria\":[{\"metric\":\"latency\",\"threshold\":\"<=10\"},"
                        + "{\"metric\":\"ok\",\"actual\":\"1\",\"passed\":true}]}");
        assertThat(result.verdict()).isEqualTo("MISSING");
        assertThat(result.missing()).contains("latency");
    }

    @Test
    @DisplayName("文本里没有判据 JSON → MISSING 并写明原因，不静默当未声明")
    void missingWhenPayloadNotJson() {
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate("all good, no json here");
        assertThat(result.declared()).isFalse();
        assertThat(result.verdict()).isEqualTo("MISSING");
        assertThat(result.missing()).contains("采集异常");
    }

    @Test
    @DisplayName("超过 64KB 的快照截断并打标记")
    void truncateOversizedSnapshot() {
        StringBuilder builder = new StringBuilder("{\"criteria\":[");
        for (int i = 0; i < 2000; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"metric\":\"m").append(i)
                    .append("\",\"actual\":\"1\",\"passed\":true}");
        }
        builder.append("]}");
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(builder.toString());
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.json()).endsWith("[TRUNCATED]");
        assertThat(result.missing()).contains("64KB");
    }

    // ---- v5.8.0 / W58-13：哨兵行 CRITERIA_JSON ----

    @Test
    @DisplayName("哨兵行优先：日志里出现别的花括号也不影响解析")
    void sentinelWinsOverStrayBraces() {
        String stdout = """
                [明细] raw={"not":"criteria"}
                CRITERIA_JSON {"criteria":[{"metric":"speed","op":"==","threshold":"1000",
                "actual":"1000","passed":true}]}
                [明细] tail={"a":1}
                """;
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(stdout);
        assertThat(result.declared()).isTrue();
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.json()).contains("\"speed\"");
    }

    @Test
    @DisplayName("多次打印哨兵行时取最后一次（重跑/复测以最终事实为准）")
    void sentinelUsesLastOccurrence() {
        String stdout = """
                CRITERIA_JSON {"criteria":[{"metric":"m","actual":"1","passed":false}]}
                CRITERIA_JSON {"criteria":[{"metric":"m","actual":"1","passed":true}]}
                """;
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(stdout);
        assertThat(result.verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("哨兵后面没有 JSON 时退回旧口径，不因哨兵存在就判 MISSING")
    void sentinelWithoutJsonFallsBack() {
        String stdout = """
                {"criteria":[{"metric":"m","actual":"2","passed":true}]}
                CRITERIA_JSON (payload 丢失)
                """;
        CriteriaEvaluation.Result result = CriteriaEvaluation.evaluate(stdout);
        assertThat(result.declared()).isTrue();
        assertThat(result.verdict()).isEqualTo("PASS");
    }
}
