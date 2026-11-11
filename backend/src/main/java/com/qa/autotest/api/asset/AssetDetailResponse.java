package com.qa.autotest.api.asset;

import java.util.List;
import java.util.Map;

/**
 * 资产详情只读聚合响应（v4.9.2 / OPT-04 / FR-49-13~16）。
 *
 * <p>把 {@code device} + {@code device_port} + {@code device_host_spec.spec_json} 聚成
 * 三分区（硬件 / 软件与带外 / 状态）+ 四层证据（{@code EvidenceItem} 的 collectedAt /
 * command / rawOutput / structuredValue）+ 网口数等派生值。
 *
 * <p><b>逐字段脱敏</b>：凭据只以 {@link MaskedCredential#keyMasked()} 前缀掩码出现，
 * 任何 {@code secret / password / 明文口令} 键都不得出现在响应里（由控制器 sanitize 保证）。
 * 本记录只读，不触发任何采集，不新增数据库列。
 */
public record AssetDetailResponse(
        long deviceId,
        long projectId,
        String name,
        Hardware hardware,
        SoftwareAndOutOfBand softwareAndOutOfBand,
        Status status,
        List<EvidenceItem> evidence,
        Map<String, Object> derived,
        MaskedCredential credential) {

    /** 硬件分区：设备类型 + 网口明细 + 命中的硬件类目。 */
    public record Hardware(String deviceType, List<Port> ports, List<String> specCategories) {
    }

    public record Port(String portName, String portType, String macAddress, Integer speedGbps) {
    }

    /** 软件与带外分区：OS / 内核 / BMC 带外地址 / 登录方式（均不含口令）。 */
    public record SoftwareAndOutOfBand(String operatingSystem, String kernelVersion,
                                       String bmcAddress, String loginMode) {
    }

    /** 状态分区：设备状态、软失效状态、采集状态、采集时刻、指纹。 */
    public record Status(String deviceStatus, String assetStatus, String specStatus,
                         String collectedAt, String specHash) {
    }

    /**
     * 四层证据：collectedAt（采集时刻）、command（命令原文，取自唯一白名单）、
     * rawOutput（原始输出，即该采集项持久化的原始 JSON 载荷）、structuredValue（结构化值）。
     * reason 为「未采集到」原因；采到时为 null。
     */
    public record EvidenceItem(String category, String label, String collectedAt,
                               String command, String rawOutput, Object structuredValue,
                               String reason) {
    }

    /** 凭据：只保留前缀掩码，绝不出现明文口令 / secret 键。 */
    public record MaskedCredential(String keyMasked) {
    }
}
