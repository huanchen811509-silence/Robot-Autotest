package com.qa.autotest.domain.iam;

/** 权限资源维度（PRD §3.1 权限矩阵的列）。 */
public enum PermissionResource {
    USER_ROLE("用户/角色"),
    PROJECT("项目"),
    ASSET("设备/端口/拓扑"),
    CREDENTIAL("凭据"),
    CASE("用例/用例集"),
    EXECUTION("执行"),
    IMPORT("导入"),
    AUDIT("审计日志"),
    /** 全局模式（v4.4.0 / FR-v4.4-06）。 */
    GLOBAL("全局模式"),
    /** 模板中心（v4.8.0 / FR-v4.8-03）。 */
    TEMPLATE("模板"),
    /** 存量同步（v4.9.0 / FR-v4.9-01）。 */
    SYNC("同步");

    private final String label;

    PermissionResource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
