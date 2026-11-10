package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 公共资产库（v4.7.0 / FR-v4.7-01..09、v4.7.1 / FR-v4.7-11..13）。
 *
 * <p>公共资产 = {@code owner_scope='PLATFORM'}（project_id 为空），复用 device / device_port / credential。
 * 导入按业务键 upsert：同名（或同 external_id）只更新不新增；源文件已无的资产软失效（asset_status=inactive），
 * 不物理删除，避免项目引用悬空。删除保护由 {@link ProjectAssetRepository#prepareHardDelete} 提供 409。
 */
@Service
public class GlobalAssetService {

    public record AssetRow(long id, String assetType, String name, String detail, String managementIp,
                           String status, String source, String externalId, String environment,
                           int referenceCount) {
    }

    public record AssetPreview(int devices, int ports, int credentials, int total,
                               List<String> samples, List<String> warnings, int sheetsScanned) {
    }

    public record AssetImportResult(int devices, int ports, int credentials, int deprecations,
                                    List<String> warnings) {
    }

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;
    private final ProjectAssetRepository projectAssets;

    public GlobalAssetService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                              ProjectAssetRepository projectAssets) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.projectAssets = projectAssets;
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
                             WHERE pa.asset_type = 'CREDENTIAL' AND pa.asset_id = c.id) AS refs
                      FROM credential c WHERE c.owner_scope = 'PLATFORM'
                    """);
        } else {
            sql.append("""
                    SELECT d.id, d.name, CONCAT(IFNULL(d.device_type, ''), ' / ', IFNULL(d.model, '')) AS detail,
                           d.management_ip, d.asset_status AS status, d.asset_source AS source,
                           d.external_id, d.environment,
                           (SELECT COUNT(*) FROM project_asset pa
                             WHERE pa.asset_type = 'DEVICE' AND pa.asset_id = d.id) AS refs
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
                rs.getString("environment"), rs.getInt("refs")), args.toArray());
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

    public AssetPreview preview(InputStream input) {
        requireWrite();
        AssetImportService.ParsedAssets parsed = AssetImportService.parse(input);
        List<String> samples = AssetImportService.summarize(parsed).stream().limit(20).toList();
        int total = parsed.devices().size() + parsed.ports().size() + parsed.credentials().size();
        return new AssetPreview(parsed.devices().size(), parsed.ports().size(),
                parsed.credentials().size(), total, samples, parsed.warnings(), parsed.sheetsScanned());
    }

    @Transactional
    public AssetImportResult importAssets(InputStream input) {
        requireWrite();
        AssetImportService.ParsedAssets parsed = AssetImportService.parse(input);
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

        for (AssetImportService.DeviceRow row : parsed.devices()) {
            String key = key(row.name());
            seenDevices.add(key);
            long id = upsertDevice(deviceIds.get(key), row.name(), row.deviceType(),
                    row.managementIp(), row.serialNo(), row.environment(), row.externalId(), operator);
            deviceIds.put(key, id);
            deviceCount++;
        }
        for (AssetImportService.PortRow row : parsed.ports()) {
            String key = key(row.deviceName());
            seenDevices.add(key);
            long deviceId = deviceIds.computeIfAbsent(key, k -> upsertDevice(null, row.deviceName(),
                    "switch", null, null, null, null, operator));
            upsertPort(deviceId, row, operator);
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
                            UPDATE device SET asset_status = 'inactive', updated_by = ?
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
                            UPDATE credential SET asset_status = 'inactive', updated_by = ?
                             WHERE id = ? AND owner_scope = 'PLATFORM' AND asset_status = 'active'
                            """, operator, ((Number) row.get("id")).longValue());
                }
            }
        }
        audit.record("global.asset.import", "device", "PLATFORM",
                "devices=" + deviceCount + " ports=" + portCount + " credentials=" + credentialCount
                        + " deprecations=" + deprecations);
        return new AssetImportResult(deviceCount, portCount, credentialCount, deprecations,
                parsed.warnings());
    }

    private long upsertDevice(Long existingId, String name, String deviceType, String ip,
                              String serialNo, String environment, String externalId, Long operator) {
        if (existingId == null && externalId != null && !externalId.isBlank()) {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM device WHERE owner_scope = 'PLATFORM' AND external_id = ?",
                    Long.class, externalId);
            if (!found.isEmpty()) {
                existingId = found.get(0);
            }
        }
        String type = truncate(deviceType, 64);
        if (existingId != null) {
            jdbc.update("""
                    UPDATE device SET name = ?, device_type = ?, management_ip = ?, serial_no = ?,
                           environment = ?, external_id = ?, asset_status = 'active',
                           asset_source = 'excel', updated_by = ?
                     WHERE id = ? AND owner_scope = 'PLATFORM'
                    """, name, type, truncate(ip, 64), truncate(serialNo, 128), truncate(environment, 64),
                    truncate(externalId, 128), operator, existingId);
            return existingId;
        }
        try {
            return insertDevice(name, type, ip, serialNo, environment, externalId, operator);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 平台序列号唯一：重复设备改用无序列号入库，避免整批失败。
            return insertDevice(name, type, ip, null, environment, externalId, operator);
        }
    }

    private long insertDevice(String name, String type, String ip, String serialNo,
                              String environment, String externalId, Long operator) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO device (project_id, owner_scope, name, device_type, management_ip,
                        status, serial_no, environment, external_id, asset_status, asset_source,
                        created_by, updated_by)
                    VALUES (NULL, 'PLATFORM', ?, ?, ?, 1, ?, ?, ?, 'active', 'excel', ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, type);
            ps.setString(3, truncate(ip, 64));
            ps.setString(4, truncate(serialNo, 128));
            ps.setString(5, truncate(environment, 64));
            ps.setString(6, truncate(externalId, 128));
            ps.setObject(7, operator);
            ps.setObject(8, operator);
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
                    UPDATE device_port SET port_type = ?, speed_gbps = ?, nic_name = ?, nic_index = ?,
                           updated_by = ?
                     WHERE id = ?
                    """, truncate(row.portType(), 32), row.speedGbps(), truncate(row.nicName(), 64),
                    row.nicIndex(), operator, found.get(0));
            return;
        }
        jdbc.update("""
                INSERT INTO device_port (device_id, port_name, port_type, speed_gbps, nic_name, nic_index,
                                         created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, deviceId, truncate(row.portName(), 64), truncate(row.portType(), 32),
                row.speedGbps(), truncate(row.nicName(), 64), row.nicIndex(), operator, operator);
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
            String[] columns = {"资产类型", "名称", "明细", "管理IP", "状态", "来源", "业务键", "环境", "引用数"};
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
