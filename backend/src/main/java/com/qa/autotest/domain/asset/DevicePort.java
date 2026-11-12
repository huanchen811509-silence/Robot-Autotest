package com.qa.autotest.domain.asset;

/**
 * 设备端口（FR-v0.2-10）：端口是可被引用的实体，v0.4 租约的外键。
 *
 * <p>v4.3.0 / FR-v4.3-03：新增网卡归属 {@code nicName} / {@code nicIndex}（均可空）。
 * 未填写时归入「未归属」分组，不影响既有字段语义。
 *
 * <p>v5.1.0 / FR-v5.1-02（W51-03）：新增 NIC 级画像 15 项，字段名刻意与
 * 自研框架 inventory 的 {@code devices.nics.<netdev>} **逐字对齐**，
 * 让渲染器可以零映射直出（少一层翻译就少一处漂移）。
 *
 * @param pciDeviceId inventory 的 {@code device_id}（PCI Device ID）。
 *                    注意与 {@code deviceId}（所属设备外键）是两个东西，命名必须区分开。
 * @param nicPort     inventory 的 {@code port}（网卡物理口序号），不是端口表主键。
 */
public record DevicePort(
        Long id,
        Long deviceId,
        String portName,
        String portType,
        String protocol,
        String macAddress,
        Integer speedGbps,
        Integer mtu,
        String ipAddress,
        String portStatus,
        String nicName,
        Integer nicIndex,
        String nicVendor,
        String nicManufacturer,
        String nicModel,
        String nicFormFactor,
        String pciBdf,
        String vendorId,
        String pciDeviceId,
        String driver,
        String firmware,
        String rdmaDevice,
        Integer nicPort,
        Integer numaNode,
        String nicRole,
        String capabilitiesJson,
        String counterPatternsJson) {

    /** v5.0.0 及以前的 12 参形态（保持源码兼容）。 */
    public DevicePort(Long id, Long deviceId, String portName, String portType, String protocol,
                      String macAddress, Integer speedGbps, Integer mtu, String ipAddress,
                      String portStatus, String nicName, Integer nicIndex) {
        this(id, deviceId, portName, portType, protocol, macAddress, speedGbps, mtu, ipAddress,
                portStatus, nicName, nicIndex, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** inventory 里的 netdev 名：优先 nicName，退回 portName。 */
    public String netdev() {
        return nicName != null && !nicName.isBlank() ? nicName.trim()
                : (portName == null ? "" : portName.trim());
    }
}
