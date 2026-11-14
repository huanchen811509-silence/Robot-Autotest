package com.qa.autotest.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 请求体「未知字段必须判 400」这条守门的地基。
 *
 * <p>背景（2026-11-14 复核 P0）：Spring Boot 默认把 {@code FAIL_ON_UNKNOWN_PROPERTIES} 关掉，
 * 于是前端字段名写错（`token` / `setIds` / `caseId`）会被**静默丢弃**——接口 200、数据不对，
 * 三处 P0 就是这么逃过 1899 条单测的。实测还证实：在 record 上写
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} **无效**，它只是「不忽略」，
 * 仍落回全局开关，而全局是 false。
 *
 * <p>所以真正生效的只有 {@code spring.jackson.deserialization.fail-on-unknown-properties: true}。
 * 它是配置而非代码，删掉不会有任何编译或单测告警，因此这里钉一条断言：
 * 配置被人误删时这条先红，而不是等下一次线上「保存成功但没生效」。
 */
class JacksonStrictBodyConfigTest {

    @Test
    @DisplayName("application.yml 必须打开 fail-on-unknown-properties")
    @SuppressWarnings("unchecked")
    void strictUnknownPropertiesEnabled() throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("类路径上找不到 application.yml").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Object parsed = new Yaml().load(yaml);
        assertThat(parsed).as("application.yml 顶层必须是映射").isInstanceOf(java.util.Map.class);

        Object spring = ((java.util.Map<String, Object>) parsed).get("spring");
        assertThat(spring).as("缺少 spring 段").isInstanceOf(java.util.Map.class);

        Object jackson = ((java.util.Map<String, Object>) spring).get("jackson");
        assertThat(jackson).as("缺少 spring.jackson 段——请求体字段写错会重新变成静默丢弃")
                .isInstanceOf(java.util.Map.class);

        Object deserialization = ((java.util.Map<String, Object>) jackson).get("deserialization");
        assertThat(deserialization).as("缺少 spring.jackson.deserialization 段")
                .isInstanceOf(java.util.Map.class);

        Object flag = ((java.util.Map<String, Object>) deserialization).get("fail-on-unknown-properties");
        assertThat(String.valueOf(flag))
                .as("fail-on-unknown-properties 必须为 true；false 或缺省会让写错字段名的请求照样 200")
                .isEqualTo("true");
    }
}
