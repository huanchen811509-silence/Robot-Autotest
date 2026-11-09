package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.HostInventoryRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/** 主机清单仓储 JDBC 实现（FR-v3.11-01）。 */
@Repository
public class HostInventoryRepositoryJdbc implements HostInventoryRepository {

    /**
     * 列表不取 `spec_json`：那是整份清单（10 类 + 每项命令），几十 KB 起，
     * 列表页只需要摘要列。要看详情走 {@link #find}。
     */
    private static final String SUMMARY_COLUMNS = "id, project_id, device_id, host, collected_at, "
            + "status, spec_hash, nic_count, roce_ports, roce_max_gbps";
    private static final String FULL_COLUMNS = SUMMARY_COLUMNS + ", spec_json";

    private static final RowMapper<HostInventoryRecord> SUMMARY = (rs, n) -> map(rs, false);
    private static final RowMapper<HostInventoryRecord> FULL = (rs, n) -> map(rs, true);

    private static HostInventoryRecord map(ResultSet rs, boolean withJson) throws SQLException {
        Timestamp collectedAt = rs.getTimestamp("collected_at");
        Object maxGbps = rs.getObject("roce_max_gbps");
        // 列表不取 spec_json 时给 null，而不是空串：空串会被前端当成「清单是空的」，
        // 而真相是「这一栏没查」。两者在界面上是完全不同的两句话。
        String json = withJson ? rs.getString("spec_json") : null;
        return new HostInventoryRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getObject("device_id") == null ? null : rs.getLong("device_id"),
                rs.getString("host"),
                collectedAt == null ? null : collectedAt.toInstant(),
                rs.getString("status"),
                json,
                rs.getString("spec_hash"),
                rs.getInt("nic_count"),
                rs.getInt("roce_ports"),
                maxGbps == null ? null : ((Number) maxGbps).intValue());
    }

    private final JdbcTemplate jdbc;

    public HostInventoryRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(HostInventoryRecord record, Long operatorId) {
        String sql = "INSERT INTO device_host_spec (project_id, device_id, host, collected_at, status, "
                + "spec_json, spec_hash, nic_count, roce_ports, roce_max_gbps, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        TenantGuard.requireProjectScope(sql, "device_host_spec");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.projectId());
            if (record.deviceId() == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, record.deviceId());
            }
            ps.setString(3, record.host());
            ps.setTimestamp(4, Timestamp.from(record.collectedAt() == null
                    ? Instant.now() : record.collectedAt()));
            ps.setString(5, record.status());
            ps.setString(6, record.specJson());
            ps.setString(7, record.specHash());
            ps.setInt(8, record.nicCount());
            ps.setInt(9, record.rocePorts());
            if (record.roceMaxGbps() == null) {
                ps.setNull(10, java.sql.Types.INTEGER);
            } else {
                ps.setInt(10, record.roceMaxGbps());
            }
            if (operatorId == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, operatorId);
            }
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    @Override
    public Optional<HostInventoryRecord> find(long projectId, long id) {
        String sql = "SELECT " + FULL_COLUMNS + " FROM device_host_spec WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "device_host_spec");
        return jdbc.query(sql, FULL, projectId, id).stream().findFirst();
    }

    @Override
    public List<HostInventoryRecord> list(long projectId, Long deviceId, int limit) {
        String base = "SELECT " + SUMMARY_COLUMNS + " FROM device_host_spec WHERE project_id = ?";
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        if (deviceId == null) {
            String sql = base + " ORDER BY collected_at DESC, id DESC LIMIT ?";
            TenantGuard.requireProjectScope(sql, "device_host_spec");
            return jdbc.query(sql, SUMMARY, projectId, safeLimit);
        }
        String sql = base + " AND device_id = ? ORDER BY collected_at DESC, id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "device_host_spec");
        return jdbc.query(sql, SUMMARY, projectId, deviceId, safeLimit);
    }
}
