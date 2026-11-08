package com.qa.autotest.application.execution;

/** EVID-03：单轮结果，不可变 record；runId 为独立 RUN_ID，roundNo 仅新轮次使用。 */
public record RoundResult(String runId, int roundNo, String verdict) {
}
