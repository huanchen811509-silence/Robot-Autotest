package com.qa.autotest.application.repository;

import com.qa.autotest.domain.host.HostInventoryRecord;
import java.util.List;
import java.util.Optional;

/** 主机清单仓储（FR-v3.11-01）。只追加不更新：一次采集就是一个历史时点，改它等于毁证据。 */
public interface HostInventoryRepository {

    long insert(HostInventoryRecord record, Long operatorId);

    Optional<HostInventoryRecord> find(long projectId, long id);

    /**
     * 最近若干次采集，按时间倒序。
     *
     * @param deviceId 为空表示该项目下所有设备
     */
    List<HostInventoryRecord> list(long projectId, Long deviceId, int limit);
}
