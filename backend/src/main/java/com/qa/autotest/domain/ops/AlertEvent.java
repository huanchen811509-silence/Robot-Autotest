package com.qa.autotest.domain.ops;

import java.time.Instant;

/** 告警事件（FR-3.3-09）：FIRING / RESOLVED，投递状态与尝试次数独立于业务状态。 */
public record AlertEvent(long id, long projectId, Long ruleId, String eventType, String dedupKey,
                         String severity, String status, String summary, Instant detectedAt,
                         Instant firedAt, Instant resolvedAt, String deliveryStatus,
                         int attempts, String lastError) {
}
