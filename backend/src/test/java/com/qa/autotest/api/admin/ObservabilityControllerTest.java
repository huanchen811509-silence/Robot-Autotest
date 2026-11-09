package com.qa.autotest.api.admin;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.caseasset.CapabilityCache;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.async.AsyncProperties;
import com.qa.autotest.common.async.BoundedExecutor;
import com.qa.autotest.common.cache.CacheProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EW32-07 · 看板 v1：仅管理员可读，字段齐全。 */
class ObservabilityControllerTest {

    private final BoundedExecutor executor =
            new BoundedExecutor(new AsyncProperties());

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    private MockMvc mvc(boolean admin) {
        AccessControlService access = mock(AccessControlService.class);
        when(access.isAdmin()).thenReturn(admin);
        CacheProperties props = new CacheProperties();
        props.setMaxEntries(8);
        return MockMvcBuilders.standaloneSetup(
                        new ObservabilityController(access, new CapabilityCache(props), executor))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("非管理员：403 FORBIDDEN，不泄漏其他租户负载")
    void nonAdminForbidden() throws Exception {
        mvc(false).perform(get("/api/admin/observability/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codeName").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("管理员：返回缓存/异步/慢查询/运行时长四组指标")
    void adminSeesSummary() throws Exception {
        mvc(true).perform(get("/api/admin/observability/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cache.hits").exists())
                .andExpect(jsonPath("$.data.cache.hitRate").exists())
                .andExpect(jsonPath("$.data.async.queued").exists())
                .andExpect(jsonPath("$.data.async.rejected").exists())
                .andExpect(jsonPath("$.data.slowQuery.measured").exists())
                .andExpect(jsonPath("$.data.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.data.generatedAt").exists());
    }
}
