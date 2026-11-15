package com.qa.autotest.application.env;

import com.qa.autotest.application.host.HostInventoryCollector;
import com.qa.autotest.application.repository.DeviceSnapshotRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 探针包服务（v5.15.0 / FR-v5.15-04）。
 *
 * <p>包里存的是「按类是否 required」，命令文本永远从 {@link HostInventoryCollector#COMMANDS}
 * 取——门禁 29 从那份字面量抽取并与真实采集证据逐字比对，落第二份命令就是证据漂移。
 */
@Service
public class ProbePackService {

    public record CommandView(String key, String command, boolean required) {
    }

    public record PackView(String code, String deviceType, List<CommandView> commands) {
    }

    public record Evaluation(String packCode, long snapshotId, List<String> requiredKeys,
                             List<String> missingKeys, List<String> presentKeys) {
    }

    private final JdbcTemplate jdbc;
    private final DeviceSnapshotRepository snapshots;

    public ProbePackService(JdbcTemplate jdbc, DeviceSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    public List<String> codes() {
        return jdbc.query("SELECT code FROM probe_pack WHERE project_id = 0 AND enabled = 1 ORDER BY id",
                (rs, n) -> rs.getString("code"));
    }

    /** 按 device_type 选包，运行时与唯一命令真源合并；包不存在直接报，不静默回落到另一套命令。 */
    public PackView commandsFor(String deviceType) {
        String type = deviceType == null || deviceType.isBlank() ? "HOST_LINUX" : deviceType.trim();
        List<Map<String, Object>> packs = jdbc.queryForList(
                "SELECT id, code FROM probe_pack WHERE project_id = 0 AND device_type = ? "
                        + "AND enabled = 1 ORDER BY id LIMIT 1", type);
        if (packs.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到 device_type=" + type + " 的探针包");
        }
        long packId = ((Number) packs.get(0).get("id")).longValue();
        String code = String.valueOf(packs.get(0).get("code"));
        List<CommandView> commands = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT command_key, required FROM probe_pack_command "
                        + "WHERE project_id = 0 AND pack_id = ? ORDER BY sort_order", packId)) {
            String key = String.valueOf(row.get("command_key"));
            String command = HostInventoryCollector.COMMANDS.get(key);
            if (command == null) {
                // 包里登记了源码没有的类：宁可跳过也不编一条命令出来。
                continue;
            }
            commands.add(new CommandView(key, command,
                    requiredOf(row.get("required"))));
        }
        return new PackView(code, type, commands);
    }

    /** required 语义：拿最新快照的按项事实，点名缺哪些必采项。 */
    public Evaluation evaluate(long projectId, long snapshotId) {
        List<DeviceSnapshotItem> items = snapshots.items(projectId, snapshotId, null);
        if (items.isEmpty() && snapshots.find(projectId, snapshotId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "设备快照不存在: " + snapshotId);
        }
        Set<String> present = new LinkedHashSet<>();
        for (DeviceSnapshotItem item : items) {
            if (item.collected()) {
                present.add(item.itemKey());
            }
        }
        PackView pack = commandsFor("HOST_LINUX");
        List<String> required = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (CommandView command : pack.commands()) {
            if (!command.required()) {
                continue;
            }
            required.add(command.key());
            if (!present.contains(command.key())) {
                missing.add(command.key());
            }
        }
        return new Evaluation(pack.code(), snapshotId, required, missing, new ArrayList<>(present));
    }

    /**
     * TINYINT(1) 在 MariaDB JDBC 里返回 {@link Boolean}，不是 {@link Number}。
     * W9 真实探针抓到强转 Number 导致 /probe-packs/commands 500；这里同时接受
     * Boolean / Number / String 三种形态，避免换驱动或换列类型就再次炸掉。
     */
    static boolean requiredOf(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.intValue() == 1;
        }
        String text = String.valueOf(raw);
        return "1".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }

    /** 三源统计用不上的辅助：按 key 聚合最新事实，供闸门复用。 */
    public Map<String, String> factsOf(long projectId, long snapshotId) {
        Map<String, String> facts = new LinkedHashMap<>();
        for (DeviceSnapshotItem item : snapshots.items(projectId, snapshotId, null)) {
            facts.put(item.itemKey(), item.collected() ? String.valueOf(item.valueJson()) : "未采集到");
        }
        return facts;
    }
}
