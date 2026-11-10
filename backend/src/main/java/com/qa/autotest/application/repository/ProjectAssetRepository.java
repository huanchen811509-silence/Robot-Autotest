package com.qa.autotest.application.repository;

import java.util.Map;
import java.util.Optional;

/**
 * 项目对平台资产的引用（AST-05）。
 *
 * <p>语义分工：资产本体的归属（{@code owner_scope} / {@code project_id}）在 device/link/credential；
 * 这张表只回答「哪个项目能看到哪条平台资产」。项目私有资产的可见性**不依赖**本表。
 */
public interface ProjectAssetRepository {

    /** 资产本体的归属快照。 */
    record AssetScope(long assetId, Long projectId, String ownerScope) {

        public boolean platform() {
            return "PLATFORM".equals(ownerScope);
        }
    }

    /** 查资产本体归属；不存在返回空。 */
    Optional<AssetScope> scopeOf(String assetType, long assetId);

    /** 私有资产转为平台共享：owner_scope=PLATFORM、project_id=NULL。 */
    void shareToPlatform(String assetType, long assetId, long ownerProjectId);

    /** 项目引用一条平台资产；已存在则只更新别名。 */
    void addReference(long projectId, String assetType, long assetId, String alias, Long operatorId);

    /** 只删引用，不动物资本体；返回是否真的删除了一行。 */
    boolean removeReference(long projectId, String assetType, long assetId);

    /** 该资产被多少个项目引用（删除保护提示用）。 */
    int referenceCount(String assetType, long assetId);

    /**
     * 本项目对某类资产设置的别名（{@code asset_id → alias}），只含非空别名。
     *
     * <p>v4.3.0：别名只用于输出组装，**不进缓存键**，避免 A 项目别名串到 B 项目（PRD K2）。
     */
    Map<Long, String> aliases(long projectId, String assetType);

    /**
     * 硬删前置校验（FR-v0.2-51）：平台共享资产一律不允许从项目路径硬删，
     * 被引用时返回 409 并带引用项目数；私有资产先清掉 V7_3 回填的自引用，
     * 避免删掉本体后 {@code project_asset} 留下幽灵行。
     */
    void prepareHardDelete(String assetType, long assetId);
}
