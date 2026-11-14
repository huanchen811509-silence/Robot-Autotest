package com.qa.autotest.infrastructure;

import com.qa.autotest.application.evidence.EvidenceBundleStore;
import com.qa.autotest.application.evidence.EvidenceBundleStore.BundleRow;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 证据包指针表（V11_61）的 JDBC 仓储（v5.7.0 / W57-24、25）。
 *
 * <p>DB 这行只是「指针摘要 / 状态 / 快速对账」，<b>不是文件清单的唯一真源</b>——磁盘上的
 * pointer 文件才是。status 三态：building（临时目录写入中）/ ready（原子发布完成）/
 * failed（写入失败可重试）。所有写入参数绑定，审计四列由 SQL 内 NOW()/操作人补齐。
 */
@Repository
public class EvidenceBundleRepositoryJdbc implements EvidenceBundleStore {

    private final JdbcTemplate jdbc;

    public EvidenceBundleRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 开写：不存在则建 building 行，存在则重置回 building（可重试）。 */
    public void begin(String bundleKey, Long projectId) {
        Long actor = TenantContext.userId();
        jdbc.update("""
                INSERT INTO evidence_bundle
                    (project_id, bundle_key, pointer_sha, file_count, total_bytes, status,
                     created_by, updated_by)
                VALUES (?, ?, '', 0, 0, 'building', ?, ?)
                ON DUPLICATE KEY UPDATE status = 'building', updated_at = NOW(3), updated_by = ?
                """, projectId, bundleKey, actor, actor, actor);
    }

    public void markReady(String bundleKey, String pointerSha, int fileCount, long totalBytes) {
        jdbc.update("""
                UPDATE evidence_bundle SET pointer_sha = ?, file_count = ?, total_bytes = ?,
                       status = 'ready', updated_at = NOW(3), updated_by = ?
                 WHERE bundle_key = ?
                """, pointerSha, fileCount, totalBytes, TenantContext.userId(), bundleKey);
    }

    public void markFailed(String bundleKey) {
        jdbc.update("""
                UPDATE evidence_bundle SET status = 'failed', updated_at = NOW(3), updated_by = ?
                 WHERE bundle_key = ?
                """, TenantContext.userId(), bundleKey);
    }

    @Override
    public Optional<BundleRow> find(String bundleKey) {
        return jdbc.query("""
                SELECT bundle_key, pointer_sha, file_count, total_bytes, status
                  FROM evidence_bundle WHERE bundle_key = ?
                """, (rs, i) -> new BundleRow(rs.getString("bundle_key"), rs.getString("pointer_sha"),
                        rs.getInt("file_count"), rs.getLong("total_bytes"), rs.getString("status")),
                bundleKey).stream().findFirst();
    }

}
