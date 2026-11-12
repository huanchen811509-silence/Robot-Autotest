package com.qa.autotest.application.repository;

import java.util.Map;
import java.util.Optional;

/**
 * 项目级配置项仓储（`project_setting` KV 表）。
 *
 * <p>v5.1 起 pytest 工程的 10 个配置项（仓库地址、分支、解释器、env/inventory 路径等）
 * 全部落这张表，沿用既有 KV 结构，不新建工程配置表——避免「一个项目两套配置真源」。
 */
public interface ProjectSettingRepository {

    Optional<String> find(long projectId, String key);

    /** 按前缀取一批（如 `pytest.`），key 返回时去掉前缀。 */
    Map<String, String> findAll(long projectId, String prefix);

    /** 存在则更新、不存在则插入（唯一键 `(project_id, setting_key)`）。 */
    void put(long projectId, String key, String value, Long operatorId);

    void remove(long projectId, String key);
}
