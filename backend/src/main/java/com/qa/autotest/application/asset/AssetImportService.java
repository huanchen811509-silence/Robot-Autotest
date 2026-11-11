package com.qa.autotest.application.asset;

import java.io.InputStream;
import java.util.ArrayList;
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
 * 「当前测试环境」分节解析（v4.7.0 / FR-v4.7-01..02，v4.9.3 补齐采集与状态列）。
 *
 * <p>样本工作簿的环境区不是规整表格：一节一个主题（服务器 / 交换机 / 网卡与端口 / 凭据），
 * 节内各自一套表头。因此解析分三步：<b>认节 → 认表头 → 按别名取列</b>，
 * 不按固定列号读；空节直接跳过，不产生半条资产。
 *
 * <p>表头别名先按「归一化后的全等」匹配，归一化会去掉空格与括号内容，
 * 因此 {@code 速率(Mb/s)} 与 {@code 速率} 同义；组合表头（如
 * {@code BMC地址 / 映射地址 / 账户}）以完整归一化文本登记，不做模糊包含匹配，
 * 避免「网口数」被误认成「网口」。
 *
 * <p>本类只负责「读成行」，不碰数据库；幂等、软失效与状态补充在
 * {@link GlobalAssetService} 里做。
 */
public final class AssetImportService {

    /** 采集到的设备行（服务器 / 交换机同构；交换机没有主机名时用「角色」当名称）。 */
    public record DeviceRow(String name, String externalId, String deviceType, String managementIp,
                            String serialNo, String environment, String model,
                            String operatingSystem, String kernel, String bmcAddress,
                            String softwareVersion, String loginMode, String account, String status,
                            String section, int sourceRow, String sourceSheet) {
    }

    /** 采集到的端口行：名称 + 速率 + MAC + 状态 + MTU + IP + 网卡归属。 */
    public record PortRow(String deviceName, String portName, String portType, Integer speedGbps,
                          String macAddress, String portStatus, Integer mtu, String ipAddress,
                          String nicName, String section, int sourceRow, String sourceSheet) {
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

    /** 字段 → 别名（键为归一化后的表头文本，值可多个字段共享同一列）。 */
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        alias("deviceName", "设备名称", "设备", "交换机", "主机名", "节点", "主机");
        alias("portName", "端口", "端口名", "端口名称", "接口", "接口名", "网口");
        alias("portType", "端口类型", "介质");
        alias("speedGbps", "速率", "带宽", "speed", "端口速率");
        alias("macAddress", "当前mac", "mac", "mac地址");
        alias("macFallback", "永久mac");
        alias("portStatus", "状态", "端口状态", "当前状态");
        alias("mtu", "mtu");
        alias("portIp", "ip地址", "数据ip", "ip");
        alias("nicName", "网卡", "网卡名", "nic", "网卡型号/类别", "网卡型号");
        alias("nicIndex", "网卡序号", "网卡编号");
        alias("credName", "凭据名称", "凭据");
        alias("credType", "凭据类型", "协议");
        alias("username", "用户名", "账号", "账户", "账户/权限/凭据引用");
        alias("credRef", "凭据引用", "密钥引用", "secret_ref", "引用");
        alias("password", "密码", "口令", "password");
        alias("name", "名称", "主机名", "节点名称", "hostname", "device");
        alias("deviceType", "设备类型", "类型", "角色");
        alias("managementIp", "管理ip", "管理地址", "带外ip", "bmc");
        alias("bmcAddress", "bmc地址/映射地址/账户");
        alias("model", "型号");
        alias("serialNo", "序列号", "sn", "设备序列号");
        alias("environment", "环境", "环境名称", "测试环境");
        alias("operatingSystem", "操作系统", "os");
        alias("kernel", "内核");
        alias("softwareVersion", "软件版本", "版本");
        alias("loginMode", "登录方式/端口", "登录方式");
        alias("nicSummary", "网卡配置汇总");
    }

    private static void alias(String field, String... names) {
        for (String name : names) {
            ALIASES.computeIfAbsent(normalize(name), k -> new ArrayList<>()).add(field);
        }
    }

