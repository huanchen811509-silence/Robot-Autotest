package com.qa.autotest.domain.ops;

import java.time.Instant;

/** 发布记录（FR-3.3-11）：版本戳 + 健康校验时间 + 回滚时间。 */
public record ReleaseRecord(long id, String version, String status, Instant deployedAt,
                            Instant verifiedAt, Instant rolledBackAt, String note) {
}
