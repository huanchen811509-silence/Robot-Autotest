package com.qa.autotest.api.asset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostInventoryCollector;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产详情只读聚合（v4.9.2 / OPT-04 / FR-49-13~16）。
 *
 * <p>把 {@code device} + {@code device_port} + {@code device_host_spec.spec_json} 聚成
 * 三分区（硬件 / 软件与带外 / 状态）+ 四层证据 + 派生值。
 *
 * <p><b>零新增采集实现</b>：只读三张既有表，命令原文直接取
 * {@link HostInventoryCollector#COMMANDS} 这份唯一白名单，不调用采集、不写 SSH/Redfish/SNMP
 * 代码、不加数据库列。<b>逐字段脱敏</b>：凭据只以 {@code keyMasked} 前缀掩码出现，
 * 所有结构化值经 {@link SecretRedactor} 递归清洗，任何 {@code secret/password/明文口令} 键
 * 都被改写成 {@code keyMasked}。
 *
 * <p>映射写成完整路径而非类级 {@code @RequestMapping("/api/asset-host-inventory")}：
 * 与既有 {@code HostInventoryController} 同前缀会形成重复类级映射，这里用独立完整路径避免冲突。
 */
@RestController
public class AssetDetailController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> HARDWARE = Set.of("CPU", "MEMORY", "DISK", "GPU", "NIC");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "secret", "password", "passwd", "pwd", "token", "private_key", "privatekey",
            "credential", "api_key", "apikey", "access_key", "口令", "密码", "密钥", "凭据");

    private final JdbcTemplate jdbc;
    private final AccessControlService access;

    public AssetDetailController(JdbcTemplate jdbc, AccessControlService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/api/asset-host-inventory/detail")
    public ApiResponse<AssetDetailResponse> detail(@RequestParam long deviceId) {
        List<Map<String, Object>> deviceRows = jdbc.queryForList(
                "SELECT * FROM device WHERE id = ?", deviceId);
        if (deviceRows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "设备不存在：" + deviceId);
        }
        Map<String, Object> device = deviceRows.get(0);
        long projectId = number(device.get("project_id")).longValue();
        // 项目资源级鉴权：跨项目一律 403（先取到 project_id 再判，不泄漏其它项目数据）
        access.requireProjectAccess(projectId);

        List<AssetDetailResponse.Port> ports = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT port_name, port_type, mac_address, speed_gbps FROM device_port "
                        + "WHERE device_id = ? ORDER BY id", deviceId)) {
            Object speed = row.get("speed_gbps");
            ports.add(new AssetDetailResponse.Port(str(row.get("port_name")),
                    str(row.get("port_type")), str(row.get("mac_address")),
                    speed instanceof Number n ? n.intValue() : null));
        }

        List<Map<String, Object>> specRows = jdbc.queryForList(
                "SELECT status, spec_json, spec_hash, collected_at, nic_count, roce_ports, "
                        + "roce_max_gbps FROM device_host_spec WHERE device_id = ? "
                        + "ORDER BY collected_at DESC, id DESC LIMIT 1", deviceId);
        String specStatus = null;
        String collectedAt = null;
        String specHash = null;
        int nicCount = 0;
        int rocePorts = 0;
        Integer roceMaxGbps = null;
        List<AssetDetailResponse.EvidenceItem> evidence = new ArrayList<>();
        List<String> hardwareCategories = new ArrayList<>();
        if (!specRows.isEmpty()) {
            Map<String, Object> spec = specRows.get(0);
            specStatus = sanitizeString(str(spec.get("status")));
            collectedAt = str(spec.get("collected_at"));
            specHash = str(spec.get("spec_hash"));
            nicCount = number(spec.get("nic_count")).intValue();
            rocePorts = number(spec.get("roce_ports")).intValue();
            Object max = spec.get("roce_max_gbps");
            roceMaxGbps = max instanceof Number n ? n.intValue() : null;
            evidence = parseEvidence(str(spec.get("spec_json")));
            for (AssetDetailResponse.EvidenceItem item : evidence) {
                if (HARDWARE.contains(item.category())) {
                    hardwareCategories.add(item.category());
                }
            }
        }

        AssetDetailResponse.Hardware hardware = new AssetDetailResponse.Hardware(
                sanitizeString(str(device.get("device_type"))), ports, hardwareCategories);
        AssetDetailResponse.SoftwareAndOutOfBand software = new AssetDetailResponse.SoftwareAndOutOfBand(
                sanitizeString(str(device.get("operating_system"))),
                sanitizeString(str(device.get("kernel_version"))),
                sanitizeString(str(device.get("bmc_address"))),
                sanitizeString(str(device.get("login_mode"))));
        AssetDetailResponse.Status status = new AssetDetailResponse.Status(
                sanitizeString(str(device.get("status"))),
                sanitizeString(str(device.get("asset_status"))),
                specStatus, collectedAt, specHash);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("nicCount", nicCount);
        derived.put("rocePorts", rocePorts);
        derived.put("roceMaxGbps", roceMaxGbps);
        derived.put("portCount", ports.size());
        derived.put("evidenceItemCount", evidence.size());

        // 凭据：account / login_mode 只以 keyMasked 前缀掩码出现，绝不回明文或 secret 键
        String keyRef = str(device.get("account"));
        if (keyRef == null || keyRef.isBlank()) {
            keyRef = str(device.get("login_mode"));
        }
        AssetDetailResponse.MaskedCredential credential =
                new AssetDetailResponse.MaskedCredential(maskPrefix(keyRef));

        return ApiResponse.ok(new AssetDetailResponse(
                deviceId, projectId, sanitizeString(str(device.get("name"))),
                hardware, software, status, evidence, derived, credential));
    }

    // ------------------------------------------------------------------ spec_json → 四层证据

    private static List<AssetDetailResponse.EvidenceItem> parseEvidence(String specJson) {
        List<AssetDetailResponse.EvidenceItem> result = new ArrayList<>();
        if (specJson == null || specJson.isBlank()) {
            return result;
        }
        try {
            JsonNode root = MAPPER.readTree(specJson);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return result;
            }
            for (JsonNode node : items) {
                String category = text(node, "category");
                // 命令原文取唯一白名单（复用只读命令链路，不另写一份）
                String command = HostInventoryCollector.COMMANDS.get(category);
                String label = node.hasNonNull("label") ? text(node, "label")
                        : HostInventoryCollector.LABELS.get(category);
                Object structured = node.has("value")
                        ? MAPPER.convertValue(node.get("value"), Object.class) : null;
                result.add(new AssetDetailResponse.EvidenceItem(
                        sanitizeString(category), sanitizeString(label),
                        sanitizeString(text(node, "collectedAt")),
                        sanitizeString(command),
                        // 原始输出：该采集项持久化的原始 JSON 载荷（stdout 本身不入库，零新增采集）
                        sanitizeString(node.toString()),
                        sanitize(structured),
                        sanitizeString(text(node, "reason"))));
            }
        } catch (JsonProcessingException ex) {
            result.add(new AssetDetailResponse.EvidenceItem("SPEC_JSON", "清单解析", null, null,
                    "***", null, "spec_json 无法解析：" + ex.getOriginalMessage()));
        }
        return result;
    }

    // ------------------------------------------------------------------ 脱敏

    /** 递归逐字段脱敏：敏感键整体改写为 keyMasked，字符串值过 SecretRedactor。 */
    private static Object sanitize(Object value) {
        if (value instanceof String s) {
            return SecretRedactor.redact(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveKey(key)) {
                    out.put("keyMasked", maskPrefix(key + ":" + String.valueOf(entry.getValue())));
                } else {
                    out.put(key, sanitize(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object element : list) {
                out.add(sanitize(element));
            }
            return out;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE_KEYS) {
            if (lower.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    private static String maskPrefix(String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }
        return value.substring(0, Math.min(2, value.length())) + "***";
    }

    private static String sanitizeString(String value) {
        return value == null ? null : SecretRedactor.redact(value);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
