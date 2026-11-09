package com.qa.autotest.domain.notification;

import java.time.Instant;

/** 站内通知收件箱条目（FR-3.1-08）；readAt 为空即未读。 */
public record NotificationItem(long id, Long projectId, String runKey, String type, String status,
                               String message, Instant readAt, Instant createdAt) {
}
