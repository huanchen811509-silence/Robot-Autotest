package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.LinkRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.asset.Link;
import com.qa.autotest.domain.common.PageResult;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 链路仓储实现：一次查询取全两端设备与端口名。 */
@Repository
public class LinkRepositoryJdbc implements LinkRepository {

    /** 读口径见 {@link AssetScopeSql}；引用别名用 lpa，pa 已被 A 端端口名占用。 */
    private static final String SELECT = """
            SELECT l.id, l.project_id, l.name, l.link_type, l.protocol, l.speed_gbps, l.mtu,
                   l.a_device_id, da.name AS a_device_name, l.a_port_id, pa.port_name AS a_port_name,
                   l.b_device_id, db.name AS b_device_name, l.b_port_id, pb.port_name AS b_port_name,
                   l.description, l.status
              FROM link l
              LEFT JOIN device da ON da.id = l.a_device_id
              LEFT JOIN device_port pa ON pa.id = l.a_port_id
              LEFT JOIN device db ON db.id = l.b_device_id
              LEFT JOIN device_port pb ON pb.id = l.b_port_id
            """;

    private static final RowMapper<Link> MAPPER = (rs, rowNum) -> new Link(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
            rs.getString("link_type"), rs.getString("protocol"),
            rs.getObject("speed_gbps") == null ? null : rs.getInt("speed_gbps"),
            rs.getObject("mtu") == null ? null : rs.getInt("mtu"),
            rs.getObject("a_device_id") == null ? null : rs.getLong("a_device_id"),
            rs.getString("a_device_name"),
            rs.getObject("a_port_id") == null ? null : rs.getLong("a_port_id"),
            rs.getString("a_port_name"),
            rs.getObject("b_device_id") == null ? null : rs.getLong("b_device_id"),
            rs.getString("b_device_name"),
            rs.getObject("b_port_id") == null ? null : rs.getLong("b_port_id"),
            rs.getString("b_port_name"),
            rs.getString("description"), rs.getInt("status") == 1);

    private final JdbcTemplate jdbc;

    public LinkRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<Link> page(long projectId, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(AssetScopeSql.LINK_WHERE);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (l.name LIKE ? OR da.name LIKE ? OR db.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String countSql = "SELECT COUNT(*) FROM link l LEFT JOIN device da ON da.id = l.a_device_id "
                + "LEFT JOIN device db ON db.id = l.b_device_id " + AssetScopeSql.LINK_JOIN + where;
        TenantGuard.requireProjectScope(countSql, "link");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<Link> rows = jdbc.query(
                SELECT + AssetScopeSql.LINK_JOIN + where + " ORDER BY l.id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public List<Link> listAll(long projectId) {
        String sql = SELECT + AssetScopeSql.LINK_JOIN + AssetScopeSql.LINK_WHERE + " ORDER BY l.id";
        TenantGuard.requireProjectScope(sql, "link");
        return jdbc.query(sql, MAPPER, projectId, projectId);
    }

    @Override
    public Optional<Link> find(long projectId, long id) {
        String sql = SELECT + AssetScopeSql.LINK_JOIN + AssetScopeSql.LINK_WHERE + " AND l.id = ?";
        TenantGuard.requireProjectScope(sql, "link");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, projectId, id));
    }

    @Override
    public long insert(Link link, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO link
                      (project_id, name, link_type, protocol, speed_gbps, mtu,
                       a_device_id, a_port_id, b_device_id, b_port_id, description, status,
                       created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, link.projectId());
            ps.setString(2, link.name());
            ps.setString(3, link.linkType());
            ps.setString(4, link.protocol());
            ps.setObject(5, link.speedGbps(), java.sql.Types.INTEGER);
            ps.setObject(6, link.mtu(), java.sql.Types.INTEGER);
            ps.setObject(7, link.aDeviceId(), java.sql.Types.BIGINT);
            ps.setObject(8, link.aPortId(), java.sql.Types.BIGINT);
            ps.setObject(9, link.bDeviceId(), java.sql.Types.BIGINT);
            ps.setObject(10, link.bPortId(), java.sql.Types.BIGINT);
            ps.setString(11, link.description());
            ps.setInt(12, link.enabled() ? 1 : 0);
            ps.setObject(13, operatorId, java.sql.Types.BIGINT);
            ps.setObject(14, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(Link link, Long operatorId) {
        String sql = """
                UPDATE link SET name = ?, link_type = ?, protocol = ?, speed_gbps = ?, mtu = ?,
                       a_device_id = ?, a_port_id = ?, b_device_id = ?, b_port_id = ?,
                       description = ?, status = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "link");
        jdbc.update(sql, link.name(), link.linkType(), link.protocol(), link.speedGbps(),
                link.mtu(), link.aDeviceId(), link.aPortId(), link.bDeviceId(), link.bPortId(),
                link.description(), link.enabled() ? 1 : 0, operatorId, link.projectId(), link.id());
    }

    @Override
    public void delete(long projectId, long id) {
        String sql = "DELETE FROM link WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "link");
        jdbc.update(sql, projectId, id);
    }
}
