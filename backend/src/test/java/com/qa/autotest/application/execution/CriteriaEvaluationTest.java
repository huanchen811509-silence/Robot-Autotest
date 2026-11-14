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
}
