package com.qa.autotest.common.tenant;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.Locale;
import java.util.Set;

/**
 * 租户条件守卫（ADR-0006 第 2 层）。
 *
 * <p>所有黑名单表的 SQL 在执行前必须经过本类。守卫只检查「SQL 是否带了 project_id 条件」，
 * <b>不做自动改写</b>：改写复杂 SQL 的风险高于收益，漏写就在这里失败。
 *
 * <p>第 3 层由元测试兜底（{@code TenantGuardMetaTest}）：故意写危险 SQL，断言被拦。
 */
public final class TenantGuard {

    /** 登录前或全局查询的白名单：不需要租户条件。 */
    public static final Set<String> WHITELIST_TABLES = Set.of(
            "flyway_schema_history", "app_user", "role", "system_setting", "audit_log",
            // 令牌按哈希精确命中，查询发生在租户上下文建立之前（PLN-03）
            "api_token",
            // v3.3.0：发布记录是平台级时间线，不属于某个项目（FR-3.3-11）
            "release_record");

    /** 必须带租户条件的业务表。新增业务表默认加进这里。 */
    public static final Set<String> TENANT_TABLES = Set.of(
            "project", "project_setting", "project_label",
            "device", "device_port", "link", "credential", "project_asset", "env_profile", "topology_node",
            "test_case", "test_case_step", "test_set", "test_set_item",
            "robot_file", "robot_file_version", "case_requirement", "editor_keyword",
            "schedule", "schedule_dependency", "execution_job", "run", "export_record",
            "execution_plan", "execution_plan_dependency",
            // v2.1 ORCH-01：计划组与成员（都按 project_id 隔离）
            "plan_group", "plan_group_item",
            // v2.1 TPLX-01/TPLX-02：报告模板与模板字段（项目级）
            "report_template", "report_template_field",
            // v2.1 PUBX-01：资产版本（内容哈希 + 同步状态）
            "asset_version",
            // v2.1 NODX-01：执行节点（节点本身是全局的，但归属项目用于可见性）
            "execution_node",
            "resource_lease", "resource_lease_history", "run_artifact", "run_attempt",
            "run_metadata", "ci_trigger",
            // v2.2 W2：运行分片（按 project_id 隔离）
            "execution_shard",
            // v3.1 EXPX：保存视图 / 批量撤销 / 站内通知与订阅 / 按用户隔离的已读记录
            "saved_view", "batch_undo", "notification", "notification_preference",
            "notification_read",
            // v3.3.0：AI 工作台与运维闭环（项目级配置 / 模板 / 消息 / 告警 / SLO）
            "ai_project_config", "ai_prompt_template", "ai_message", "ai_session", "ai_action",
            "alert_channel", "alert_rule", "alert_event", "slo_daily",
            // v3.4.0：归档保留（策略与冷表都按项目隔离）
            "archive_policy", "archived_run", "archived_test_result",
            "archived_keyword_result",
            // v3.11.0：主机清单（按项目隔离；同一台设备可能被多个项目各自采集）
            "device_host_spec");

    private TenantGuard() {
    }

    /**
     * 断言 SQL 已经带租户条件。
     *
     * @param sql   即将执行的 SQL
     * @param table 该 SQL 的主要业务表名（用于报错定位）
     * @throws BusinessException 表在黑名单内但 SQL 不含 project_id 条件
     */
    public static void requireProjectScope(String sql, String table) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SQL 为空");
        }
        if (!TENANT_TABLES.contains(table)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "表 " + table + " 未配置租户策略：请在 TenantGuard 白名单或黑名单中登记");
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (!normalized.contains("project_id")) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "租户条件缺失：表 " + table + " 的查询未带 project_id");
        }
    }

    /** 判断某表是否要求租户条件。 */
    public static boolean requiresTenant(String table) {
        return TENANT_TABLES.contains(table);
    }
}
