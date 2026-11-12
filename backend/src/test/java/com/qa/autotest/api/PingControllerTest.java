package com.qa.autotest.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.application.system.BuildInfoService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一响应体与异常映射的单测（TC 对应 FR-v0.1-06）。
 *
 * <p>用 standaloneSetup，不起 Spring 容器、不连库——v0.1 的 L1 单测必须在任何机器上秒级可跑。
 */
class PingControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PingController(new BuildInfoService()), new BoomController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("/api/_ping 返回统一结构且状态 UP")
    void ping_返回统一结构() throws Exception {
        mockMvc.perform(get("/api/_ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.codeName").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.app").value("robot-autotest"))
                .andExpect(jsonPath("$.data.version").isNotEmpty());
    }

    @Test
    @DisplayName("/api/_ping 版本号来自构建产物，不再硬编码 v0.1")
    void ping_版本来自构建产物() throws Exception {
        String expected = new BuildInfoService().info().get("version").toString();
        mockMvc.perform(get("/api/_ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(expected));
    }

    @Test
    @DisplayName("业务异常映射为对应 HTTP 状态且保留错误码")
    void 业务异常映射() throws Exception {
        mockMvc.perform(get("/api/_boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.codeName").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("对象不存在"));
    }

    @Test
    @DisplayName("未预期异常兜底成 500 且不泄漏堆栈")
    void 未预期异常兜底() throws Exception {
        mockMvc.perform(get("/api/_boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.message").value("服务内部错误"));
    }

    /** 只为触发异常分支的测试控制器，不进生产代码。 */
    @RestController
    static class BoomController {

        @GetMapping("/api/_boom/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "对象不存在");
        }

        @GetMapping("/api/_boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom");
        }
    }
}
