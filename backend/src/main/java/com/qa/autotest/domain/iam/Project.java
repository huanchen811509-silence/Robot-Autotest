package com.qa.autotest.domain.iam;

/** 项目（多项目隔离的根）。 */
public record Project(
        Long id,
        String code,
        String name,
        String description,
        boolean enabled,
        Long ownerId,
        String visibility,
        String testKernel) {

    /** 兼容旧七参构造（测试桩 / 夹具共 21 处）：testKernel 缺省 robot（RF 是平台主内核）。 */
    public Project(Long id, String code, String name, String description,
                   boolean enabled, Long ownerId, String visibility) {
        this(id, code, name, description, enabled, ownerId, visibility, "robot");
    }
}
