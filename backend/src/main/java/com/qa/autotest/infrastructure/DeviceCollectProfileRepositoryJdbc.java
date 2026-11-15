package com.qa.autotest.infrastructure;

import com.qa.autotest.application.asset.DeviceCollectProfile;
import com.qa.autotest.application.repository.DeviceCollectProfileRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 采集画像的查询期聚合（v5.13.0 / W63-02）。
 *
 * <p>零迁移：绑定列（V11_45）、验证事实（V11_64）、采集记录（device_host_spec）都是既有表；
 * 这里只用相关子查询把它们拼成一行。三个子查询都走各表已有的 device_id 索引，
 * 不在 SQL 里复制一份状态公式——公式只在 {@link DeviceCollectProfile#deriveState} 里写一次。
 *
 * <p>租户收口复用 {@link AssetScopeSql}：本项目私有设备，或平台共享且本项目已引用的设备。
 * 传进来的 projectId 由接口层校验，不允许用户拿别的项目 id 反查别人资产。
 */
@Repository
public class DeviceCollectProfileRepositoryJdbc implements DeviceCollectProfileRepository {

    /** 包可见：单测直接断言 SQL 形状（含租户条件、不含明文列）。 */
    static final String SELECT_SQL = """
            SELECT d.id AS device_id, d.project_id, d.owner_scope, d.name, d.management_ip,
                   d.management_credential_id, d.bmc_credential_id,
                   mc.name AS mgmt_cred_name, mc.asset_status AS mgmt_cred_status,
                   bc.name AS bmc_cred_name, bc.asset_status AS bmc_cred_status,
                   (SELECT cv.credential_id FROM credential_verification cv
                     WHERE cv.device_id = d.id
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS last_verified_credential_id,
                   (SELECT DATE_FORMAT(cv.verified_at, '%Y-%m-%d %H:%i:%s')
                      FROM credential_verification cv WHERE cv.device_id = d.id
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS last_verified_at,
                   (SELECT cv.result FROM credential_verification cv
                     WHERE cv.device_id = d.id
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS verify_result,
                   (SELECT cv.failure_kind FROM credential_verification cv
                     WHERE cv.device_id = d.id
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS verify_failure_kind,
                   (SELECT COUNT(*) FROM device_host_spec h WHERE h.device_id = d.id)
                     AS collect_count,
                   (SELECT DATE_FORMAT(h.collected_at, '%Y-%m-%d %H:%i:%s')
                      FROM device_host_spec h WHERE h.device_id = d.id
                     ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS last_collect_at,
                   (SELECT h.status FROM device_host_spec h WHERE h.device_id = d.id
                     ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS last_collect_status
              FROM device d
              LEFT JOIN credential mc ON mc.id = d.management_credential_id
              LEFT JOIN credential bc ON bc.id = d.bmc_credential_id
            """;

    static final String BASE_SQL = SELECT_SQL + AssetScopeSql.DEVICE_JOIN
            + AssetScopeSql.DEVICE_WHERE;

    /** 平台项目自身：公共资产恒可见，不靠「自己引用自己」。 */
    static final String PLATFORM_SQL = SELECT_SQL + AssetScopeSql.DEVICE_JOIN
            + AssetScopeSql.DEVICE_WHERE_PLATFORM;

    /** 包可见纯函数：可见性 SQL 只按 scope 选，单测可断言不复制第二份公式。 */
    static String sqlFor(long projectId, long platformId) {
        return projectId == platformId ? PLATFORM_SQL : BASE_SQL;
    }

    private final JdbcTemplate jdbc;

    public DeviceCollectProfileRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DeviceCollectProfile> list(long projectId, long platformId,
                                           Long deviceId, String state) {
        StringBuilder sql = new StringBuilder(sqlFor(projectId, platformId));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(projectId);
        if (deviceId != null) {
            sql.append(" AND d.id = ?");
            args.add(deviceId);
        }
        sql.append(" ORDER BY d.id DESC");
        String finalSql = sql.toString();
        TenantGuard.requireProjectScope(finalSql, "device");
        List<DeviceCollectProfile> rows = jdbc.query(finalSql,
                (rs, n) -> DeviceCollectProfile.of(rawOf(rs)), args.toArray());
        return filterByState(rows, state);
    }

    /** 包可见纯函数：派生状态过滤不复制 SQL 公式，单测直接打这个入口。 */
    static List<DeviceCollectProfile> filterByState(List<DeviceCollectProfile> rows, String state) {
        if (state == null || state.isBlank()) {
            return rows;
        }
        String filter = state.trim().toUpperCase(Locale.ROOT);
        return rows.stream().filter(row -> filter.equals(row.profileState())).toList();
    }

    private static DeviceCollectProfile.RawRow rawOf(ResultSet rs) throws SQLException {
        return new DeviceCollectProfile.RawRow(
                rs.getLong("device_id"),
                rs.getObject("project_id", Long.class),
                rs.getString("owner_scope"),
                rs.getString("name"),
                rs.getString("management_ip"),
                rs.getObject("management_credential_id", Long.class),
                rs.getObject("bmc_credential_id", Long.class),
                rs.getString("mgmt_cred_name"),
                rs.getString("bmc_cred_name"),
                rs.getString("mgmt_cred_status"),
                rs.getString("bmc_cred_status"),
                rs.getObject("last_verified_credential_id", Long.class),
                rs.getString("last_verified_at"),
                rs.getString("verify_result"),
                rs.getString("verify_failure_kind"),
                rs.getString("last_collect_at"),
                rs.getString("last_collect_status"),
                rs.getInt("collect_count"));
    }
}
