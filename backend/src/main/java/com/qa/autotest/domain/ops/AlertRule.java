package com.qa.autotest.domain.ops;

/** 告警规则（FR-3.3-09）。threshold 的含义随 eventType 变化：百分比 / 毫秒 / 分钟。 */
public record AlertRule(long id, long projectId, String eventType, double threshold,
                        int windowMinutes, int cooldownMinutes, String severity, boolean enabled) {
}
