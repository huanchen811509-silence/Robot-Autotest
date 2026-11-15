package com.qa.autotest.domain.env;

import java.time.Instant;

/** 一次采集的快照头（v5.15.0 / FR-v5.15-01）：内容寻址、只增不改。 */
public record DeviceSnapshot(long id,
                             long projectId,
                             Long deviceId,
                             String host,
                             String source,
                             String snapshotHash,
                             String specHash,
                             Long sourceRecordId,
                             Instant collectedAt,
                             int itemCount,
                             Instant createdAt) {
}
