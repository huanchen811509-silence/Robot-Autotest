package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 资产引用与归属的 JDBC 实现；表名由 {@link AssetScopeSql#tableOf} 映射，类型不拼进 SQL。 */
@Repository
public class ProjectAssetRepositoryJdbc implements ProjectAssetRepository {

    private final JdbcTemplate jdbc;

    public ProjectAssetRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AssetScope> scopeOf(String assetType, long assetId) {
        String table = AssetScopeSql.tableOf(assetType);
        String sql = "SELECT id, project_id, owner_scope FROM `" + table + "` WHERE id = ?";
        TenantGuard.requireProjectScope(sql, table);
        List<AssetScope> rows = jdbc.query(sql, (rs, rowNum) -> new AssetScope(
                rs.getLong("id"),
                rs.getObject("project_id") == null ? null : rs.getLong("project_id"),
                rs.getString("owner_scope")), assetId);
        return rows.stream().findFirst();
    }

    @Override
    public void shareToPlatform(String assetType, long assetId, long ownerProjectId) {
        String table = AssetScopeSql.tableOf(assetType);
        String sql = "UPDATE `" + table + "` SET owner_scope = 'PLATFORM', project_id = NULL "
                + "WHERE id = ? AND owner_scope = 'PROJECT' AND project_id = ?";
        TenantGuard.requireProjectScope(sql, table);
        jdbc.update(sql, assetId, ownerProjectId);
    }

    @Override
    public void addReference(long projectId, String assetType, long assetId, String alias,
                             Long operatorId) {
        String sql = """
                INSERT INTO project_asset
                  (project_id, asset_type, asset_id, alias, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE alias = VALUES(alias), updated_by = VALUES(updated_by)
                """;
        TenantGuard.requireProjectScope(sql, "project_asset");
        jdbc.update(sql, projectId, AssetScopeSql.normalize(assetType), assetId, alias,
                operatorId, operatorId);
    }

    @Override
    public boolean removeReference(long projectId, String assetType, long assetId) {
        String sql = """
                DELETE FROM project_asset
                 WHERE project_id = ? AND asset_type = ? AND asset_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "project_asset");
        return jdbc.update(sql, projectId, AssetScopeSql.normalize(assetType), assetId) > 0;
    }

    @Override
    public Map<Long, String> aliases(long projectId, String assetType) {
        String sql = """
                SELECT asset_id, alias FROM project_asset
                 WHERE project_id = ? AND asset_type = ? AND alias IS NOT NULL AND alias <> ''
                """;
        TenantGuard.requireProjectScope(sql, "project_asset");
        Map<Long, String> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getLong("asset_id"), rs.getString("alias"));
        }, projectId, AssetScopeSql.normalize(assetType));
        return out;
    }

    @Override
    public int referenceCount(String assetType, long assetId) {
        String sql = "SELECT COUNT(DISTINCT project_id) FROM project_asset "
                + "WHERE asset_type = ? AND asset_id = ? AND project_id IS NOT NULL";
        TenantGuard.requireProjectScope(sql, "project_asset");
        Integer count = jdbc.queryForObject(sql, Integer.class,
                AssetScopeSql.normalize(assetType), assetId);
        return count == null ? 0 : count;
    }

    @Override
    public void prepareHardDelete(String assetType, long assetId) {
        String type = AssetScopeSql.normalize(assetType);
        Optional<AssetScope> scope = scopeOf(type, assetId);
        if (scope.isEmpty()) {
            return; // 资产不存在，交给上层 find 抛 404
        }
        if (scope.get().platform()) {
            int refs = referenceCount(type, assetId);
            String hint = refs > 0
                    ? "平台共享资产被 " + refs + " 个项目引用，不可硬删；请先在各项目解除引用"
                    : "平台共享资产不可从项目路径硬删；请先转回私有资产";
            throw new BusinessException(ErrorCode.CONFLICT, hint);
        }
        // V7_3 给所有存量私有资产回填了「本项目引用自己」，删本体前必须清掉
        String sql = "DELETE FROM project_asset "
                + "WHERE asset_type = ? AND asset_id = ? AND project_id IS NOT NULL";
        TenantGuard.requireProjectScope(sql, "project_asset");
        jdbc.update(sql, type, assetId);
    }

    /** 大小写统一由仓储负责，服务层不用重复写 toUpperCase。 */
    static String normalize(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("资产类型不能为空");
        }
        return assetType.trim().toUpperCase(Locale.ROOT);
    }
}
