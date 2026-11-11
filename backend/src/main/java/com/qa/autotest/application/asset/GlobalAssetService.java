package com.qa.autotest.application.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostAccess;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公共资产库（v4.7.0 / FR-v4.7-01..09、v4.7.1 / FR-v4.7-11..13、v4.9.3 采集与状态补充）。
 *
 * <p>公共资产 = {@code owner_scope='PLATFORM'}（project_id 为空），复用 device / device_port / credential。
 * 导入按业务键 upsert：同名（或同 external_id）只更新不新增；源文件已无的资产软失效
 * （{@code asset_status='deprecated'}），不物理删除，避免项目引用悬空。
 * 删除保护由 {@link ProjectAssetRepository#prepareHardDelete} 提供 409。
 *
 * <p>「校验和」指整份工作簿的 SHA-256：同一文件重复提交直接回放上一次批次，不再写库；
 * 「采集」指把导入的端口事实（状态/速率/MAC/MTU/IP）落库，并把主机清单采集记录挂到资产上；
 * 「补充状态」指用表格里的状态列回填端口 up/down 与设备生命周期状态，缺省不猜。
 */
@Service
public class GlobalAssetService {

    /**
     * 批量采集里「单台抛异常」必须留堆栈。
     *
     * <p>实测教训（v4.12.0 W-D）：这里最初只把 `ex.getMessage()` 塞进 reason，于是现场看到的是
     * 17 台全 `NullPointerException`，堆栈一个字都没有，只能回去翻 DEBUG 日志才定位到是
     * `Optional.of(null)`。reason 给用户看，日志给排障看，两者不能合并成一样东西。
     */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GlobalAssetService.class);

    /**
     * 资产列表行。
     *
     * <p>v4.12.0 加三个字段不是为了显示，而是为了让页面**在点击前**判断得出来：
     * <ul>
     *   <li>{@code managementCredentialId} / {@code bmcCredentialId}：两个地址面各自的绑定。
     *       都没绑就意味着「点了必然 400」——按 FR-v4.12-10 这类设备的「采集」按钮不渲染；</li>
     *   <li>{@code collectChannel}：这条资产会走哪条采集通道（ssh / network / bmc）。
     *       凭据行填自己的类型推导结果，设备行留空（设备走哪条通道取决于采集时选哪条凭据）。</li>
     * </ul>
     */
    public record AssetRow(long id, String assetType, String name, String detail, String managementIp,
                           String status, String source, String externalId, String environment,
                           int referenceCount, int portCount, int collectCount,
                           String lastCollectedAt, String collectStatus,
                           Long managementCredentialId, Long bmcCredentialId,
                           String collectChannel) {
    }

    public record AssetPreview(long batchId, String sha256, int devices, int ports, int credentials,
                               int total, List<String> samples, List<String> warnings,
                               int sheetsScanned, boolean replayed) {
    }

    public record AssetImportResult(long batchId, String sha256, int devices, int ports,
                                    int credentials, int deprecations, int statusSupplemented,
                                    List<String> warnings, boolean replayed) {
    }

    public record CollectResult(long deviceId, String host, String status, String collectedAt,
                                int nicCount, int rocePorts, Integer roceMaxGbps) {
    }

    /**
     * 一条「凭据 → 设备」的绑定关系（v4.12.0 / FR-v4.12-11）。
     *
     * <p>{@code plane} 是**地址面**而不是通道：同一台机器可能既有业务管理地址（{@code mgmt}）
     * 又有带外地址（{@code bmc}），两条地址各绑各的凭据。按 (设备, 地址面) 展开成行，
     * 而不是按设备去重——否则「全部采集」时 BMC 面会被业务面的凭据带着去连，必然连错端口。
     *
     * <p>{@code channel} 由凭据类型推导（{@link HostAccess#channelOf}），供前端在**点击前**
     * 决定按钮文案与是否渲染（FR-v4.12-10）。
     */
    public record CredentialDevice(long deviceId, String name, String plane, String address,
                                   String channel, String lastStatus, String lastCollectedAt) {
    }

    /** 手工录入设备（FR-v4.12-13）；两个地址至少填一个，两个凭据 ID 必须指向平台凭据。 */
    public record CreateDeviceRequest(String name, String deviceType, String managementIp,
                                      String managementIpAlt, String bmcAddress,
                                      String bmcAddressAlt, Long managementCredentialId,
                                      Long bmcCredentialId, String model, String vendor,
                                      String serialNo, String location, String domainTag) {
    }

    /** 手工录入凭据（FR-v4.12-14）；{@code secret} 只进不出，响应里不会有它。 */
    public record CreateCredentialRequest(String name, String credType, String username,
                                          String secret, String connectOptions) {
    }

    /** 凭据的对外视图：显式列出字段，而不是把实体原样返回，避免将来加列把密文带出去。 */
    public record CredentialView(long id, String name, String credType, String username,
                                 String connectOptions, boolean configured) {
    }

    /** 手工录入后的设备视图：只回显地址与绑定关系，不回显任何凭据内容。 */
    public record DeviceView(long id, String name, String deviceType, String managementIp,
                             String managementIpAlt, String bmcAddress, String bmcAddressAlt,
                             Long managementCredentialId, Long bmcCredentialId, String assetSource) {
    }

    /** 批量采集单台结果；{@code status} 取 host_inventory 的 OK/PARTIAL/FAILED。 */
    public record BatchCollectItem(long deviceId, String name, String plane, String host,
                                   String status, String reason) {
    }

    /**
     * 批量采集汇总。
     *
     * <p>恒有 {@code total == ok + failed}：没有地址的、抛异常的、状态是 FAILED 的全部计入
     * {@code failed}，不存在「既不算成功也不算失败」的第三条暗河——前端摘要要能对上总数。
     */
    public record BatchCollectResult(int total, int ok, int failed, List<BatchCollectItem> items) {
    }

    /** 设备类型枚举值（FR-v4.12-16 的统一口径）；录入端点按它校验，导入老数据不追溯。 */
    private static final Set<String> DEVICE_TYPES = Set.of("gpu", "server", "storage", "switch");

    /** 凭据类型合法值（FR-v4.12-06）；{@code network} 为本版新增。 */
    private static final Set<String> CREDENTIAL_TYPES =
            Set.of("ssh", "network", "bmc", "snmp", "token");

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;
    private final ProjectAssetRepository projectAssets;
    private final PlatformProjectService platform;
    private final HostInventoryService hostInventory;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;

    public GlobalAssetService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                              ProjectAssetRepository projectAssets, PlatformProjectService platform,
                              HostInventoryService hostInventory, SecretCipher cipher,
                              ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.projectAssets = projectAssets;
        this.platform = platform;
        this.hostInventory = hostInventory;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    private void requireWrite() {
        access.requireGlobalEnter();
    }

    private void requireAdmin() {
        access.requireGlobalEnter();
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "公共资产删除仅管理员可执行");
        }
    }

    // ---------------------------------------------------------------- 列表

    public List<AssetRow> list(String type, String keyword, String availability) {
        access.requireGlobalEnter();
        String normalized = type == null || type.isBlank() ? "DEVICE" : type.trim().toUpperCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if ("CREDENTIAL".equals(normalized)) {
            sql.append("""
                    SELECT c.id, c.name, CONCAT(IFNULL(c.cred_type, ''), ' / ', IFNULL(c.username, '')) AS detail,
                           '' AS management_ip, c.asset_status AS status, c.asset_source AS source,
                           c.external_id, '' AS environment,
                           (SELECT COUNT(*) FROM project_asset pa
                             WHERE pa.asset_type = 'CREDENTIAL' AND pa.asset_id = c.id) AS refs,
                           0 AS port_count, 0 AS collect_count,
                           NULL AS last_collected_at, NULL AS collect_status,
                           NULL AS management_credential_id, NULL AS bmc_credential_id,
                           c.cred_type AS cred_type
                      FROM credential c WHERE c.owner_scope = 'PLATFORM'
                    """);
        } else {
            sql.append("""
                    SELECT d.id, d.name, CONCAT(IFNULL(d.device_type, ''), ' / ', IFNULL(d.model, '')) AS detail,
                           d.management_ip, d.asset_status AS status, d.asset_source AS source,
                           d.external_id, d.environment,
                           (SELECT COUNT(*) FROM project_asset pa
                             WHERE pa.asset_type = 'DEVICE' AND pa.asset_id = d.id) AS refs,
                           (SELECT COUNT(*) FROM device_port dp WHERE dp.device_id = d.id) AS port_count,
                           (SELECT COUNT(*) FROM device_host_spec h WHERE h.device_id = d.id) AS collect_count,
                           (SELECT DATE_FORMAT(MAX(h.collected_at), '%Y-%m-%d %H:%i:%s')
                              FROM device_host_spec h WHERE h.device_id = d.id) AS last_collected_at,
                           (SELECT h.status FROM device_host_spec h WHERE h.device_id = d.id
                             ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS collect_status,
                           d.management_credential_id, d.bmc_credential_id,
                           NULL AS cred_type
                      FROM device d WHERE d.owner_scope = 'PLATFORM'
                    """);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR external_id LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (availability != null && !availability.isBlank()) {
            sql.append(" AND asset_status = ?");
            args.add(availability.trim());
        }
        sql.append(" ORDER BY name LIMIT 500");
        return jdbc.query(sql.toString(), (rs, i) -> new AssetRow(rs.getLong("id"), normalized,
                rs.getString("name"), rs.getString("detail"), rs.getString("management_ip"),
                rs.getString("status"), rs.getString("source"), rs.getString("external_id"),
                rs.getString("environment"), rs.getInt("refs"), rs.getInt("port_count"),
                rs.getInt("collect_count"), rs.getString("last_collected_at"),
                rs.getString("collect_status"),
                rs.getObject("management_credential_id", Long.class),
                rs.getObject("bmc_credential_id", Long.class),
                rs.getString("cred_type") == null ? null
                        : HostAccess.channelOf(rs.getString("cred_type"))), args.toArray());
    }

    public Map<String, Integer> references(String type, long id) {
        access.requireGlobalEnter();
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int count = projectAssets.referenceCount(normalized, id);
        List<Map<String, Object>> projects = jdbc.queryForList("""
                SELECT p.id, p.name FROM project_asset pa JOIN project p ON p.id = pa.project_id
                 WHERE pa.asset_type = ? AND pa.asset_id = ? ORDER BY p.name
                """, normalized, id);
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("count", count);
        for (Map<String, Object> project : projects) {
            result.put(String.valueOf(project.get("id")) + ":" + project.get("name"), 1);
        }
        return result;
    }

    // ---------------------------------------------------------------- 导入

    public AssetPreview preview(byte[] content, String sourceName) {
        requireWrite();
        String sha = sha256Hex(content);
        AssetImportService.ParsedAssets parsed = AssetImportService.parse(new ByteArrayInputStream(content));
        List<String> samples = AssetImportService.summarize(parsed).stream().limit(20).toList();
        int total = parsed.devices().size() + parsed.ports().size() + parsed.credentials().size();
        Optional<Map<String, Object>> batch = findBatch(sha);
        long batchId = batch.map(item -> ((Number) item.get("id")).longValue())
                .orElseGet(() -> saveBatch(sha, sourceName(parsed, sourceName), total, 0, 0, 0,
                        "PREFLIGHT"));
        boolean replayed = batch.map(item -> "COMMITTED".equals(item.get("status"))).orElse(false);
        return new AssetPreview(batchId, sha, parsed.devices().size(), parsed.ports().size(),
                parsed.credentials().size(), total, samples, parsed.warnings(),
                parsed.sheetsScanned(), replayed);
    }

    @Transactional
    public AssetImportResult importAssets(byte[] content, String sourceName) {
        requireWrite();
        String sha = sha256Hex(content);
        Optional<Map<String, Object>> existing = findBatch(sha);
        if (existing.isPresent() && "COMMITTED".equals(existing.get().get("status"))) {
            // 提交时把 devices/ports/credentials 依次记在 new/updated/unchanged 三列，
            // 这里按同一映射还原，保证「同文件重复提交」返回上次的真实读数。
            Map<String, Object> batch = existing.get();
            return new AssetImportResult(((Number) batch.get("id")).longValue(), sha,
                    intOf(batch.get("new_count")), intOf(batch.get("updated_count")),
                    intOf(batch.get("unchanged_count")), intOf(batch.get("deprecated_count")), 0,
                    List.of("同一文件已导入过，返回上次批次结果；未重复写库"), true);
        }
        AssetImportService.ParsedAssets parsed = AssetImportService.parse(new ByteArrayInputStream(content));
        Long operator = TenantContext.userId();
        Map<String, Long> deviceIds = new HashMap<>();
        Set<String> seenDevices = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, name FROM device WHERE owner_scope = 'PLATFORM'")) {
            deviceIds.put(key(String.valueOf(row.get("name"))), ((Number) row.get("id")).longValue());
        }

        int deviceCount = 0;
        int portCount = 0;
        int credentialCount = 0;
        int statusSupplemented = 0;

        for (AssetImportService.DeviceRow row : parsed.devices()) {
            String key = key(row.name());
            seenDevices.add(key);
            if (row.status() != null) {
                statusSupplemented++;
            }
            long id = upsertDevice(deviceIds.get(key), row, operator);
            deviceIds.put(key, id);
            deviceCount++;
        }
        for (AssetImportService.PortRow row : parsed.ports()) {
            String key = key(row.deviceName());
            seenDevices.add(key);
            long deviceId = deviceIds.computeIfAbsent(key, k -> upsertDevice(null,
                    new AssetImportService.DeviceRow(row.deviceName(), row.deviceName(), "交换机",
                            null, null, "当前测试环境", null, null, null, null, null, null, null, null,
                            row.section(), row.sourceRow(), row.sourceSheet()), operator));
            upsertPort(deviceId, row, operator);
            if (row.portStatus() != null) {
                statusSupplemented++;
            }
            portCount++;
        }
        Set<String> seenCredentials = new HashSet<>();
        for (AssetImportService.CredentialRow row : parsed.credentials()) {
            seenCredentials.add(key(row.name()));
            upsertCredential(row, operator);
            credentialCount++;
        }

        int deprecations = 0;
        if (deviceCount + portCount > 0) {
            for (Map.Entry<String, Long> entry : deviceIds.entrySet()) {
                if (!seenDevices.contains(entry.getKey())) {
                    deprecations += jdbc.update("""
                            UPDATE device SET asset_status = 'deprecated', updated_by = ?
                             WHERE id = ? AND owner_scope = 'PLATFORM' AND asset_status = 'active'
                            """, operator, entry.getValue());
                }
            }
        }
        if (credentialCount > 0) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, name FROM credential WHERE owner_scope = 'PLATFORM'")) {
                if (!seenCredentials.contains(key(String.valueOf(row.get("name"))))) {
                    deprecations += jdbc.update("""
                            UPDATE credential SET asset_status = 'deprecated', updated_by = ?
                             WHERE id = ? AND owner_scope = 'PLATFORM' AND asset_status = 'active'
                            """, operator, ((Number) row.get("id")).longValue());
                }
            }
        }
        long batchId;
        if (existing.isPresent()) {
            batchId = ((Number) existing.get().get("id")).longValue();
        } else {
            batchId = saveBatch(sha, sourceName(parsed, sourceName),
                    deviceCount + portCount + credentialCount, deviceCount, portCount,
                    credentialCount, "PREFLIGHT");
        }
        jdbc.update("""
                UPDATE import_batch SET status = 'COMMITTED', committed_at = NOW(3),
                       source_name = ?, total_rows = ?, new_count = ?, updated_count = ?,
                       unchanged_count = ?, deprecated_count = ?
                 WHERE id = ?
                """, sourceName(parsed, sourceName), deviceCount + portCount + credentialCount,
                deviceCount, portCount, credentialCount, deprecations, batchId);
        audit.record("global.asset.import", "device", "PLATFORM",
                "sha=" + sha.substring(0, 12) + " devices=" + deviceCount + " ports=" + portCount
                        + " credentials=" + credentialCount + " deprecations=" + deprecations
                        + " statusSupplemented=" + statusSupplemented);
        return new AssetImportResult(batchId, sha, deviceCount, portCount, credentialCount,
                deprecations, statusSupplemented, parsed.warnings(), false);
    }

    private long upsertDevice(Long existingId, AssetImportService.DeviceRow row, Long operator) {
        String externalId = row.externalId();
        if (existingId == null && externalId != null && !externalId.isBlank()) {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM device WHERE owner_scope = 'PLATFORM' AND external_id = ?",
                    Long.class, externalId);
            if (!found.isEmpty()) {
                existingId = found.get(0);
            }
        }
        String status = row.status() == null ? "active" : row.status();
        if (existingId != null) {
            jdbc.update("""
                    UPDATE device SET name = ?, device_type = COALESCE(?, device_type),
                           management_ip = COALESCE(?, management_ip),
                           serial_no = COALESCE(?, serial_no),
                           environment = COALESCE(?, environment), model = COALESCE(?, model),
                           operating_system = COALESCE(?, operating_system),
                           kernel_version = COALESCE(?, kernel_version),
                           bmc_address = COALESCE(?, bmc_address),
                           login_mode = COALESCE(?, login_mode), account = COALESCE(?, account),
                           external_id = ?, asset_status = ?, asset_source = 'excel', updated_by = ?
                     WHERE id = ? AND owner_scope = 'PLATFORM'
                    """, row.name(), truncate(row.deviceType(), 64), truncate(row.managementIp(), 64),
                    truncate(row.serialNo(), 128), truncate(row.environment(), 64),
                    truncate(row.model(), 128), truncate(row.operatingSystem(), 64),
                    truncate(row.kernel(), 64), truncate(row.bmcAddress(), 128),
                    truncate(row.loginMode(), 64), truncate(row.account(), 128),
                    truncate(externalId, 128), status, operator, existingId);
            return existingId;
        }
        try {
            return insertDevice(row, status, operator);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 平台序列号唯一：重复设备改用无序列号入库，避免整批失败。
            return insertDevice(new AssetImportService.DeviceRow(row.name(), row.externalId(),
                    row.deviceType(), row.managementIp(), null, row.environment(), row.model(),
                    row.operatingSystem(), row.kernel(), row.bmcAddress(), row.softwareVersion(),
                    row.loginMode(), row.account(), row.status(), row.section(), row.sourceRow(),
                    row.sourceSheet()), status, operator);
        }
    }

    private long insertDevice(AssetImportService.DeviceRow row, String status, Long operator) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO device (project_id, owner_scope, name, device_type, management_ip,
                        status, serial_no, environment, model, operating_system, kernel_version,
                        bmc_address, login_mode, account, external_id, asset_status, asset_source,
                        created_by, updated_by)
                    VALUES (NULL, 'PLATFORM', ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'excel', ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, row.name());
            ps.setString(2, truncate(row.deviceType(), 64));
            ps.setString(3, truncate(row.managementIp(), 64));
            ps.setString(4, truncate(row.serialNo(), 128));
            ps.setString(5, truncate(row.environment(), 64));
            ps.setString(6, truncate(row.model(), 128));
            ps.setString(7, truncate(row.operatingSystem(), 64));
            ps.setString(8, truncate(row.kernel(), 64));
            ps.setString(9, truncate(row.bmcAddress(), 128));
            ps.setString(10, truncate(row.loginMode(), 64));
            ps.setString(11, truncate(row.account(), 128));
            ps.setString(12, truncate(row.externalId(), 128));
            ps.setString(13, status);
            ps.setObject(14, operator);
            ps.setObject(15, operator);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private void upsertPort(long deviceId, AssetImportService.PortRow row, Long operator) {
        List<Long> found = jdbc.queryForList(
                "SELECT id FROM device_port WHERE device_id = ? AND port_name = ?",
                Long.class, deviceId, row.portName());
        if (!found.isEmpty()) {
            jdbc.update("""
                    UPDATE device_port SET port_type = COALESCE(?, port_type),
                           speed_gbps = COALESCE(?, speed_gbps), nic_name = COALESCE(?, nic_name),
                           mac_address = COALESCE(?, mac_address),
                           port_status = COALESCE(?, port_status), mtu = COALESCE(?, mtu),
                           ip_address = COALESCE(?, ip_address), updated_by = ?
                     WHERE id = ?
                    """, truncate(row.portType(), 32), row.speedGbps(), truncate(row.nicName(), 64),
                    truncate(row.macAddress(), 32), truncate(row.portStatus(), 16), row.mtu(),
                    truncate(row.ipAddress(), 64), operator, found.get(0));
            return;
        }
        jdbc.update("""
                INSERT INTO device_port (device_id, port_name, port_type, speed_gbps, nic_name,
                                         mac_address, port_status, mtu, ip_address,
                                         created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, deviceId, truncate(row.portName(), 64), truncate(row.portType(), 32),
                row.speedGbps(), truncate(row.nicName(), 64), truncate(row.macAddress(), 32),
                truncate(row.portStatus(), 16), row.mtu(), truncate(row.ipAddress(), 64),
                operator, operator);
    }

    private void upsertCredential(AssetImportService.CredentialRow row, Long operator) {
        String secretRef = row.credRef() == null || row.credRef().isBlank()
                ? "env:IMPORTED_" + row.name().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT)
                : row.credRef();
        List<Long> found = jdbc.queryForList(
                "SELECT id FROM credential WHERE owner_scope = 'PLATFORM' AND name = ?",
                Long.class, row.name());
        if (!found.isEmpty()) {
            jdbc.update("""
                    UPDATE credential SET cred_type = ?, username = ?, secret_ref = ?, cred_ref = ?,
                           cred_masked = ?, asset_status = 'active', asset_source = 'excel',
                           external_id = ?, updated_by = ?
                     WHERE id = ?
                    """, truncate(row.credType(), 32), truncate(row.username(), 128),
                    truncate(secretRef, 256), truncate(row.credRef(), 256), truncate(row.masked(), 128),
                    truncate(row.externalId(), 128), operator, found.get(0));
            return;
        }
        jdbc.update("""
                INSERT INTO credential (project_id, owner_scope, name, cred_type, username, secret_ref,
                    cred_ref, cred_masked, asset_status, asset_source, external_id, created_by, updated_by)
                VALUES (NULL, 'PLATFORM', ?, ?, ?, ?, ?, ?, 'active', 'excel', ?, ?, ?)
                """, row.name(), truncate(row.credType(), 32), truncate(row.username(), 128),
                truncate(secretRef, 256), truncate(row.credRef(), 256), truncate(row.masked(), 128),
                truncate(row.externalId(), 128), operator, operator);
    }

    // ---------------------------------------------------------------- 采集

    /**
     * 显式补采：按设备管理 IP 触发一次主机清单采集（远程需凭据 + 二次确认）。
     *
     * <p>不自动跑：采集是带 SSH 副作用的动作，必须由操作者选凭据、点确认。
     * 采集记录挂到设备上（{@code device_host_spec.device_id}），列表的「采集状态」即由此派生。
     */
    public CollectResult collect(long deviceId, Long credentialId, String username, boolean confirmed) {
        requireWrite();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, management_ip FROM device WHERE id = ? AND owner_scope = 'PLATFORM'
                """, deviceId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公共设备不存在");
        }
        String host = rows.get(0).get("management_ip") == null
                ? null : String.valueOf(rows.get(0).get("management_ip"));
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该设备没有管理 IP，无法采集");
        }
        long platformId = platform.id();
        if (credentialId != null) {
            ensurePlatformCredentialReference(platformId, credentialId);
        }
        HostInventoryRecord record = hostInventory.collect(platformId, host, deviceId,
                TenantContext.userId(), username, credentialId, confirmed);
        jdbc.update("UPDATE device SET last_checked_at = NOW(3), updated_by = ? WHERE id = ?",
                TenantContext.userId(), deviceId);
        audit.record("global.asset.collect", "device", String.valueOf(deviceId),
                "hostInventory=" + record.id() + " status=" + record.status());
        return new CollectResult(deviceId, host, record.status(),
                record.collectedAt() == null ? null : record.collectedAt().toString(),
                record.nicCount(), record.rocePorts(), record.roceMaxGbps());
    }

    /** 平台项目对自己库里的凭据也要有一条引用，采集取密码时才能通过可见性校验。 */
    private void ensurePlatformCredentialReference(long platformId, long credentialId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_asset
                 WHERE project_id = ? AND asset_type = 'CREDENTIAL' AND asset_id = ?
                """, Long.class, platformId, credentialId);
        if (count == null || count == 0) {
            projectAssets.addReference(platformId, "CREDENTIAL", credentialId, null,
                    TenantContext.userId());
        }
    }

    // ------------------------------------------- 凭据 ↔ 设备（v4.12.0）

    /**
     * 「凭据 → 设备 × 地址面」展开（FR-v4.12-11）。
     *
     * <p>{@code COALESCE(NULLIF(x, ''), alt)} 的顺序是刻意的：现场给的是「映射假公网 IP」，
     * 业务管理地址才是内网真地址；能直连时优先用真地址，真地址为空才退回映射地址。
     *
     * <p>最近一次采集状态挂在设备上（{@code device_host_spec} 没有 plane 列），因此同一设备的
     * 两个地址面会显示同一条状态 —— 这是**已知简化**，不假装能分面，注释写在这里免得下一个人
     * 看到「BMC 面显示的是 Linux 采集状态」以为是 bug。
     */
    private static final String SQL_DEVICES_OF_CREDENTIAL = """
            SELECT x.device_id, x.name, x.plane, x.address,
                   (SELECT h.status FROM device_host_spec h WHERE h.device_id = x.device_id
                     ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS last_status,
                   (SELECT DATE_FORMAT(h.collected_at, '%Y-%m-%d %H:%i:%s')
                      FROM device_host_spec h WHERE h.device_id = x.device_id
                     ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS last_at
              FROM (
                    SELECT d.id AS device_id, d.name AS name, 'mgmt' AS plane,
                           COALESCE(NULLIF(d.management_ip, ''), d.management_ip_alt) AS address
                      FROM device d
                     WHERE d.owner_scope = 'PLATFORM' AND d.management_credential_id = ?
                     UNION ALL
                    SELECT d.id AS device_id, d.name AS name, 'bmc' AS plane,
                           COALESCE(NULLIF(d.bmc_address, ''), d.bmc_address_alt) AS address
                      FROM device d
                     WHERE d.owner_scope = 'PLATFORM' AND d.bmc_credential_id = ?
                   ) x
             ORDER BY x.name, x.plane
            """;

    public List<CredentialDevice> devicesOfCredential(long credentialId) {
        access.requireGlobalEnter();
        Map<String, Object> credential = requirePlatformCredential(credentialId);
        Object type = credential.get("cred_type");
        String channel = HostAccess.channelOf(type == null ? null : String.valueOf(type));
        return jdbc.query(SQL_DEVICES_OF_CREDENTIAL,
                (rs, index) -> new CredentialDevice(rs.getLong("device_id"), rs.getString("name"),
                        rs.getString("plane"), rs.getString("address"), channel,
                        rs.getString("last_status"), rs.getString("last_at")),
                credentialId, credentialId);
    }

    /**
     * 手工录入设备（FR-v4.12-13）。
     *
     * <p>为什么两个地址面各自绑凭据、而不是设备只绑一个：现场 GPU 服务器是「业务口 ssh + 带外
     * Redfish」两套账号，一台机器两条采集路径。只留一个 FK 的话，另一面就只能靠猜。
     */
    @Transactional
    public DeviceView createDevice(CreateDeviceRequest request) {
        requireWrite();
        String name = requireText(request.name(), "设备名称");
        guardLength(name, "设备名称", 128);
        String deviceType = normalizeDeviceType(request.deviceType());
        String managementIp = blankToNull(request.managementIp());
        String managementIpAlt = blankToNull(request.managementIpAlt());
        String bmcAddress = blankToNull(request.bmcAddress());
        String bmcAddressAlt = blankToNull(request.bmcAddressAlt());
        if (managementIp == null && managementIpAlt == null
                && bmcAddress == null && bmcAddressAlt == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "至少要填一个地址（业务管理地址或带外地址），否则这台设备无法被采集");
        }
        Long managementCredentialId =
                requirePlatformCredentialId(request.managementCredentialId(), "业务管理凭据");
        Long bmcCredentialId = requirePlatformCredentialId(request.bmcCredentialId(), "带外凭据");
        Long duplicated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE owner_scope = 'PLATFORM' AND name = ?",
                Long.class, name);
        if (duplicated != null && duplicated > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "设备名称已存在：" + name);
        }
        Long operator = TenantContext.userId();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO device (project_id, owner_scope, name, device_type,
                            management_ip, management_ip_alt, bmc_address, bmc_address_alt,
                            management_credential_id, bmc_credential_id, model, vendor, serial_no,
                            location, domain_tag, status, asset_status, asset_source,
                            created_by, updated_by)
                        VALUES (NULL, 'PLATFORM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1,
                                'active', 'manual', ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, name);
                ps.setString(2, deviceType);
                ps.setString(3, managementIp);
                ps.setString(4, managementIpAlt);
                ps.setString(5, bmcAddress);
                ps.setString(6, bmcAddressAlt);
                ps.setObject(7, managementCredentialId, java.sql.Types.BIGINT);
                ps.setObject(8, bmcCredentialId, java.sql.Types.BIGINT);
                ps.setString(9, blankToNull(request.model()));
                ps.setString(10, blankToNull(request.vendor()));
                ps.setString(11, blankToNull(request.serialNo()));
                ps.setString(12, blankToNull(request.location()));
                ps.setString(13, blankToNull(request.domainTag()));
                ps.setObject(14, operator, java.sql.Types.BIGINT);
                ps.setObject(15, operator, java.sql.Types.BIGINT);
                return ps;
            }, keys);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 平台序列号唯一约束：同一个序列号不能挂两台设备（录入时多半是复制粘贴旧行）
            throw new BusinessException(ErrorCode.CONFLICT,
                    "设备写入冲突（序列号可能已被占用）：" + blankToNull(request.serialNo()));
        }
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        audit.record("global.asset.device.create", "device", String.valueOf(id),
                "name=" + name + "; type=" + (deviceType == null ? "-" : deviceType)
                        + "; mgmt=" + (managementIp == null ? "-" : managementIp)
                        + "; bmc=" + (bmcAddress == null ? "-" : bmcAddress)
                        + "; mgmtCred=" + (managementCredentialId == null ? "-" : managementCredentialId)
                        + "; bmcCred=" + (bmcCredentialId == null ? "-" : bmcCredentialId));
        return new DeviceView(id, name, deviceType, managementIp, managementIpAlt, bmcAddress,
                bmcAddressAlt, managementCredentialId, bmcCredentialId, "manual");
    }

    /**
     * 手工录入凭据（FR-v4.12-14）。
     *
     * <p>明文只在这一层的入参里出现一次，随即加密入库；返回值是显式的 {@link CredentialView}，
     * 而不是实体原样序列化 —— 这样将来给 {@code credential} 表加列也不会顺手把密文带出去。
     */
    @Transactional
    public CredentialView createCredential(CreateCredentialRequest request) {
        requireWrite();
        String name = requireText(request.name(), "凭据名称");
        guardLength(name, "凭据名称", 128);
        String credType = normalizeCredType(request.credType());
        String username = blankToNull(request.username());
        String secret = requireText(request.secret(), "凭据内容");
        String connectOptions = blankToNull(request.connectOptions());
        guardLength(connectOptions, "连接参数", 256);
        Long duplicated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential WHERE owner_scope = 'PLATFORM' AND name = ?",
                Long.class, name);
        if (duplicated != null && duplicated > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "凭据名称已存在：" + name);
        }
        Long operator = TenantContext.userId();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO credential (project_id, owner_scope, name, cred_type, username,
                        secret_ref, cipher_blob, key_version, connect_options,
                        asset_status, asset_source, created_by, updated_by)
                    VALUES (NULL, 'PLATFORM', ?, ?, ?, 'encrypted', ?, 1, ?, 'active', 'manual', ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, credType);
            ps.setString(3, username);
            ps.setString(4, cipher.encrypt(secret));
            ps.setString(5, connectOptions);
            ps.setObject(6, operator, java.sql.Types.BIGINT);
            ps.setObject(7, operator, java.sql.Types.BIGINT);
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        // 审计只记「配了什么」，不记口令、不记密文、不记密文长度
        audit.record("global.asset.credential.create", "credential", String.valueOf(id),
                "name=" + name + "; type=" + credType
                        + "; target=" + (username == null ? "未指定" : username)
                        + "; configured=true; connectOptions=" + (connectOptions == null ? "-" : connectOptions));
        return new CredentialView(id, name, credType, username, connectOptions, true);
    }

    /**
     * 按凭据批量采集（FR-v4.12-12）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：一台上抛异常不该把其余台已经落库的采集记录
     * 一起回滚。现场 12 台里必然有几台口令过期，若整批原子，运维改完一台的口令再跑一次，
     * 前面成功的记录全丢，时间线上就永远是「失败」。
     *
     * <p>因此这里逐台独立：每台的成功/失败各自成立，最后汇总成 {@code {total, ok, failed, items}}。
     * 二次确认在方法入口一次判掉 —— 让 5 台各失败一次「缺 confirm=true」是噪音，不是信息。
     */
    public BatchCollectResult collectByCredential(long credentialId, boolean confirmed) {
        requireWrite();
        requirePlatformCredential(credentialId);
        if (!confirmed) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "批量采集需要二次确认：请求需带 confirm=true");
        }
        long platformId = platform.id();
        // 平台凭据要经 project_asset 引用才对执行域可见，否则取密码会被可见性校验拦掉
        ensurePlatformCredentialReference(platformId, credentialId);
        List<CredentialDevice> devices = devicesOfCredential(credentialId);
        List<BatchCollectItem> items = new ArrayList<>();
        int ok = 0;
        for (CredentialDevice device : devices) {
            if (device.address() == null || device.address().isBlank()) {
                items.add(new BatchCollectItem(device.deviceId(), device.name(), device.plane(), null,
                        "FAILED", "no_address"));
                continue;
            }
            try {
                HostInventoryRecord record = hostInventory.collect(platformId, device.address(),
                        device.deviceId(), TenantContext.userId(), null, credentialId, true);
                jdbc.update("UPDATE device SET last_checked_at = NOW(3), updated_by = ? WHERE id = ?",
                        TenantContext.userId(), device.deviceId());
                String status = record.status() == null ? "FAILED" : record.status();
                if ("OK".equals(status) || "PARTIAL".equals(status)) {
                    ok++;
                    items.add(new BatchCollectItem(device.deviceId(), device.name(), device.plane(),
                            device.address(), status, null));
                } else {
                    items.add(new BatchCollectItem(device.deviceId(), device.name(), device.plane(),
                            device.address(), status, lastReasonOf(record)));
                }
            } catch (RuntimeException ex) {
                LOG.warn("按凭据批量采集：单台失败 credentialId={} deviceId={} plane={} host={}",
                        credentialId, device.deviceId(), device.plane(), device.address(), ex);
                items.add(new BatchCollectItem(device.deviceId(), device.name(), device.plane(),
                        device.address(), "FAILED", reasonOf(ex)));
            }
        }
        int total = items.size();
        audit.record("global.asset.collect.batch", "credential", String.valueOf(credentialId),
                "total=" + total + "; ok=" + ok + "; failed=" + (total - ok));
        return new BatchCollectResult(total, ok, total - ok, items);
    }

    /**
     * 从已落库的采集记录里取失败原因。
     *
     * <p>采集器把「为什么失败」写在 {@code spec_json} 的 items 里（{@code reason} 字段），
     * 汇总接口把它提出来给运维看 —— 否则前端只能显示一个 FAILED，人还得逐台点开详情。
     *
     * <p>解析失败返回 null 而不是抛：原因只是附加信息，状态已经落库，不能因为一段脏 JSON
     * 把整批采集结果弄成 500。
     */
    private String lastReasonOf(HostInventoryRecord record) {
        return firstReason(mapper, record.specJson());
    }

    /** 取出第一条非空 {@code items[].reason}；测试直接打这个入口。 */
    static String firstReason(ObjectMapper mapper, String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return null;
        }
        try {
            for (JsonNode item : mapper.readTree(specJson).path("items")) {
                String reason = item.path("reason").asText("");
                if (!reason.isBlank()) {
                    return clip(reason.replaceAll("\\s+", " ").trim(), 200);
                }
            }
            return null;
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return null;
        }
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private Map<String, Object> requirePlatformCredential(long credentialId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, cred_type, username, connect_options
                  FROM credential WHERE id = ? AND owner_scope = 'PLATFORM'
                """, credentialId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "平台凭据不存在：" + credentialId);
        }
        return rows.get(0);
    }

    /** 绑定的凭据必须是平台凭据：拿项目私有凭据 ID 来绑，等于把自己的口令挂到公共资产上。 */
    private Long requirePlatformCredentialId(Long credentialId, String label) {
        if (credentialId == null) {
            return null;
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential WHERE id = ? AND owner_scope = 'PLATFORM'",
                Long.class, credentialId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    label + "不存在或不是平台凭据：" + credentialId);
        }
        return credentialId;
    }

    static String normalizeDeviceType(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!DEVICE_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "设备类型只能是 gpu | server | storage | switch，收到：" + raw);
        }
        return normalized;
    }

    static String normalizeCredType(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "凭据类型不能为空");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!CREDENTIAL_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "凭据类型只能是 ssh | network | bmc | snmp | token，收到：" + raw);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        return value.trim();
    }

    private static void guardLength(String value, String field, int max) {
        if (value != null && value.length() > max) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    field + "长度不能超过 " + max + " 个字符");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 异常消息压成单行并截断：批量结果会直接进前端表格与审计，多行会把版面撑破。 */
    private static String reasonOf(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) : single;
    }

    // ------------------------------------------------------------ 引用治理

    public void reference(long projectId, String type, long assetId) {
        access.bindProject(projectId);
        projectAssets.addReference(projectId, type.trim().toUpperCase(Locale.ROOT), assetId, null,
                TenantContext.userId());
        audit.record("global.asset.reference", type.toLowerCase(Locale.ROOT),
                String.valueOf(assetId), "project=" + projectId);
    }

    public void unreference(long projectId, String type, long assetId) {
        access.bindProject(projectId);
        if (!projectAssets.removeReference(projectId, type.trim().toUpperCase(Locale.ROOT), assetId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该项目没有引用这条资产");
        }
        audit.record("global.asset.unreference", type.toLowerCase(Locale.ROOT),
                String.valueOf(assetId), "project=" + projectId);
    }

    /** 删除公共资产：被项目引用时 409（不泄露引用项目名，只给数量）。 */
    public void delete(String type, long id) {
        requireAdmin();
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int refs = projectAssets.referenceCount(normalized, id);
        if (refs > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "公共资产被 " + refs + " 个项目引用，不可删除；请先解除引用");
        }
        int deleted = switch (normalized) {
            case "DEVICE" -> jdbc.update("DELETE FROM device WHERE id = ? AND owner_scope = 'PLATFORM'", id);
            case "CREDENTIAL" -> jdbc.update(
                    "DELETE FROM credential WHERE id = ? AND owner_scope = 'PLATFORM'", id);
            case "LINK" -> jdbc.update("DELETE FROM link WHERE id = ? AND owner_scope = 'PLATFORM'", id);
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不支持的资产类型：" + type);
        };
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在或不是公共资产");
        }
        audit.record("global.asset.delete", normalized.toLowerCase(Locale.ROOT), String.valueOf(id), "");
    }

    // ---------------------------------------------------------------- 批次

    public PageResult<Map<String, Object>> batches(int page, int size) {
        access.requireGlobalEnter();
        long projectId = platform.id();
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE project_id = ? AND kind = 'asset'",
                Long.class, projectId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, source_name, checksum, status, total_rows, new_count, updated_count,
                       unchanged_count, deprecated_count, created_at, committed_at
                  FROM import_batch
                 WHERE project_id = ? AND kind = 'asset'
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, projectId, safeSize, (safePage - 1) * safeSize);
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    private Optional<Map<String, Object>> findBatch(String sha) {
        return jdbc.queryForList("""
                SELECT * FROM import_batch WHERE project_id = ? AND kind = 'asset' AND checksum = ?
                 ORDER BY id DESC LIMIT 1
                """, platform.id(), sha).stream().findFirst();
    }

    private long saveBatch(String sha, String sourceName, int total, int devices, int ports,
                           int credentials, String status) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO import_batch (project_id, source_type, source_name, checksum,
                                              version, status, kind, total_rows, new_count,
                                              updated_count, unchanged_count, deprecated_count)
                    VALUES (?, 'excel', ?, ?, 1, ?, 'asset', ?, ?, ?, ?, 0)
                    """, new String[]{"id"});
            ps.setLong(1, platform.id());
            ps.setString(2, sourceName);
            ps.setString(3, sha);
            ps.setString(4, status);
            ps.setInt(5, total);
            ps.setInt(6, devices);
            ps.setInt(7, ports);
            ps.setInt(8, credentials);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private static String sourceName(AssetImportService.ParsedAssets parsed, String sourceName) {
        if (sourceName != null && !sourceName.isBlank()) {
            return sourceName.length() > 200 ? sourceName.substring(0, 200) : sourceName;
        }
        return "assets.xlsx";
    }

    // ---------------------------------------------------------------- 导出

    public record ExportFile(String fileName, byte[] content, int rowCount) {
    }

    public ExportFile exportAssets() {
        access.requireGlobalEnter();
        List<AssetRow> assets = new ArrayList<>();
        assets.addAll(list("DEVICE", null, null));
        assets.addAll(list("CREDENTIAL", null, null));
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("公共资产");
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            String[] columns = {"资产类型", "名称", "明细", "管理IP", "状态", "来源", "业务键", "环境",
                    "引用数", "端口数", "采集次数", "最近采集", "采集状态"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }
            int r = 1;
            for (AssetRow asset : assets) {
                Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(asset.assetType());
                line.createCell(1).setCellValue(nullSafe(asset.name()));
                line.createCell(2).setCellValue(nullSafe(asset.detail()));
                line.createCell(3).setCellValue(nullSafe(asset.managementIp()));
                line.createCell(4).setCellValue(nullSafe(asset.status()));
                line.createCell(5).setCellValue(nullSafe(asset.source()));
                line.createCell(6).setCellValue(nullSafe(asset.externalId()));
                line.createCell(7).setCellValue(nullSafe(asset.environment()));
                line.createCell(8).setCellValue(asset.referenceCount());
                line.createCell(9).setCellValue(asset.portCount());
                line.createCell(10).setCellValue(asset.collectCount());
                line.createCell(11).setCellValue(nullSafe(asset.lastCollectedAt()));
                line.createCell(12).setCellValue(nullSafe(asset.collectStatus()));
            }
            for (int i = 0; i < columns.length; i++) {
                sheet.setColumnWidth(i, 4200);
            }
            workbook.write(out);
            audit.record("global.asset.export", "device", "PLATFORM", "rows=" + assets.size());
            return new ExportFile("public-assets.xlsx", out.toByteArray(), assets.size());
        } catch (java.io.IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出失败：" + ex.getMessage());
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String key(String value) {
        return (value == null ? "" : value.trim()).toLowerCase(Locale.ROOT);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
