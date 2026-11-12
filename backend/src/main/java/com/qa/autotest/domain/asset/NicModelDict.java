package com.qa.autotest.domain.asset;

import java.time.Instant;

/**
 * 网卡型号字典行（FR-v5.2-08 / V11_50）。
 *
 * <p>{@code vendorKey} / {@code modelKey} 是入库时算好的归一化键，用来做唯一约束与匹配；
 * {@code vendor} / {@code model} 保留录入原样，界面照原样显示。两者分开存而不是
 * 「入库前就地归一」，是为了让「字典里这一行原本叫什么」可追溯——匹配键丢了大小写，
 * 光看匹配键没法判断这台机器的 {@code ConnectX-7} 到底被谁覆盖了。
 */
public record NicModelDict(
        Long id,
        String vendor,
        String model,
        String displayName,
        String formFactor,
        String vendorId,
        String deviceId,
        String capabilitiesJson,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    /** 兼容 v5.2.0 之前的形态（无审计时间戳）。 */
    public NicModelDict(Long id, String vendor, String model, String displayName,
                        String formFactor, String vendorId, String deviceId,
                        String capabilitiesJson, boolean enabled) {
        this(id, vendor, model, displayName, formFactor, vendorId, deviceId,
                capabilitiesJson, enabled, null, null);
    }
}
