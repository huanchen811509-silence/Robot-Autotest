package com.qa.autotest.application.asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 「当前测试环境」分节解析（v4.7.0 / FR-v4.7-02）。
 *
 * <p>样本工作簿的环境区不是规整表格：一节一个主题（服务器 / 交换机 / 端口 / 凭据），
 * 节内各自一套表头。因此解析分三步：<b>认节 → 认表头 → 按别名取列</b>，
 * 不按固定列号读；空节直接跳过，不产生半条资产。
 *
 * <p>纯函数、不碰数据库，便于单测与复用。
 */
public final class AssetImportService {

    public record DeviceRow(String name, String externalId, String deviceType, String managementIp,
                            String serialNo, String environment, String section, int sourceRow,
                            String sourceSheet) {
    }

    public record PortRow(String deviceName, String portName, String portType, Integer speedGbps,
                          String nicName, Integer nicIndex, String section, int sourceRow,
                          String sourceSheet) {
    }

    public record CredentialRow(String name, String externalId, String credType, String username,
                                String credRef, String masked, String section, int sourceRow,
                                String sourceSheet) {
    }

    public record ParsedAssets(List<DeviceRow> devices, List<PortRow> ports,
                               List<CredentialRow> credentials, List<String> warnings,
                               int sheetsScanned) {
    }

    private AssetImportService() {
    }

    private static final Map<String, List<String>> DEVICE_ALIASES = Map.of(
            "name", List.of("设备名称", "主机名", "节点名称", "名称", "device", "hostname", "节点"),
            "managementIp", List.of("管理ip", "ip", "ip地址", "带外ip", "管理地址", "bmc"),
            "deviceType", List.of("设备类型", "类型", "机型", "角色"),
            "serialNo", List.of("序列号", "sn", "设备序列号"),
            "environment", List.of("环境", "环境名称", "测试环境"));

    private static final Map<String, List<String>> PORT_ALIASES = Map.of(
            "deviceName", List.of("设备名称", "设备", "交换机", "主机名", "节点"),
            "portName", List.of("端口", "端口名", "端口名称", "接口", "接口名"),
            "portType", List.of("端口类型", "类型", "介质"),
            "speedGbps", List.of("速率", "带宽", "speed", "端口速率"),
            "nicName", List.of("网卡", "网卡名", "nic"),
            "nicIndex", List.of("网卡序号", "网卡编号"));

    private static final Map<String, List<String>> CRED_ALIASES = Map.of(
            "name", List.of("凭据名称", "名称", "账号名称", "凭据"),
            "credType", List.of("凭据类型", "类型", "协议"),
            "username", List.of("用户名", "账号", "用户"),
            "credRef", List.of("凭据引用", "密钥引用", "secret_ref", "引用"),
            "password", List.of("密码", "口令", "password"));

    private static final List<String> SECTION_KEYWORDS =
            List.of("服务器", "交换机", "存储", "端口", "网络", "凭据", "账号", "设备");