    /** 汇总 / 映射类分节：只描述关系或事实，不产生资产行，避免与「网卡与端口信息」重复计数。 */
    private static final List<String> SKIP_SECTION_KEYWORDS =
            List.of("rdma", "端点绑定", "环境事实", "环境限制", "bond");

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
                boolean skipSection = false;
                Map<String, Integer> columns = null;
                String kind = null;
                for (Row row : sheet) {
                    List<String> cells = values(row, formatter);
                    if (cells.isEmpty()) {
                        continue;
                    }
                    String single = singleText(cells);
                    if (single != null) {
                        // 整行只有一个非空单元格 = 分节标题（不限制长度：样本标题常带括号说明）。
                        section = single.trim();
                        skipSection = isSkipSection(section);
                        columns = null;
                        kind = null;
                        continue;
                    }
                    if (skipSection) {
                        continue;
                    }
                    Map<String, Integer> detected = detectHeader(cells);
                    if (detected.size() >= 2) {
                        columns = detected;
                        kind = classify(detected);
                        continue;
                    }
                    if (columns == null || kind == null) {
                        continue;
                    }
                    int rowNum = row.getRowNum() + 1;
                    switch (kind) {
                        case "PORT" -> ports.addAll(portRows(cells, columns, section, rowNum,
                                sheet.getSheetName()));
                        case "CREDENTIAL" -> {
                            CredentialRow credential = credentialRow(cells, columns, section,
                                    rowNum, sheet.getSheetName());
                            if (credential != null) {
                                credentials.add(credential);
                                if (!isBlank(value(cells, columns.get("password")))) {
                                    warnings.add("第 " + rowNum + " 行包含明文密码，已按掩码导入，未落库");
                                }
                            }
                        }
                        default -> {
                            DeviceRow device = deviceRow(cells, columns, section, rowNum,
                                    sheet.getSheetName());
                            if (device != null) {
                                devices.add(device);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            warnings.add("工作簿解析失败：" + ex.getMessage());
        }
        if (devices.isEmpty() && ports.isEmpty() && credentials.isEmpty()) {
            warnings.add("未识别到「当前测试环境」分节或环境资产表头；该工作簿可能不含资产区");
        }
        if (credentials.isEmpty()) {
            warnings.add("未识别到凭据分节；账户列只作设备属性，不生成凭据记录");
        }
        return new ParsedAssets(devices, ports, credentials, warnings, sheetsScanned);
    }

    private static DeviceRow deviceRow(List<String> cells, Map<String, Integer> columns,
                                       String section, int rowNum, String sheetName) {
        String name = firstNonBlank(value(cells, columns.get("name")),
                value(cells, columns.get("deviceName")),
                value(cells, columns.get("deviceType")),
                value(cells, columns.get("managementIp")));
        if (isBlank(name) || isPlaceholder(name)) {
            return null;
        }
        // 交换机节没有「主机名」列，「角色」既是名称也最接近设备类型；类型统一按节名落「交换机」。
        String defaultType = defaultDeviceType(section);
        String type = "交换机".equals(defaultType)
                ? defaultType
                : firstNonBlank(value(cells, columns.get("deviceType")), defaultType);
        String ip = blankIfDash(value(cells, columns.get("managementIp")));
        String status = normalizeAssetStatus(value(cells, columns.get("portStatus")));
        String environment = firstNonBlank(value(cells, columns.get("environment")),
                defaultEnvironment(section));
        return new DeviceRow(name, isBlank(ip) ? name : ip,
                truncate(type, 64), ip,
                blankIfDash(value(cells, columns.get("serialNo"))),
                environment,
                value(cells, columns.get("model")),
                value(cells, columns.get("operatingSystem")),
                value(cells, columns.get("kernel")),
                value(cells, columns.get("bmcAddress")),
                value(cells, columns.get("softwareVersion")),
                value(cells, columns.get("loginMode")),
                value(cells, columns.get("username")),
                status, section, rowNum, sheetName);
    }

    private static List<PortRow> portRows(List<String> cells, Map<String, Integer> columns,
                                          String section, int rowNum, String sheetName) {
        String raw = value(cells, columns.get("portName"));
        if (isBlank(raw)) {
            return List.of();
        }
        String deviceName = firstNonBlank(value(cells, columns.get("deviceName")),
                value(cells, columns.get("name")));
        if (isBlank(deviceName)) {
            return List.of();
        }
        List<PortRow> rows = new ArrayList<>();
        String[] names = raw.split("\\r?\\n");
        for (int i = 0; i < names.length; i++) {
            String portName = names[i].trim();
            if (portName.isEmpty() || isPlaceholder(portName)) {
                continue;
            }
            String mac = firstValidMac(lineAt(cells, columns.get("macAddress"), i),
                    lineAt(cells, columns.get("macFallback"), i));
            String nicName = firstLine(value(cells, columns.get("nicName")));
            rows.add(new PortRow(deviceName, truncate(portName, 64),
                    truncate(firstLine(value(cells, columns.get("portType"))), 32),
                    parseSpeedGbps(lineAt(cells, columns.get("speedGbps"), i)),
                    mac,
                    normalizePortStatus(lineAt(cells, columns.get("portStatus"), i)),
                    parseInt(lineAt(cells, columns.get("mtu"), i)),
                    blankIfDash(lineAt(cells, columns.get("portIp"), i)),
                    truncate(nicName, 64),
                    section, rowNum, sheetName));
        }
        return rows;
    }

    /** 多行单元格按行取第 i 个值；单行单元格对所有 i 都返回该值；行数不足不编造。 */
    private static String lineAt(List<String> cells, Integer index, int i) {
        String raw = value(cells, index);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\r?\\n");
        if (parts.length == 1) {
            return parts[0].trim();
        }
        return i < parts.length ? parts[i].trim() : null;
    }

    private static CredentialRow credentialRow(List<String> cells, Map<String, Integer> columns,
                                               String section, int rowNum, String sheetName) {
        String name = firstNonBlank(value(cells, columns.get("credName")),
                value(cells, columns.get("username")));
        if (isBlank(name)) {
            return null;
        }
        return new CredentialRow(name, name,
                value(cells, columns.get("credType")),
                value(cells, columns.get("username")),
                value(cells, columns.get("credRef")),
                mask(value(cells, columns.get("password"))),
                section, rowNum, sheetName);
    }

    private static String defaultDeviceType(String section) {
        String text = section == null ? "" : section;
        if (text.contains("交换机")) {
            return "交换机";
        }
        return text.contains("服务器") ? "服务器" : null;
    }

    private static String defaultEnvironment(String section) {
        String text = section == null ? "" : section;
        return text.isBlank() ? null : "当前测试环境";
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

    private static boolean isSkipSection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return SKIP_SECTION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /** 占位符（破折号 / 无网口）不是真实端口名，不能落成端口行。 */
    private static boolean isPlaceholder(String value) {
        String text = value.trim();
        return "—".equals(text) || "-".equals(text) || "无".equals(text)
                || text.contains("无网口") || text.contains("无端口");
    }

    private static String blankIfDash(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return isPlaceholder(text) ? null : text;
    }

    /** 一列同时登记多个字段（如「主机名」既是设备名也是名称），读哪一列由行类型决定。 */
    private static Map<String, Integer> detectHeader(List<String> cells) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String cell = normalize(cells.get(i));
            if (cell.isEmpty()) {
                continue;
            }
            for (String field : ALIASES.getOrDefault(cell, List.of())) {
                found.putIfAbsent(field, i);
            }
        }
        if (!found.containsKey("portName")) {
            found.remove("portType");
            found.remove("speedGbps");
            found.remove("macAddress");
            found.remove("macFallback");
            found.remove("portStatus");
            found.remove("mtu");
            found.remove("portIp");
        }
        return found;
    }

    private static String classify(Map<String, Integer> columns) {
        if (columns.containsKey("portName")) {
            return "PORT";
        }
        if (columns.containsKey("credName") || columns.containsKey("credType")
                || columns.containsKey("credRef") || columns.containsKey("password")) {
            return "CREDENTIAL";
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
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 速率列常见单位是 Mb/s：超过 1000 的按 Mb/s 折算成 Gbps，避免把 200000 存成 20 万 G。 */
    private static Integer parseSpeedGbps(String value) {
        Integer raw = parseInt(value);
        if (raw == null) {
            return null;
        }
        return raw > 1000 ? raw / 1000 : raw;
    }

    /** 端口状态：保留 up / down 归一值，其它（如「未连接」）按原文截断，空值不编造。 */
    private static String normalizePortStatus(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        if ("—".equals(text) || "-".equals(text)) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("up")) {
            return "up";
        }
        if (lower.startsWith("down")) {
            return "down";
        }
        return truncate(text, 16);
    }

    /** 资产生命周期状态：表格里若写了在用/停用，映射到 active / deprecated；未写不猜。 */
    private static String normalizeAssetStatus(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.contains("停用") || text.contains("废弃") || text.contains("下线")
                || text.contains("deprecated") || text.contains("inactive")) {
            return "deprecated";
        }
        if (text.contains("在用") || text.contains("启用") || text.contains("active")) {
            return "active";
        }
        return null;
    }

    private static String firstValidMac(String preferred, String fallback) {
        if (isMac(preferred)) {
            return preferred.trim();
        }
        return isMac(fallback) ? fallback.trim() : null;
    }

    private static boolean isMac(String value) {
        return value != null && value.matches("[0-9A-Fa-f]{2}([:\\-][0-9A-Fa-f]{2}){5}");
    }

    private static String firstLine(String value) {
        if (value == null) {
            return null;
        }
        int index = value.indexOf('\n');
        String line = index < 0 ? value : value.substring(0, index);
        return line.isBlank() ? null : line.trim();
    }

    private static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return secret.length() <= 2 ? "**" : secret.substring(0, 1) + "***" + secret.charAt(secret.length() - 1);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('（', '(').replace('）', ')')
                .replaceAll("\\([^)]*\\)", "")
                .replace("\u00a0", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 便于单测与预览：把分节结果压成「类型:名称」列表。 */
    public static List<String> summarize(ParsedAssets parsed) {
        List<String> summary = new ArrayList<>();
        parsed.devices().forEach(item -> summary.add("DEVICE:" + item.name()));
        parsed.ports().forEach(item -> summary.add("PORT:" + item.deviceName() + "/" + item.portName()));
        parsed.credentials().forEach(item -> summary.add("CREDENTIAL:" + item.name()));
        return summary;
    }
}
