package com.qa.autotest.domain.asset;

/**
 * 设备端口（FR-v0.2-10）：端口是可被引用的实体，v0.4 租约的外键。
 *
 * <p>v4.3.0 / FR-v4.3-03：新增网卡归属 {@code nicName} / {@code nicIndex}（均可空）。
 * 未填写时归入「未归属」分组，不影响既有字段语义。
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
        Integer nicIndex) {
}
