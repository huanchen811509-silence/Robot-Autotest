package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.asset.Credential;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 凭据仓储实现：列表只返回「是否已配置」，密文只在 {@link #cipherOf} 中取。 */
@Repository
public class CredentialRepositoryJdbc implements CredentialRepository {

    /** 读口径见 {@link AssetScopeSql}：本项目私有 或 平台共享且已引用。 */
    private static final String SELECT = """
            SELECT c.id, c.project_id, c.name, c.cred_type, c.username,
                   CASE WHEN c.cipher_blob IS NULL OR c.cipher_blob = '' THEN 0 ELSE 1 END AS configured,
                   c.key_version, c.last_rotated_at, c.connect_options
              FROM credential c
            """;

    private static final RowMapper<Credential> MAPPER = (rs, rowNum) -> new Credential(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
            rs.getString("cred_type"), rs.getString("username"), rs.getInt("configured") == 1,
            rs.getInt("key_version"), JdbcSupport.instant(rs.getTimestamp("last_rotated_at")),
            rs.getString("connect_options"));

    private final JdbcTemplate jdbc;

    public CredentialRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Credential> list(long projectId) {
        String sql = SELECT + AssetScopeSql.CREDENTIAL_JOIN + AssetScopeSql.CREDENTIAL_WHERE
                + " ORDER BY c.id DESC";
        TenantGuard.requireProjectScope(sql, "credential");
        return jdbc.query(sql, MAPPER, projectId, projectId);
    }

    @Override
    public Optional<Credential> find(long projectId, long id) {
        String sql = SELECT + AssetScopeSql.CREDENTIAL_JOIN + AssetScopeSql.CREDENTIAL_WHERE
                + " AND c.id = ?";
        TenantGuard.requireProjectScope(sql, "credential");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, projectId, id));
    }

    @Override
    public Optional<String> cipherOf(long projectId, long id) {
        String sql = "SELECT c.cipher_blob FROM credential c " + AssetScopeSql.CREDENTIAL_JOIN
                + AssetScopeSql.CREDENTIAL_WHERE + " AND c.id = ?";
        TenantGuard.requireProjectScope(sql, "credential");
        List<String> rows = jdbc.query(sql, (rs, rowNum) -> rs.getString(1), projectId, projectId, id);
        return JdbcSupport.first(rows).filter(value -> value != null && !value.isBlank());
    }

    @Override
    public long insert(long projectId, String name, String credType, String username,
                       String cipherBlob, int keyVersion, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO credential
                      (project_id, name, cred_type, username, secret_ref, cipher_blob, key_version,
                       created_by, updated_by)
                    VALUES (?, ?, ?, ?, 'encrypted', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, name);
            ps.setString(3, credType);
            ps.setString(4, username);
            ps.setString(5, cipherBlob);
            ps.setInt(6, keyVersion);
            ps.setObject(7, operatorId, java.sql.Types.BIGINT);
            ps.setObject(8, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(long projectId, long id, String name, String username, String cipherBlob,
                       Long operatorId) {
        String sql = """
                UPDATE credential SET name = ?, username = ?, cipher_blob = ?,
                       last_rotated_at = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "credential");
        jdbc.update(sql, name, username, cipherBlob,
                cipherBlob == null ? null : Timestamp.from(Instant.now()), operatorId,
                projectId, id);
    }

    @Override
    public void delete(long projectId, long id) {
        String sql = "DELETE FROM credential WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "credential");
        jdbc.update(sql, projectId, id);
    }

    @Override
    public boolean nameExists(long projectId, String name, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM credential WHERE project_id = ? AND name = ? AND id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "credential");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, name,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }
}
