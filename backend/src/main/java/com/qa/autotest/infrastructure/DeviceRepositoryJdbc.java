package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.common.PageResult;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 设备与端口仓储实现；每条 SQL 执行前过 {@link TenantGuard}。 */
@Repository
public class DeviceRepositoryJdbc implements DeviceRepository {

    /** 读口径见 {@link AssetScopeSql}：私有 OR 平台共享且本项目已引用。 */
    private static final String SELECT = """
            SELECT d.id, d.project_id, d.name, d.device_type, d.model, d.vendor, d.serial_no,
                   d.management_ip, d.location, d.rack_no, d.domain_tag, d.status, d.owner_id,
                   d.last_checked_at, d.host_key, d.os_family, d.os_version, d.architecture,
                   d.ssh_port
              FROM device d
            """;

    /**
     * 端口读列（v5.1.0 / W51-03）。抽成常量是为了让三处端口查询
     * （ports / portsOfVisibleDevices / findPort）共用一份列清单——
     * 之前三处各自内联，加字段时漏一处就会在某个入口返回 null，排查成本极高。
     *
     * <p>`pci_device_id` 是 inventory 的 PCI Device ID（V11_46 里刻意带 pci_ 前缀），
     * 与第 2 列的 `device_id`（所属设备外键）是两个完全不同的东西。
     */
    private static final String PORT_COLUMNS = """
            dp.id, dp.device_id, dp.port_name, dp.port_type, dp.protocol, dp.mac_address,
                   dp.speed_gbps, dp.mtu, dp.ip_address, dp.port_status, dp.nic_name,
                   dp.nic_index, dp.nic_vendor, dp.nic_manufacturer, dp.nic_model,
                   dp.nic_form_factor, dp.pci_bdf, dp.vendor_id,
                   dp.pci_device_id, dp.driver, dp.firmware, dp.rdma_device,
                   dp.nic_port, dp.numa_node, dp.nic_role, dp.capabilities_json,
                   dp.counter_patterns_json
            """;

    private static final RowMapper<Device> MAPPER = (rs, rowNum) -> new Device(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
            rs.getString("device_type"), rs.getString("model"), rs.getString("vendor"),
            rs.getString("serial_no"), rs.getString("management_ip"), rs.getString("location"),
            rs.getString("rack_no"), rs.getString("domain_tag"), rs.getInt("status") == 1,
            rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
            JdbcSupport.instant(rs.getTimestamp("last_checked_at")),
            rs.getString("host_key"), rs.getString("os_family"), rs.getString("os_version"),
            rs.getString("architecture"),
            rs.getObject("ssh_port") == null ? null : rs.getInt("ssh_port"));

    private static final RowMapper<DevicePort> PORT_MAPPER = (rs, rowNum) -> new DevicePort(
            rs.getLong("id"), rs.getLong("device_id"), rs.getString("port_name"),
            rs.getString("port_type"), rs.getString("protocol"), rs.getString("mac_address"),
            rs.getObject("speed_gbps") == null ? null : rs.getInt("speed_gbps"),
            rs.getObject("mtu") == null ? null : rs.getInt("mtu"),
            rs.getString("ip_address"), rs.getString("port_status"),
            rs.getString("nic_name"),
            rs.getObject("nic_index") == null ? null : rs.getInt("nic_index"),
            rs.getString("nic_vendor"), rs.getString("nic_manufacturer"),
            rs.getString("nic_model"), rs.getString("nic_form_factor"), rs.getString("pci_bdf"),
            rs.getString("vendor_id"), rs.getString("pci_device_id"), rs.getString("driver"),
            rs.getString("firmware"), rs.getString("rdma_device"),
            rs.getObject("nic_port") == null ? null : rs.getInt("nic_port"),
            rs.getObject("numa_node") == null ? null : rs.getInt("numa_node"),
            rs.getString("nic_role"), rs.getString("capabilities_json"),
            rs.getString("counter_patterns_json"));

    private final JdbcTemplate jdbc;

    public DeviceRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<Device> page(long projectId, String keyword, Integer status, String domain,
                                   int page, int size) {
        StringBuilder where = new StringBuilder(AssetScopeSql.DEVICE_WHERE);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (d.name LIKE ? OR d.management_ip LIKE ? OR d.serial_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null) {
            where.append(" AND d.status = ?");
            args.add(status);
        }
        if (domain != null && !domain.isBlank()) {
            where.append(" AND d.domain_tag = ?");
            args.add(domain);
        }
        String countSql = "SELECT COUNT(*) FROM device d " + AssetScopeSql.DEVICE_JOIN + where;
        TenantGuard.requireProjectScope(countSql, "device");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        String listSql = SELECT + AssetScopeSql.DEVICE_JOIN + where
                + " ORDER BY d.id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "device");
        List<Device> rows = jdbc.query(listSql, MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public Optional<Device> find(long projectId, long id) {
        String sql = SELECT + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE + " AND d.id = ?";
        TenantGuard.requireProjectScope(sql, "device");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, projectId, id));
    }

