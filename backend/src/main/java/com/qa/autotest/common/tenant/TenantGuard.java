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
            // v4.1.0：报告索引进入工作区快照，按项目隔离（V8_1）
            "test_report",
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
            "device_host_spec",
            // v4.1.0：工作区导出/导入任务（任务即项目锁，ADR-4X-12..15）；
            // project_member 进快照的成员清单，按项目隔离
            "workspace_job", "project_member",
            // EHT-11 修复（2026-11-13）：v5.3–v5.4 建表时漏登记的租户业务表。
            // 三张表都有 project_id；未登记时 requireProjectScope 直接抛 500
            // 「未配置租户策略」，六页读真实端点全部失败（实测 3/12 端点 500）。
            "case_duration_baseline", "case_failure_disposition", "manual_execution",
            // v5.15.0：设备快照两表都带 project_id，按项目隔离。
            "device_snapshot", "device_snapshot_item",
            // v5.15.0：探针包两表带 project_id（0=平台预置），查询按项目条件收口。
            "probe_pack", "probe_pack_command",
            // v5.16.0：运维任务表带 project_id，按项目隔离。
            "ops_task",
            // 2026-11-21 补登记（CI 首次用空库跑 IT 时暴露）：三张表一直被 requireProjectScope
            // 调用，却从未进名单，调用点必定抛 500「未配置租户策略」。
            // 它们的租户条件都由 JOIN run / 子查询带上（见 ArchiveRepositoryJdbc、
            // TestCaseRepositoryJdbc.delete），不是"不需要租户"。
            "test_result", "keyword_result", "case_script_binding",
            // v5.35.0 / 2026-11-21：用例判据不可变版本表（V11_2）以 test_case_id 关联用例，
            // 本身没有 project_id。删除用例要先清它，清理 SQL 用
            // `test_case_id IN (SELECT id FROM test_case WHERE project_id = ?)` 携带租户条件。
            // 漏登记时 TenantGuard 直接抛 500「未配置租户策略」——用户点删除一律失败，
            // 而开发库停在旧 schema 时表里没数据、看起来"只是删不掉"，问题被掩盖了三个月。
            "case_requirement_version");

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
