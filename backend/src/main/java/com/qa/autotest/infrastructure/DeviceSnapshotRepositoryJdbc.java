package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.DeviceSnapshotRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.env.DeviceSnapshot;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 设备快照仓储 JDBC 实现（v5.15.0）：所有 SQL 先过租户守卫。 */
@Repository
public class DeviceSnapshotRepositoryJdbc implements DeviceSnapshotRepository {

    private static final String SNAPSHOT_COLUMNS = "id, project_id, device_id, host, source, "
            + "snapshot_hash, spec_hash, source_record_id, collected_at, item_count, created_at";
    private static final String ITEM_COLUMNS = "id, project_id, snapshot_id, item_key, category, "
            + "command, value_json, value_hash, collected, reason, created_at";

    private static final RowMapper<DeviceSnapshot> SNAPSHOT = (rs, n) -> new DeviceSnapshot(
            rs.getLong("id"), rs.getLong("project_id"), nullableLong(rs, "device_id"),
            rs.getString("host"), rs.getString("source"), rs.getString("snapshot_hash"),
            rs.getString("spec_hash"), nullableLong(rs, "source_record_id"),
            instant(rs, "collected_at"), rs.getInt("item_count"), instant(rs, "created_at"));

    private static final RowMapper<DeviceSnapshotItem> ITEM = (rs, n) -> new DeviceSnapshotItem(
            rs.getLong("id"), rs.getLong("project_id"), rs.getLong("snapshot_id"),
            rs.getString("item_key"), rs.getString("category"), rs.getString("command"),
            rs.getString("value_json"), rs.getString("value_hash"), rs.getBoolean("collected"),
            rs.getString("reason"), instant(rs, "created_at"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private final JdbcTemplate jdbc;

    public DeviceSnapshotRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long upsertSnapshot(DeviceSnapshot snapshot, Long operatorId) {
        String find = "SELECT id FROM device_snapshot WHERE project_id = ? AND snapshot_hash = ?";
        TenantGuard.requireProjectScope(find, "device_snapshot");
        List<Long> existing = jdbc.query(find, (rs, n) -> rs.getLong("id"),
                snapshot.projectId(), snapshot.snapshotHash());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String insert = "INSERT INTO device_snapshot (project_id, device_id, host, source, "
                + "snapshot_hash, spec_hash, source_record_id, collected_at, item_count, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(insert, "device_snapshot");
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(insert,
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, snapshot.projectId());
                if (snapshot.deviceId() == null) {
                    ps.setNull(2, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(2, snapshot.deviceId());
                }
                ps.setString(3, snapshot.host());
                ps.setString(4, snapshot.source());
                ps.setString(5, snapshot.snapshotHash());
                ps.setString(6, snapshot.specHash());
                if (snapshot.sourceRecordId() == null) {
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(7, snapshot.sourceRecordId());
                }
                ps.setTimestamp(8, Timestamp.from(snapshot.collectedAt() == null
                        ? Instant.now() : snapshot.collectedAt()));
                ps.setInt(9, snapshot.itemCount());
                if (operatorId == null) {
                    ps.setNull(10, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(10, operatorId);
                }
                return ps;
            }, keys);
            return keys.getKey() == null ? 0L : keys.getKey().longValue();
        } catch (DuplicateKeyException race) {
            // 并发下同一份内容被另一个请求先插入：复用它，不覆盖、不报错。
            List<Long> won = jdbc.query(find, (rs, n) -> rs.getLong("id"),
                    snapshot.projectId(), snapshot.snapshotHash());
            if (won.isEmpty()) {
                throw race;
            }
            return won.get(0);
        }
    }

    @Override
    public void insertItems(long projectId, long snapshotId, List<DeviceSnapshotItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String sql = "INSERT IGNORE INTO device_snapshot_item (project_id, snapshot_id, item_key, "
                + "category, command, value_json, value_hash, collected, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "device_snapshot_item");
        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                DeviceSnapshotItem item = items.get(i);
                ps.setLong(1, projectId);
                ps.setLong(2, snapshotId);
                ps.setString(3, item.itemKey());
                ps.setString(4, item.category());
                ps.setString(5, item.command());
                ps.setString(6, item.valueJson());
                ps.setString(7, item.valueHash());
                ps.setBoolean(8, item.collected());
                ps.setString(9, item.reason());
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
    }

    @Override
    public Optional<DeviceSnapshot> find(long projectId, long id) {
        String sql = "SELECT " + SNAPSHOT_COLUMNS
                + " FROM device_snapshot WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "device_snapshot");
        return jdbc.query(sql, SNAPSHOT, projectId, id).stream().findFirst();
    }

    @Override
    public List<DeviceSnapshotItem> items(long projectId, long snapshotId, String key) {
        String base = "SELECT " + ITEM_COLUMNS
                + " FROM device_snapshot_item WHERE project_id = ? AND snapshot_id = ?";
        String sql = key == null || key.isBlank() ? base : base + " AND item_key = ?";
        TenantGuard.requireProjectScope(sql, "device_snapshot_item");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(snapshotId);
        if (key != null && !key.isBlank()) {
            args.add(key);
        }
        return jdbc.query(sql, ITEM::mapRow, args.toArray());
    }

    @Override
    public List<DeviceSnapshotItem> latestItems(long projectId, String key) {
        String base = "SELECT " + ITEM_COLUMNS + " FROM device_snapshot_item "
                + "WHERE project_id = ? AND snapshot_id = ("
                + "SELECT id FROM device_snapshot WHERE project_id = ? "
                + "ORDER BY collected_at DESC, id DESC LIMIT 1)";
        String sql = key == null || key.isBlank() ? base : base + " AND item_key = ?";
        TenantGuard.requireProjectScope(sql, "device_snapshot_item");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(projectId);
        if (key != null && !key.isBlank()) {
            args.add(key);
        }
        return jdbc.query(sql, ITEM::mapRow, args.toArray());
    }

    @Override
    public List<DeviceSnapshot> list(long projectId, int limit) {
        String sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM device_snapshot "
                + "WHERE project_id = ? ORDER BY collected_at DESC, id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "device_snapshot");
        return jdbc.query(sql, SNAPSHOT, projectId, limit <= 0 ? 20 : Math.min(limit, 200));
    }
}
