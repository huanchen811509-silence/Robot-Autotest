package com.qa.autotest.application.system;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.SystemSettingRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 设置分级（NAV-02 / FR-v2.0-10 / 裁决 D10 = A）。
 *
 * <p>平台级：端口（只读展示）/ 执行并发 / 默认超时 / 报告保留天数 / AI 开关，落
 * {@code system_setting}；项目级：默认内核 / 默认超时 / 标签集，落 {@code project_setting}。
 *
 * <p><b>这是一张白名单表，不是通用 KV 存储。</b>键和值都先全量校验再写入，避免出现
 * 「任何登录用户都能往库里写任意字符串」的口子。每次变更写 {@code audit_log}，
 * 摘要只放 key 与前后值，不放任何凭据。
 */
@Service
public class SystemSettingService {

    public static final String K_MAX_PARALLEL = "execution.maxParallel";
    public static final String K_DEFAULT_TIMEOUT = "execution.defaultTimeoutSeconds";
    public static final String K_RETENTION_DAYS = "report.retentionDays";
    public static final String K_AI_ENABLED = "ai.enabled";
    /** 只读展示项：端口由部署脚本决定，不允许通过 API 改。 */
    public static final String K_ENTRY_PORT = "entry.port";

    public static final String P_DEFAULT_KERNEL = "kernel.default";
    public static final String P_TIMEOUT = "execution.timeoutSeconds";
    public static final String P_TAG_SET = "case.tagSet";
    /**
     * v5.14.0 / W64-09：自动生成测试报告开关。
     *
     * <p>判据逻辑（{@code ReportAutoGenerator.decide}）早就在读
     * {@code ProjectSettingRepository.findAll(projectId, "report.")} 里的
     * {@code auto_generate}，但**写入侧的白名单没有这个键**——设置页就算加了开关，
     * PUT 也会被 {@link #validateProject} 以「不支持的项目设置键」拒掉（400）。
     * 所以本版不是「只缺 UI」，而是要把键补进白名单、默认值与校验分支。
     */
    public static final String P_REPORT_AUTO_GENERATE = "report.auto_generate";

    /* v5.23.0 / FR-v5.23-01：报告元数据默认值（六项）。
     *
     * <p>复用项目设置白名单通道而不是新建表：这里本来就有项目维度、审计与权限校验，
     * 再建一张 report_metadata_default 只会产生第二套「同一项目两份设置」的真源。
     * 值仍受 MAX_VALUE_LENGTH 约束，且不放任何凭据。 */
    public static final String P_REPORT_META_QA_PROJECT = "report_meta.qaProject";
    public static final String P_REPORT_META_DUT = "report_meta.dut";
    public static final String P_REPORT_META_LINK = "report_meta.link";
    public static final String P_REPORT_META_SW_BUILD = "report_meta.swBuild";
    public static final String P_REPORT_META_BASELINE = "report_meta.baseline";
    public static final String P_REPORT_META_OPERATOR = "report_meta.operator";

    private static final Map<String, String> PLATFORM_DEFAULTS = Map.of(
            K_MAX_PARALLEL, "4",
            K_DEFAULT_TIMEOUT, "1800",
            K_RETENTION_DAYS, "90",
            K_AI_ENABLED, "false");

    private static final Map<String, String> PROJECT_DEFAULTS = Map.of(
            P_DEFAULT_KERNEL, "robot",
            P_TIMEOUT, "1800",
            P_TAG_SET, "",
            // 默认关闭：升级后不能悄悄改变既有项目的执行产物数量。
            P_REPORT_AUTO_GENERATE, "false",
            P_REPORT_META_QA_PROJECT, "",
            P_REPORT_META_DUT, "",
            P_REPORT_META_LINK, "",
            P_REPORT_META_SW_BUILD, "",
            P_REPORT_META_BASELINE, "",
            P_REPORT_META_OPERATOR, "");

    private static final Set<String> PLATFORM_KEYS = PLATFORM_DEFAULTS.keySet();
    private static final Set<String> PROJECT_KEYS = PROJECT_DEFAULTS.keySet();
    private static final int MAX_KEYS_PER_REQUEST = 10;
    private static final int MAX_VALUE_LENGTH = 255;

    private final SystemSettingRepository repository;
    private final AuditService audit;

    /** 统一入口端口只展示、不可写；由部署配置注入，缺省 8001（W1 约定）。 */
    @Value("${platform.entry-port:8001}")
    private String entryPort = "8001";

