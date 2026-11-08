package com.qa.autotest.api.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * v2.6 / FR-AI-14：{@code ChatRequest} 增加 cli / model 两个可选字段后的**兼容性**守门。
 *
 * <p>为什么单开一条测试：记录型（record）反序列化对「缺字段」很敏感——
 * 一旦 Jackson 认为构造器不唯一或必需字段缺失，整个 {@code POST /api/ai/chat} 会 400，
 * 而这类问题**单测跑不到**（单测直接 new 对象），只有真 HTTP 才暴露。
 * 这里用真实 ObjectMapper 把「老请求体」和「新请求体」都过一遍，锁住兼容性。
 *
 * <p>注意 `ObjectMapper` 默认对 record 缺少的属性按 null 绑定；本测试正是要证明这一点，
 * 因此不注册任何特殊模块，与 Spring Boot 的默认行为保持一致。
 */
class AiChatRequestBindingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyThreeFieldBodyStillBinds() throws Exception {
        AiPanelController.ChatRequest request = json.readValue(
                "{\"projectId\":7,\"sessionId\":12,\"prompt\":\"你好\"}",
                AiPanelController.ChatRequest.class);

        assertThat(request.projectId()).isEqualTo(7L);
        assertThat(request.sessionId()).isEqualTo(12L);
        assertThat(request.prompt()).isEqualTo("你好");
        // 老调用方不传这两个字段：必须落成 null，走「用首选 agent + 已保存模型」的老路径
        assertThat(request.cli()).isNull();
        assertThat(request.model()).isNull();
    }

    @Test
    void newBodyBindsAgentAndModelOverrides() throws Exception {
        AiPanelController.ChatRequest request = json.readValue(
                "{\"projectId\":7,\"sessionId\":12,\"prompt\":\"hi\","
                        + "\"cli\":\"pi\",\"model\":\"ark/deepseek-v4-flash\"}",
                AiPanelController.ChatRequest.class);

        assertThat(request.cli()).isEqualTo("pi");
        assertThat(request.model()).isEqualTo("ark/deepseek-v4-flash");
    }
}
