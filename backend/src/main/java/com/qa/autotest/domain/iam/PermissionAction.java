package com.qa.autotest.domain.iam;

/** 权限动作维度（PRD §3.1 权限矩阵的行）。 */
public enum PermissionAction {
    READ, CREATE, UPDATE, DELETE, EXECUTE, USE,
    /** 进入全局模式（v4.4.0 / FR-v4.4-06）。 */
    ENTER,
    /** 批量导入（v4.6.0 / FR-v4.6-13）。 */
    IMPORT,
    /** 反向导出（v4.6.1 / FR-v4.6-22）。 */
    EXPORT,
    /** 模板治理（v4.8.0 / FR-v4.8-03）。 */
    MANAGE,
    /** 模板提升为全局可见（v4.8.0 / FR-v4.8-05）。 */
    PROMOTE
}
