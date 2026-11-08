package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.qa.autotest.common.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-AI-07：ai.enabled=false 时服务明确禁用；FEATURE_AI_REAL_CLI 默认关闭。 */
class AiEnabledFlagTest {

    private static AiPanelService service(AiFeatureGate gate) {
        return AiTestSupport.service(AiTestSupport.stubProbe(false), gate,
                new AiProcessManager(), null);
    }

    @Test
    void disabledGateThrowsWithExplicitMessage() {
        AiPanelService service = service(AiFeatureGate.disabled());
        assertThatThrownBy(() -> service.open(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 助手已禁用");
        assertThatThrownBy(() -> service.suggest(1L, Map.of("k", "v")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 助手已禁用");
        assertThatThrownBy(() -> service.chat(1L, 1L, "hi"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 助手已禁用");
    }

    @Test
    void stateReportsDisabledWithoutThrowing() {
        Map<String, Object> state = service(AiFeatureGate.disabled()).state(1L);
        assertThat(state).containsEntry("enabled", false);
        assertThat(state).containsEntry("mode", "DISABLED");
        assertThat(state).containsEntry("realCliEnabled",
                AiFeatureGate.realCliFeatureEnabled());
    }

    @Test
    void permissiveGateAllowsActions() {
        AiPanelService service = service(AiFeatureGate.permissive());
        assertThat(service.state(1L)).containsEntry("enabled", true);
        assertDoesNotThrow(() -> service.evaluate(1L, 1, 0));
    }

    @Test
    void realCliFeatureIsOffUnlessExplicitlyEnabled() {
        Runnable restore = AiTestSupport.applyProperties(
                Map.of("FEATURE_AI_REAL_CLI", "false"));
        try {
            assertThat(AiFeatureGate.realCliFeatureEnabled()).isFalse();
            AiProcessResult result = new AiProcessManager().execute("claude", "x");
            assertThat(result.executed()).isFalse();
            assertThat(result.handle()).isNull();
        } finally {
            restore.run();
        }
    }

    @Test
    void rawSettingSupplierDecidesEnabled() {
        assertThat(new AiFeatureGate(() -> "true").enabled()).isTrue();
        assertThat(new AiFeatureGate(() -> "false").enabled()).isFalse();
        assertThat(new AiFeatureGate(() -> null).enabled()).isFalse();
    }
}
