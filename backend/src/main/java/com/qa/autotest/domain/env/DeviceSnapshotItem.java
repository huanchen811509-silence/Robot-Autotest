package com.qa.autotest.domain.env;

import java.time.Instant;

/**
 * 快照里的一项（v5.15.0 / FR-v5.15-01）。
 *
 * <p>拿不到时 valueJson 必须是 null 且 reason 非空；不许写 "0" / "未知" / "-" 冒充结果。
 * 这条与 HostInventoryItem 的判据③同源，不另立一套口径。
 */
public record DeviceSnapshotItem(long id,
                                 long projectId,
                                 long snapshotId,
                                 String itemKey,
                                 String category,
                                 String command,
                                 String valueJson,
                                 String valueHash,
                                 boolean collected,
                                 String reason,
                                 Instant createdAt) {
}
