package com.qa.autotest.domain.iam;

import java.time.LocalDateTime;

/**
 * 项目（多项目隔离的根）。
 *
 * @param kind          standard / platform（v4.5.0 / FR-v4.6-01）；platform 为公共库单例
 * @param codeChangedAt 编码唯一一次变更时间（v4.4.0 / FR-v4.4-08）；null 表示尚未改码
 */
public record Project(
        Long id,
        String code,
        String name,
        String description,
        boolean enabled,
        Long ownerId,
        String visibility,
        String testKernel,
        String kind,
        LocalDateTime codeChangedAt) {

    /** 兼容旧八参构造（测试桩 / 夹具）：kind 缺省 standard，codeChangedAt 缺省 null。 */
    public Project(Long id, String code, String name, String description,
                   boolean enabled, Long ownerId, String visibility, String testKernel) {
        this(id, code, name, description, enabled, ownerId, visibility, testKernel,
                "standard", null);
    }

    /** 兼容旧七参构造：testKernel 缺省 robot（RF 是平台主内核）。 */
    public Project(Long id, String code, String name, String description,
                   boolean enabled, Long ownerId, String visibility) {
        this(id, code, name, description, enabled, ownerId, visibility, "robot",
                "standard", null);
    }

    /** 是否平台项目（公共库载体，不可删 / 不可停用 / 不进普通项目列表）。 */
    public boolean platform() {
        return "platform".equalsIgnoreCase(kind);
    }
}
