package com.qa.autotest.domain.caseasset;

import java.time.Instant;

/**
 * 批量操作前的单条快照（FR-3.1-04）。
 *
 * <p>reviewedAt 用字符串存：payload 落 MySQL JSON 列，字符串形式不依赖 Jackson 的
 * JavaTime 模块配置，读回时再解析，避免测试环境与运行时行为不一致。
 */
public record ReviewSnapshot(long id, String reviewStatus, String priority, String domainTag,
                             String safetyLevel, String reviewComment, Long reviewedBy,
                             String reviewedAt) {

    public Instant reviewedInstant() {
        return reviewedAt == null || reviewedAt.isBlank() ? null : Instant.parse(reviewedAt);
    }
}