    public static ParsedAssets parse(InputStream input) {
        List<DeviceRow> devices = new ArrayList<>();
        List<PortRow> ports = new ArrayList<>();
        List<CredentialRow> credentials = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int sheetsScanned = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sheetsScanned++;
                String section = "";
                Map<String, Integer> columns = null;
                String kind = null;
                for (Row row : sheet) {
                    List<String> cells = values(row, formatter);
                    if (cells.isEmpty()) {
                        continue;
                    }
                    String single = singleText(cells);
                    if (single != null && isSection(single)) {
                        section = single.trim();
                        columns = null;
                        kind = null;
                        continue;
                    }
                    Map<String, Integer> detected = detectHeader(cells);
                    if (detected.size() >= 2) {
                        columns = detected;
                        kind = classify(detected, section);
                        continue;
                    }
                    if (columns == null || kind == null) {
                        continue;
                    }
                    String name = value(cells, columns.get("deviceName"));
                    if ("PORT".equals(kind) && isBlank(name)) {
                        name = value(cells, columns.get("name"));
                    }
                    if (isBlank(name)) {
                        continue;
                    }
                    int rowNum = row.getRowNum() + 1;
                    switch (kind) {
                        case "PORT" -> ports.add(new PortRow(name, value(cells, columns.get("portName")),
                                value(cells, columns.get("portType")),
                                parseInt(value(cells, columns.get("speedGbps"))),
                                value(cells, columns.get("nicName")),
                                parseInt(value(cells, columns.get("nicIndex"))),
                                section, rowNum, sheet.getSheetName()));
                        case "CREDENTIAL" -> credentials.add(new CredentialRow(name,
                                value(cells, columns.get("name")), value(cells, columns.get("credType")),
                                value(cells, columns.get("username")),
                                value(cells, columns.get("credRef")),
                                mask(value(cells, columns.get("password"))),
                                section, rowNum, sheet.getSheetName()));
                        default -> devices.add(new DeviceRow(name, value(cells, columns.get("name")),
                                value(cells, columns.get("deviceType")),
                                value(cells, columns.get("managementIp")),
                                value(cells, columns.get("serialNo")),
                                value(cells, columns.get("environment")),
                                section, rowNum, sheet.getSheetName()));
                    }
                    if ("CREDENTIAL".equals(kind) && !isBlank(value(cells, columns.get("password")))) {
                        warnings.add("第 " + rowNum + " 行包含明文密码，已按掩码导入，未落库");
                    }
                }
            }
        } catch (Exception ex) {
            warnings.add("工作簿解析失败：" + ex.getMessage());
        }
        if (devices.isEmpty() && ports.isEmpty() && credentials.isEmpty()) {
            warnings.add("未识别到「当前测试环境」分节或环境资产表头；该工作簿可能不含资产区");
        }
        return new ParsedAssets(devices, ports, credentials, warnings, sheetsScanned);
    }

    private static List<String> values(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        int last = Math.min(row.getLastCellNum(), 40);
        for (int i = 0; i < last; i++) {
            Cell cell = row.getCell(i);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    private static String singleText(List<String> cells) {
        String found = null;
        for (String cell : cells) {
            if (!cell.isEmpty()) {
                if (found != null) {
                    return null;
                }
                found = cell;
            }
        }
        return found;
    }

    private static boolean isSection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return text.length() <= 30 && SECTION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static Map<String, Integer> detectHeader(List<String> cells) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String cell = normalize(cells.get(i));
            if (cell.isEmpty()) {
                continue;
            }
            putIfAbsent(found, "deviceName", i, cell, PORT_ALIASES.get("deviceName"));
            putIfAbsent(found, "portName", i, cell, PORT_ALIASES.get("portName"));
            putIfAbsent(found, "portType", i, cell, PORT_ALIASES.get("portType"));
            putIfAbsent(found, "speedGbps", i, cell, PORT_ALIASES.get("speedGbps"));
            putIfAbsent(found, "nicName", i, cell, PORT_ALIASES.get("nicName"));
            putIfAbsent(found, "nicIndex", i, cell, PORT_ALIASES.get("nicIndex"));
            putIfAbsent(found, "credType", i, cell, CRED_ALIASES.get("credType"));
            putIfAbsent(found, "username", i, cell, CRED_ALIASES.get("username"));
            putIfAbsent(found, "credRef", i, cell, CRED_ALIASES.get("credRef"));
            putIfAbsent(found, "password", i, cell, CRED_ALIASES.get("password"));
            putIfAbsent(found, "deviceType", i, cell, DEVICE_ALIASES.get("deviceType"));
            putIfAbsent(found, "managementIp", i, cell, DEVICE_ALIASES.get("managementIp"));
            putIfAbsent(found, "serialNo", i, cell, DEVICE_ALIASES.get("serialNo"));
            putIfAbsent(found, "environment", i, cell, DEVICE_ALIASES.get("environment"));
            putIfAbsent(found, "name", i, cell, DEVICE_ALIASES.get("name"));
        }
        // 端口表头优先级：有 portName 才认为是端口节，避免把「名称」误判成设备列。
        if (!found.containsKey("portName")) {
            found.remove("portType");
            found.remove("speedGbps");
            found.remove("nicName");
            found.remove("nicIndex");
        }
        return found;
    }

    private static void putIfAbsent(Map<String, Integer> found, String key, int index, String cell,
                                    List<String> aliases) {
        if (found.containsKey(key)) {
            return;
        }
        for (String alias : aliases) {
            if (cell.equals(normalize(alias))) {
                found.put(key, index);
                return;
            }
        }
    }

    private static String classify(Map<String, Integer> columns, String section) {
        if (columns.containsKey("portName")) {
            return "PORT";
        }
        if (columns.containsKey("credType") || columns.containsKey("username")
                || columns.containsKey("credRef") || columns.containsKey("password")) {
            return "CREDENTIAL";
        }
        String lower = section.toLowerCase(Locale.ROOT);
        if (lower.contains("交换机") && !columns.containsKey("managementIp")) {
            return "PORT";
        }
        return "DEVICE";
    }

    private static String value(List<String> cells, Integer index) {
        if (index == null || index >= cells.size()) {
            return null;
        }
        String value = cells.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return secret.length() <= 2 ? "**" : secret.substring(0, 1) + "***" + secret.charAt(secret.length() - 1);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("（", "(").replace("）", ")").replace(" ", "")
                .replace("\u00a0", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 便于单测：把分节结果压成「类型:名称」列表。 */
    public static List<String> summarize(ParsedAssets parsed) {
        List<String> summary = new ArrayList<>();
        parsed.devices().forEach(item -> summary.add("DEVICE:" + item.name()));
        parsed.ports().forEach(item -> summary.add("PORT:" + item.deviceName() + "/" + item.portName()));
        parsed.credentials().forEach(item -> summary.add("CREDENTIAL:" + item.name()));
        return summary;
    }

    /** 供服务层做去重键（同名设备/端口只保留第一条）。 */
    public static Map<String, DeviceRow> uniqueDevices(ParsedAssets parsed) {
        Map<String, DeviceRow> map = new HashMap<>();
        for (DeviceRow row : parsed.devices()) {
            map.putIfAbsent(row.name().toLowerCase(Locale.ROOT), row);
        }
        return map;
    }
}
