package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.EnvProfileRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.asset.EnvProfileSummary;
import com.qa.autotest.domain.asset.EnvSnapshot;
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

/** 环境快照仓储实现。 */
@Repository
public class EnvProfileRepositoryJdbc implements EnvProfileRepository {

    private static final RowMapper<EnvSnapshot> MAPPER = (rs, rowNum) -> new EnvSnapshot(
            rs.getLong("id"), rs.getString("name"), rs.getString("profile_type"),
            rs.getString("description"), rs.getString("snapshot_json"),
            JdbcSupport.instant(rs.getTimestamp("snapshot_at")));

    private static final String SELECT = """
            SELECT id, name, profile_type, description, snapshot_json, snapshot_at
              FROM env_profile
            """;

    private final JdbcTemplate jdbc;

    public EnvProfileRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertSnapshot(long projectId, String name, String profileType,
                               String description, String snapshotJson, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO env_profile
                      (project_id, name, profile_type, description, snapshot_json, snapshot_at,
                       created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, name);
            ps.setString(3, profileType);
            ps.setString(4, description);
            ps.setString(5, snapshotJson);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setObject(7, operatorId, java.sql.Types.BIGINT);
            ps.setObject(8, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public List<EnvSnapshot> listSnapshots(long projectId, String name) {
        String sql = SELECT + " WHERE project_id = ? AND name = ? ORDER BY snapshot_at DESC";
        TenantGuard.requireProjectScope(sql, "env_profile");
        return jdbc.query(sql, MAPPER, projectId, name);
    }

    @Override
    public Optional<EnvSnapshot> latestSnapshot(long projectId, String name) {
        String sql = SELECT + " WHERE project_id = ? AND name = ? "
                + "ORDER BY snapshot_at DESC LIMIT 1";
        TenantGuard.requireProjectScope(sql, "env_profile");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, name));
    }

    @Override
    public List<EnvProfileSummary> listEnvironments(long projectId) {
        // profile_type 取「最近一次快照」那行的值：MAX(profile_type) 取的是字母序最大，
        // 不是最近。用相关子查询按时间/id 定序取首行；同时避免 JOIN 到同秒两条时出现重复环境名。
        String sql = """
                SELECT c.name,
                       (SELECT e.profile_type FROM env_profile e
                         WHERE e.project_id = c.project_id AND e.name = c.name
                         ORDER BY e.snapshot_at DESC, e.id DESC LIMIT 1) AS profile_type,
                       c.snapshot_count, c.latest_at
                  FROM (
                    SELECT project_id, name, COUNT(*) AS snapshot_count,
                           MAX(snapshot_at) AS latest_at
                      FROM env_profile
                     WHERE project_id = ?
                     GROUP BY project_id, name
                  ) c
                 ORDER BY c.latest_at DESC, c.name ASC
                """;
        TenantGuard.requireProjectScope(sql, "env_profile");
        return jdbc.query(sql, (rs, n) -> new EnvProfileSummary(
                rs.getString("name"),
                rs.getString("profile_type"),
                rs.getInt("snapshot_count"),
                JdbcSupport.instant(rs.getTimestamp("latest_at"))), projectId);
    }
}
