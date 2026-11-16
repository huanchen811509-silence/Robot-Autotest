package com.qa.autotest.application.env;

import com.qa.autotest.application.host.HostInventoryCollector;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 探针命令目录（v5.18.0 / FR-v5.18-05 / W68-10）。
 *
 * <p>三套包的命令**全部来自代码常量**，不来自数据库：表里只登记「哪个包、按什么顺序、
 * 哪些项 required」，命令文本运行时与这里合并。DB 里存命令文本必然与代码漂移，
 * 那就等于证据失效。
 *
 * <p>为什么不把交换机/BMC 命令也塞进 {@code HostInventoryCollector.COMMANDS}：
 * 门禁第 29 步（{@code check_host_inventory.py}）对那份常量有**恰好 10 类**的硬约束，
 * 并且要与真实采集证据逐字比对。往里加键会让 29 步直接判红——那不是本版要改的东西。
 * 所以这里做一层目录：Linux 直接**引用**那份常量（不复制），交换机与 BMC 各自成表。
 *
 * <p><b>边界（D-3 已拍板）</b>：交换机与 BMC 命令是「厂商无关的通用只读形态」，
 * 没有真机样本可验，因此真机采集记 BLOCKED；本版只保证建包、白名单一致性与
 * 「未知类型不回落」。
 */
public final class ProbeCommandCatalog {

    public static final String HOST_LINUX = "HOST_LINUX";
    public static final String NETWORK_SWITCH = "NETWORK_SWITCH";
    public static final String BMC = "BMC";

    private static final Map<String, String> SWITCH = orderedSwitch();
    private static final Map<String, String> BMC_MAP = orderedBmc();

    private ProbeCommandCatalog() {
    }

    /**
     * 按设备类型取白名单。
     *
     * @return 对应类型的命令表；**未知类型返回 null**，由调用方报 400 点名，绝不回落到 Linux 包
     */
    public static Map<String, String> forDeviceType(String deviceType) {
        if (deviceType == null || deviceType.isBlank()) {
            return null;
        }
        String type = deviceType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case HOST_LINUX -> HostInventoryCollector.COMMANDS;
            case NETWORK_SWITCH -> SWITCH;
            case BMC -> BMC_MAP;
            default -> null;
        };
    }

    /** 支持的类型清单（用于 400 的提示文案，避免只说「不支持」）。 */
    public static java.util.List<String> supportedTypes() {
        return java.util.List.of(HOST_LINUX, NETWORK_SWITCH, BMC);
    }

    /** 交换机只读：show 族，不带任何配置类子命令。 */
    private static Map<String, String> orderedSwitch() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("SW_VERSION", "export LC_ALL=C; show version");
        map.put("SW_INTERFACES", "export LC_ALL=C; show interfaces status");
        map.put("SW_LLDP", "export LC_ALL=C; show lldp neighbors");
        map.put("SW_MAC", "export LC_ALL=C; show mac address-table");
        return map;
    }

    /** BMC 只读：ipmitool 查询族，不写 SEL、不改 LAN 配置。 */
    private static Map<String, String> orderedBmc() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("BMC_MC_INFO", "export LC_ALL=C; ipmitool mc info");
        map.put("BMC_SDR", "export LC_ALL=C; ipmitool sdr type Temperature");
        map.put("BMC_SEL", "export LC_ALL=C; ipmitool sel list");
        map.put("BMC_LAN", "export LC_ALL=C; ipmitool lan print 1");
        return map;
    }
}
