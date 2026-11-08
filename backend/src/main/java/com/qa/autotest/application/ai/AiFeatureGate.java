package com.qa.autotest.application.ai;

import com.qa.autotest.application.system.SystemSettingService;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI 双闸门（FR-AI-07 / 硬约束）。
 *
 * <p>闸门一：{@code ai.enabled}（SystemSettingService.platform()，默认 false）。
 * 关闭时所有 AI 动作抛 BusinessException「AI 助手已禁用」；前端按 /api/ai/state
 * 返回的 enabled 隐藏入口。
 *
 * <p>闸门二：{@code FEATURE_AI_REAL_CLI}（环境变量 / 系统属性，默认关闭）。
 * 只有显式开启才允许起子进程；开发期即使开启也只接 fixtures/ai/fake-cli.sh。
 */
@Component
public final class AiFeatureGate {

    public static final String K_AI_ENABLED = SystemSettingService.K_AI_ENABLED;
    public static final String ENV_REAL_CLI = "FEATURE_AI_REAL_CLI";

    private final Supplier<String> rawEnabled;

    @Autowired
    public AiFeatureGate(SystemSettingService settings) {
        this(() -> settings.platform().getOrDefault(K_AI_ENABLED, "false"));
    }

    /** 测试可注入原始设置值，无需真实数据库。 */
    public AiFeatureGate(Supplier<String> rawEnabled) {
        this.rawEnabled = rawEnabled;
    }

    public boolean enabled() {
        String raw = rawEnabled.get();
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    /** 单测 / 无 DB 场景：放行 ai.enabled；是否起子进程仍由 ENV_REAL_CLI 决定。 */
    public static AiFeatureGate permissive() {
        return new AiFeatureGate(() -> "true");
    }

    public static AiFeatureGate disabled() {
        return new AiFeatureGate(() -> "false");
    }

    /** 真实子进程开关；系统属性优先于环境变量，默认 false。 */
    public static boolean realCliFeatureEnabled() {
        String value = System.getProperty(ENV_REAL_CLI);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV_REAL_CLI);
        }
        return value != null && "true".equalsIgnoreCase(value.trim());
    }
}
