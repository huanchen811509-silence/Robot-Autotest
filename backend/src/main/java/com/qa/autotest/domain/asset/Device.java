package com.qa.autotest.domain.asset;

import java.time.Instant;

/**
 * 设备台账（FR-v0.2-09）。
 *
 * <p>v5.1.0 / FR-v5.1-02：新增 inventory 渲染所需的五项
 * （{@code hostKey} 稳定键 + OS 三件套 + ssh 端口）。
 * 旧 14 参构造器保留，历史调用点与测试无需改动。
 *
 * @param hostKey inventory 的 hosts 键（`hostname` 或 `hostname@management_ip`），
 *                由工程配置 {@code pytest.inventory_key_rule} 决定拼接方式
 */
public record Device(
        Long id,
        Long projectId,
        String name,
        String deviceType,
        String model,
        String vendor,
        String serialNo,
        String managementIp,
        String location,
        String rackNo,
        String domainTag,
        boolean enabled,
        Long ownerId,
        Instant lastCheckedAt,
        String hostKey,
        String osFamily,
        String osVersion,
        String architecture,
        Integer sshPort,
        Long modelDictId) {

    /** v5.1.0 的 19 参形态（无型号字典关联）。 */
    public Device(Long id, Long projectId, String name, String deviceType, String model,
                  String vendor, String serialNo, String managementIp, String location,
                  String rackNo, String domainTag, boolean enabled, Long ownerId,
                  Instant lastCheckedAt, String hostKey, String osFamily, String osVersion,
                  String architecture, Integer sshPort) {
        this(id, projectId, name, deviceType, model, vendor, serialNo, managementIp, location,
                rackNo, domainTag, enabled, ownerId, lastCheckedAt, hostKey, osFamily, osVersion,
                architecture, sshPort, null);
    }

    /** v5.0.0 及以前的全参形态（保持源码兼容，避免历史测试批量改）。 */
    public Device(Long id, Long projectId, String name, String deviceType, String model,
                  String vendor, String serialNo, String managementIp, String location,
                  String rackNo, String domainTag, boolean enabled, Long ownerId,
                  Instant lastCheckedAt) {
        this(id, projectId, name, deviceType, model, vendor, serialNo, managementIp, location,
                rackNo, domainTag, enabled, ownerId, lastCheckedAt, null, null, null, null, null,
                null);
    }

    /** inventory 渲染用的 hosts 键：优先显式 hostKey，否则按规则派生。 */
    public String inventoryKey(String keyRule) {
        if (hostKey != null && !hostKey.isBlank()) {
            return hostKey.trim();
        }
        String host = name == null ? "" : name.trim();
        if ("hostname".equalsIgnoreCase(keyRule) || managementIp == null
                || managementIp.isBlank()) {
            return host;
        }
        return host + "@" + managementIp.trim();
    }
}
