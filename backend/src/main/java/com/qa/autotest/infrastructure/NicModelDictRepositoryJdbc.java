package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.NicModelDictRepository;
import com.qa.autotest.domain.asset.NicModelDict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 网卡型号字典仓储实现（`nic_model_dict`）。 */
@Repository
public class NicModelDictRepositoryJdbc implements NicModelDictRepository {

    private static final String COLUMNS = "id, vendor, model, display_name, form_factor, "
            + "vendor_id, device_id, capabilities_json, enabled, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public NicModelDictRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NicModelDict> list(boolean includeDisabled) {
        String sql = "SELECT " + COLUMNS + " FROM nic_model_dict "
                + (includeDisabled ? "" : "WHERE enabled = 1 ")
                + "ORDER BY vendor, model";
        return jdbc.query(sql, (rs, rowNum) -> map(rs));
    }

    @Override
    public Optional<NicModelDict> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM nic_model_dict WHERE id = ?",
                (rs, rowNum) -> map(rs), id).stream().findFirst();
    }

    @Override
    public long insert(NicModelDict row, Long operatorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO nic_model_dict
                      (vendor, model, display_name, form_factor, vendor_id, device_id,
                       capabilities_json, enabled, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.vendor());
            ps.setString(2, row.model());
            ps.setString(3, row.displayName());
            ps.setString(4, row.formFactor());
            ps.setString(5, row.vendorId());
            ps.setString(6, row.deviceId());
            ps.setString(7, row.capabilitiesJson());
            ps.setBoolean(8, row.enabled());
            if (operatorId == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, operatorId);
                ps.setLong(10, operatorId);
            }
            return ps;
        }, keys);
        Number id = keys.getKey();
        return id == null ? 0L : id.longValue();
    }

    @Override
    public void update(NicModelDict row, Long operatorId) {
        jdbc.update("""
                UPDATE nic_model_dict
                   SET vendor = ?, model = ?, display_name = ?, form_factor = ?,
                       vendor_id = ?, device_id = ?, capabilities_json = ?, enabled = ?,
                       updated_by = ?
                 WHERE id = ?
                """, row.vendor(), row.model(), row.displayName(), row.formFactor(),
                row.vendorId(), row.deviceId(), row.capabilitiesJson(), row.enabled(),
                operatorId, row.id());
    }

    @Override
    public void setEnabled(long id, boolean enabled, Long operatorId) {
        jdbc.update("UPDATE nic_model_dict SET enabled = ?, updated_by = ? WHERE id = ?",
                enabled, operatorId, id);
    }

    @Override
    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM nic_model_dict", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long countDevicesUsing(long id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE model_dict_id = ?", Long.class, id);
        return count == null ? 0L : count;
    }

    private static NicModelDict map(ResultSet rs) throws SQLException {
        java.sql.Timestamp created = rs.getTimestamp("created_at");
        java.sql.Timestamp updated = rs.getTimestamp("updated_at");
        return new NicModelDict(
                rs.getLong("id"),
                rs.getString("vendor"),
                rs.getString("model"),
                rs.getString("display_name"),
                rs.getString("form_factor"),
                rs.getString("vendor_id"),
                rs.getString("device_id"),
                rs.getString("capabilities_json"),
                rs.getBoolean("enabled"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant());
    }
}
