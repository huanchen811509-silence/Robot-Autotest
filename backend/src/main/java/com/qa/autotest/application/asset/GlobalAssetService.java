package com.qa.autotest.application.asset;

import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
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

    public record AssetRow(long id, String assetType, String name, String detail, String managementIp,
                           String status, String source, String externalId, String environment,
                           int referenceCount, int portCount, int collectCount,
                           String lastCollectedAt, String collectStatus) {
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

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;
    private final ProjectAssetRepository projectAssets;
    private final PlatformProjectService platform;
    private final HostInventoryService hostInventory;

    public GlobalAssetService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                              ProjectAssetRepository projectAssets, PlatformProjectService platform,
                              HostInventoryService hostInventory) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.projectAssets = projectAssets;
        this.platform = platform;
        this.hostInventory = hostInventory;
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
                           NULL AS last_collected_at, NULL AS collect_status
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
                             ORDER BY h.collected_at DESC, h.id DESC LIMIT 1) AS collect_status
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
                rs.getString("collect_status")), args.toArray());
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
