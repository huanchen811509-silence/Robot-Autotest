package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.Optional;

/** 批量撤销凭据仓储（FR-3.1-04）。 */
public interface BatchUndoRepository {

    record UndoRecord(long id, long projectId, String token, String action, String targetType,
                      String payload, int itemCount, Instant expiresAt, Instant consumedAt,
                      Instant createdAt) {
    }

    long insert(long projectId, String token, String action, String targetType, String payload,
                int itemCount, Instant expiresAt, Long operatorId);

    Optional<UndoRecord> find(long projectId, String token);

    /** 原子消费：只有未消费且未过期才能成功，返回 false 表示已消费/已过期。 */
    boolean consume(long projectId, long id, Instant now);
}