    @Override
    public long insert(Device device, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO device
                      (project_id, name, device_type, model, vendor, serial_no, management_ip,
                       location, rack_no, domain_tag, status, owner_id, created_by, updated_by,
                       host_key, os_family, os_version, architecture, ssh_port)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, device.projectId());
            ps.setString(2, device.name());
            ps.setString(3, device.deviceType());
            ps.setString(4, device.model());
            ps.setString(5, device.vendor());
            ps.setString(6, device.serialNo());
            ps.setString(7, device.managementIp());
            ps.setString(8, device.location());
            ps.setString(9, device.rackNo());
            ps.setString(10, device.domainTag());
            ps.setInt(11, device.enabled() ? 1 : 0);
            ps.setObject(12, device.ownerId(), java.sql.Types.BIGINT);
            ps.setObject(13, operatorId, java.sql.Types.BIGINT);
            ps.setObject(14, operatorId, java.sql.Types.BIGINT);
            ps.setString(15, device.hostKey());
            ps.setString(16, device.osFamily());
            ps.setString(17, device.osVersion());
            ps.setString(18, device.architecture());
            ps.setObject(19, device.sshPort(), java.sql.Types.INTEGER);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(Device device, Long operatorId) {
        String sql = """
                UPDATE device SET name = ?, device_type = ?, model = ?, vendor = ?, serial_no = ?,
                       management_ip = ?, location = ?, rack_no = ?, domain_tag = ?, status = ?,
                       owner_id = ?, updated_by = ?, host_key = ?, os_family = ?, os_version = ?,
                       architecture = ?, ssh_port = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "device");
        jdbc.update(sql, device.name(), device.deviceType(), device.model(), device.vendor(),
                device.serialNo(), device.managementIp(), device.location(), device.rackNo(),
                device.domainTag(), device.enabled() ? 1 : 0, device.ownerId(), operatorId,
                device.hostKey(), device.osFamily(), device.osVersion(), device.architecture(),
                device.sshPort(), device.projectId(), device.id());
    }

    @Override
    public void delete(long projectId, long id) {
        String delPorts = "DELETE FROM device_port WHERE device_id = ? "
                + "AND device_id IN (SELECT id FROM device WHERE project_id = ? AND id = ?)";
        TenantGuard.requireProjectScope(delPorts, "device_port");
        jdbc.update(delPorts, id, projectId, id);
        String delDevice = "DELETE FROM device WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(delDevice, "device");
        jdbc.update(delDevice, projectId, id);
    }

    @Override
    public boolean serialExists(long projectId, String serialNo, Long excludeId) {
        if (serialNo == null || serialNo.isBlank()) {
            return false;
        }
        String sql = """
                SELECT COUNT(*) FROM device WHERE project_id = ? AND serial_no = ? AND id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "device");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, serialNo,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    @Override
    public List<DevicePort> ports(long projectId, long deviceId) {
        String sql = "SELECT " + PORT_COLUMNS + """
                  FROM device_port dp JOIN device d ON d.id = dp.device_id
                """ + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE
                + " AND dp.device_id = ? ORDER BY dp.id";
        TenantGuard.requireProjectScope(sql, "device_port");
        return jdbc.query(sql, PORT_MAPPER, projectId, projectId, deviceId);
    }

    @Override
    public List<DevicePort> portsOfVisibleDevices(long projectId) {
        String sql = "SELECT " + PORT_COLUMNS + """
                  FROM device_port dp JOIN device d ON d.id = dp.device_id
                """ + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE
                + " ORDER BY dp.device_id, dp.nic_index, dp.nic_name, dp.id";
        TenantGuard.requireProjectScope(sql, "device_port");
        return jdbc.query(sql, PORT_MAPPER, projectId, projectId);
    }

    @Override
    public List<Device> listAll(long projectId) {
        String sql = SELECT + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE
                + " ORDER BY d.id";
        TenantGuard.requireProjectScope(sql, "device");
        return jdbc.query(sql, MAPPER, projectId, projectId);
    }

    @Override
    public Optional<DevicePort> findPort(long projectId, long portId) {
        String sql = "SELECT " + PORT_COLUMNS + """
                  FROM device_port dp JOIN device d ON d.id = dp.device_id
                """ + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE + " AND dp.id = ?";
        TenantGuard.requireProjectScope(sql, "device_port");
        return JdbcSupport.first(jdbc.query(sql, PORT_MAPPER, projectId, projectId, portId));
    }