    public SystemSettingService(SystemSettingRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** 平台设置：默认值打底，库里有值则覆盖；端口单独附一个只读键。 */
    public Map<String, String> platform() {
        Map<String, String> result = new LinkedHashMap<>(PLATFORM_DEFAULTS);
        result.putAll(repository.findAllSystem());
        result.put(K_ENTRY_PORT, entryPort);
        return result;
    }

    /** 项目设置：默认值打底，库里有值则覆盖。 */
    public Map<String, String> project(long projectId) {
        Map<String, String> result = new LinkedHashMap<>(PROJECT_DEFAULTS);
        result.putAll(repository.findAllProject(projectId));
        return result;
    }

    /** 平台设置写入。权限判定在 Controller（{@code AccessControlService.isAdmin()}），这里再兜一次。 */
    public Map<String, String> putPlatform(Map<String, String> values, boolean isAdmin) {
        if (!isAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台设置仅管理员可修改");
        }
        if (values == null || values.isEmpty()) {
            return platform();
        }
        if (values.size() > MAX_KEYS_PER_REQUEST || values.containsKey(K_ENTRY_PORT)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "端口为只读展示项，且单次提交不得超过 " + MAX_KEYS_PER_REQUEST + " 项");
        }
        values.forEach(SystemSettingService::validatePlatform);

        Map<String, String> before = platform();
        Long actorId = TenantContext.userId();
        values.forEach((key, value) -> {
            repository.upsertSystem(key, value, actorId);
            audit.recordChange("system_setting:update", "system_setting", key,
                    before.get(key), value);
        });
        return platform();
    }

    /** 项目设置写入。跨项目访问由 Controller 的 {@code requireProjectAccess} 拦截。 */
    public Map<String, String> putProject(long projectId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return project(projectId);
        }
        if (values.size() > MAX_KEYS_PER_REQUEST) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单次提交不得超过 " + MAX_KEYS_PER_REQUEST + " 项");
        }
        values.forEach(SystemSettingService::validateProject);

        Map<String, String> before = project(projectId);
        Long actorId = TenantContext.userId();
        values.forEach((key, value) -> {
            repository.upsertProject(projectId, key, value, actorId);
            audit.recordChange("project_setting:update", "project_setting",
                    projectId + ":" + key, before.get(key), value);
        });
        return project(projectId);
    }

    private static void validatePlatform(String key, String value) {
        if (!PLATFORM_KEYS.contains(key)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不支持的平台设置键：" + key);
        }
        requireText(key, value);
        switch (key) {
            case K_MAX_PARALLEL -> requireInt(key, value, 1, 32);
            case K_DEFAULT_TIMEOUT -> requireInt(key, value, 1, 86_400);
            case K_RETENTION_DAYS -> requireInt(key, value, 1, 3_650);
            case K_AI_ENABLED -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "ai.enabled 只能是 true / false");
                }
            }
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "平台设置键缺少校验分支：" + key);
        }
    }

    private static void validateProject(String key, String value) {
        if (!PROJECT_KEYS.contains(key)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不支持的项目设置键：" + key);
        }
        requireText(key, value);
        switch (key) {
            case P_DEFAULT_KERNEL -> {
                if (!"robot".equals(value) && !"pytest".equals(value)) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "kernel.default 只能是 robot / pytest");
                }
            }
            case P_TIMEOUT -> requireInt(key, value, 1, 86_400);
            case P_TAG_SET -> {
                if (value.length() > MAX_VALUE_LENGTH) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "case.tagSet 超长（上限 " + MAX_VALUE_LENGTH + "）");
                }
            }
            case P_REPORT_AUTO_GENERATE -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "report.auto_generate 只能是 true / false");
                }
            }
            case P_REPORT_META_QA_PROJECT, P_REPORT_META_DUT, P_REPORT_META_LINK,
                 P_REPORT_META_SW_BUILD, P_REPORT_META_BASELINE, P_REPORT_META_OPERATOR -> {
                // 文本值：requireText 已做 null / 长度校验，这里只要求显式分支存在。
                if (value != null && value.contains("\n")) {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "报告元数据不能包含换行：" + key);
                }
            }
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "项目设置键缺少校验分支：" + key);
        }
    }

    private static void requireText(String key, String value) {
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "设置值非法或超长：" + key);
        }
    }

    private static void requireInt(String key, String value, int min, int max) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "设置值必须是整数：" + key + "=" + value);
        }
        if (parsed < min || parsed > max) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "设置值超出范围：" + key + " 允许 " + min + ".." + max);
        }
    }
}
