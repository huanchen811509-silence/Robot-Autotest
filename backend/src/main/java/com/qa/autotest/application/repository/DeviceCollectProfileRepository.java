package com.qa.autotest.application.repository;

import com.qa.autotest.application.asset.DeviceCollectProfile;
import java.util.List;

/** 采集画像只读聚合（v5.13.0 / FR-v5.13-01）。 */
public interface DeviceCollectProfileRepository {

    /**
     * @param projectId 租户收口用的项目 id（AssetScopeSql 的可见口径）
     * @param deviceId  可选，单台设备详情用
     * @param state     可选，派生状态过滤（在 Java 侧过滤，SQL 不复制一份状态公式）
     */
    List<DeviceCollectProfile> list(long projectId, Long deviceId, String state);
}