    @Override
    public long insertPort(long projectId, DevicePort port, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO device_port
                      (device_id, port_name, port_type, protocol, mac_address, speed_gbps, mtu,
                       ip_address, port_status, nic_name, nic_index, created_by, updated_by,
                       nic_vendor, nic_manufacturer, nic_model, nic_form_factor, pci_bdf,
                       vendor_id, pci_device_id, driver, firmware, rdma_device, nic_port,
                       numa_node, nic_role, capabilities_json, counter_patterns_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, port.deviceId());
            ps.setString(2, port.portName());
            ps.setString(3, port.portType());
            ps.setString(4, port.protocol());
            ps.setString(5, port.macAddress());
            ps.setObject(6, port.speedGbps(), java.sql.Types.INTEGER);
            ps.setObject(7, port.mtu(), java.sql.Types.INTEGER);
            ps.setString(8, port.ipAddress());
            ps.setString(9, port.portStatus());
            ps.setString(10, port.nicName());
            ps.setObject(11, port.nicIndex(), java.sql.Types.INTEGER);
            ps.setObject(12, operatorId, java.sql.Types.BIGINT);
            ps.setObject(13, operatorId, java.sql.Types.BIGINT);
            ps.setString(14, port.nicVendor());
            ps.setString(15, port.nicManufacturer());
            ps.setString(16, port.nicModel());
            ps.setString(17, port.nicFormFactor());
            ps.setString(18, port.pciBdf());
            ps.setString(19, port.vendorId());
            // 第 20 列是 PCI Device ID，与第 1 列的「所属设备外键」同名不同义。
            ps.setString(20, port.pciDeviceId());
            ps.setString(21, port.driver());
            ps.setString(22, port.firmware());
            ps.setString(23, port.rdmaDevice());
            ps.setObject(24, port.nicPort(), java.sql.Types.INTEGER);
            ps.setObject(25, port.numaNode(), java.sql.Types.INTEGER);
            ps.setString(26, port.nicRole());
            ps.setString(27, port.capabilitiesJson());
            ps.setString(28, port.counterPatternsJson());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void updatePort(long projectId, DevicePort port, Long operatorId) {
        String sql = """
                UPDATE device_port SET port_name = ?, port_type = ?, protocol = ?, mac_address = ?,
                       speed_gbps = ?, mtu = ?, ip_address = ?, port_status = ?, nic_name = ?,
                       nic_index = ?, updated_by = ?, nic_vendor = ?, nic_manufacturer = ?,
                       nic_model = ?, nic_form_factor = ?, pci_bdf = ?, vendor_id = ?,
                       pci_device_id = ?, driver = ?, firmware = ?, rdma_device = ?, nic_port = ?,
                       numa_node = ?, nic_role = ?, capabilities_json = ?,
                       counter_patterns_json = ?
                 WHERE id = ? AND device_id IN (SELECT id FROM device WHERE project_id = ?)
                """;
        TenantGuard.requireProjectScope(sql, "device_port");
        jdbc.update(sql, port.portName(), port.portType(), port.protocol(), port.macAddress(),
                port.speedGbps(), port.mtu(), port.ipAddress(), port.portStatus(), port.nicName(),
                port.nicIndex(), operatorId, port.nicVendor(), port.nicManufacturer(),
                port.nicModel(), port.nicFormFactor(), port.pciBdf(), port.vendorId(),
                port.pciDeviceId(), port.driver(), port.firmware(), port.rdmaDevice(),
                port.nicPort(), port.numaNode(), port.nicRole(), port.capabilitiesJson(),
                port.counterPatternsJson(), port.id(), projectId);
    }

    @Override
    public void deletePort(long projectId, long portId) {
        String sql = """
                DELETE FROM device_port WHERE id = ?
                  AND device_id IN (SELECT id FROM device WHERE project_id = ?)
                """;
        TenantGuard.requireProjectScope(sql, "device_port");
        jdbc.update(sql, portId, projectId);
    }

    @Override
    public boolean portNameExists(long projectId, long deviceId, String portName, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM device_port dp JOIN device d ON d.id = dp.device_id
                 WHERE d.project_id = ? AND dp.device_id = ? AND dp.port_name = ? AND dp.id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "device_port");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, deviceId, portName,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    @Override
    public List<String> domains(long projectId) {
        String sql = """
                SELECT DISTINCT d.domain_tag FROM device d
                """ + AssetScopeSql.DEVICE_JOIN + AssetScopeSql.DEVICE_WHERE
                + " AND d.domain_tag IS NOT NULL ORDER BY d.domain_tag";
        TenantGuard.requireProjectScope(sql, "device");
        return jdbc.query(sql, (rs, rowNum) -> rs.getString(1), projectId, projectId);
    }
}
