package com.qa.autotest.application.repository;

import com.qa.autotest.domain.env.DeviceSnapshot;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import java.util.List;
import java.util.Optional;

/** 设备快照仓储（v5.15.0）：只追加，唯一键保证内容寻址幂等。 */
public interface DeviceSnapshotRepository {

    /** 同 hash 复用既有行，返回快照 id；不覆盖既有行、不重复插项。 */
    long upsertSnapshot(DeviceSnapshot snapshot, Long operatorId);

    /** INSERT IGNORE：已有 item_key 的行一律不动。 */
    void insertItems(long projectId, long snapshotId, List<DeviceSnapshotItem> items);

    Optional<DeviceSnapshot> find(long projectId, long id);

    List<DeviceSnapshotItem> items(long projectId, long snapshotId, String key);

    /** 最新一份快照里的按项事实；key 为空表示全部。 */
    List<DeviceSnapshotItem> latestItems(long projectId, String key);

    List<DeviceSnapshot> list(long projectId, int limit);
}
