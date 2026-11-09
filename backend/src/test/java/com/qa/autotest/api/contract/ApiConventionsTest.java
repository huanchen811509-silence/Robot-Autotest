package com.qa.autotest.api.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.common.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EW32-08 · 跨端点约定：错误码 / HTTP 状态 / 分页字段 / 时间序列化 / 枚举。
 *
 * <p>这些约定一旦漂移，前端就要为每个端点写特判；用测试钉住，避免「某天某个接口
 * 悄悄返回 500 或 camelCase 时间」。
 */
class ApiConventionsTest {

    @Test
    @DisplayName("错误码：数值唯一、段位正确、name 只含大写字母与下划线")
    void errorCodesAreStable() {
        Set<Integer> codes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::code)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> names = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::codeName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(codes).hasSize(ErrorCode.values().length);
        assertThat(names).hasSize(ErrorCode.values().length);
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.codeName()).matches("[A-Z][A-Z0-9_]*");
            if (code == ErrorCode.SUCCESS) {
                assertThat(code.code()).isZero();
            } else if (code.code() / 10000 == 4) {
                assertThat(code.code()).isBetween(40000, 49999);
            } else {
                assertThat(code.code()).isBetween(50000, 59999);
            }
        }
    }

    @Test
    @DisplayName("错误码 → HTTP 状态：每个业务错误码都有确定映射，不落兜底 500")
    void businessErrorsMapToHttpStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Map<ErrorCode, HttpStatus> expected = Map.of(
                ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
                ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
                ErrorCode.SAFETY_LEVEL_BLOCKED, HttpStatus.FORBIDDEN,
                ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                ErrorCode.CONFLICT, HttpStatus.CONFLICT,
                ErrorCode.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);

        expected.forEach((code, status) -> {
            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleBusiness(new BusinessException(code, "boom"));
            assertThat(response.getStatusCode())
                    .as("错误码 %s", code.codeName())
                    .isEqualTo(status);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(code.code());
            assertThat(response.getBody().codeName()).isEqualTo(code.codeName());
        });
    }

    @Test
    @DisplayName("分页信封：items/total/page/size 四个字段名固定")
    void pageResultFields() throws Exception {
        String json = Jackson2ObjectMapperBuilder.json().build()
                .writeValueAsString(new PageResult<>(List.of("a"), 1, 2, 20));
        assertThat(json).contains("\"items\"").contains("\"total\"").contains("\"page\"").contains("\"size\"");
        assertThat(json).contains("\"page\":2").contains("\"size\":20");
    }

    @Test
    @DisplayName("时间统一 ISO-8601（UTC，带 Z），不是 epoch 数字")
    void instantIsIso8601() throws Exception {
        // 与 Spring Boot JacksonAutoConfiguration 的默认行为一致：
        // Boot 通过 Jackson2ObjectMapperBuilderCustomizer 关闭 WRITE_DATES_AS_TIMESTAMPS。
        // 这里显式关闭，避免测试用的裸 builder 与容器内 ObjectMapper 行为不一致。
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        String json = mapper.writeValueAsString(
                Map.of("at", Instant.parse("2026-11-08T01:02:03Z")));
        assertThat(json).contains("2026-11-08T01:02:03Z");
        assertThat(json).doesNotContain("1794099723");
    }
}
