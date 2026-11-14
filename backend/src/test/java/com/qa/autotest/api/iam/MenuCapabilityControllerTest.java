package com.qa.autotest.api.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * OPT-08 / FR-49-32/33：服务端按登录角色下发能力数组，且敏感能力由
 * {@code AccessControlService.requireCapability} 在接口层 403 兜底（不靠前端隐藏）。
 */
class MenuCapabilityControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        AccessControlService access = new AccessControlService(users, projects);
        mockMvc = MockMvcBuilders.standaloneSetup(new MenuCapabilityController(access))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void asRole(long userId, String... roleCodes) {
        when(users.roleCodesOf(anyLong())).thenReturn(List.of(roleCodes));
        TenantContext.set(userId, "user-" + userId, null);
    }

    private List<String> capabilities() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/me/menu-capabilities")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":0");
        JsonNode data = MAPPER.readTree(body).get("data");
        assertThat(data.isArray()).isTrue();
        List<String> caps = new ArrayList<>();
        for (JsonNode node : data) {
            caps.add(node.asText());
        }
        return caps;
    }

    @Test
    @DisplayName("ADMIN：7 个能力全下发（含 ai:config/user:write/audit:read/case:write）")
    void adminCapabilities() throws Exception {
        asRole(1L, "admin");
        assertThat(capabilities()).containsExactly("settings:read", "ai:config", "user:write",
                "audit:read", "about", "changelog", "case:write");
    }

    @Test
    @DisplayName("PM：audit:read 与 case:write 有，ai:config/user:write 无")
    void pmCapabilities() throws Exception {
        asRole(2L, "pm");
        assertThat(capabilities()).containsExactly("settings:read", "audit:read", "about",
                "changelog", "case:write");
    }

    @Test
    @DisplayName("ENGINEER：settings:read/about/changelog + case:write")
    void engineerCapabilities() throws Exception {
        asRole(3L, "engineer");
        assertThat(capabilities()).containsExactly("settings:read", "about", "changelog", "case:write");
    }

    @Test
    @DisplayName("VIEWER：仅 settings:read/about/changelog")
    void viewerCapabilities() throws Exception {
        asRole(4L, "viewer");
        assertThat(capabilities()).containsExactly("settings:read", "about", "changelog");
    }

    // ------------------------------------------------------------------ 服务端逐页鉴权（FR-49-33）

    @Test
    @DisplayName("engineer 访问 admin 类能力 ai:config/user:write/audit:read 一律 403")
    void engineerDeniedAdminCapabilities() throws Exception {
        asRole(3L, "engineer");
        for (String cap : List.of("ai:config", "user:write", "audit:read")) {
            MvcResult result = mockMvc.perform(get("/api/me/menu-capabilities/check")
                    .param("capability", cap)).andReturn();
            assertEquals(403, result.getResponse().getStatus(), "engineer 不该拥有 " + cap);
        }
    }

    @Test
    @DisplayName("viewer 访问 AI 配置能力 ai:config 被拒 403")
    void viewerDeniedAiConfig() throws Exception {
        asRole(4L, "viewer");
        MvcResult result = mockMvc.perform(get("/api/me/menu-capabilities/check")
                .param("capability", "ai:config")).andReturn();
        assertEquals(403, result.getResponse().getStatus());
    }

    @Test
    @DisplayName("ADMIN 命中敏感能力 200；PM 命中 audit:read 200、ai:config 403")
    void allowedAndDeniedMix() throws Exception {
        asRole(1L, "admin");
        assertEquals(200, check("ai:config"));
        assertEquals(200, check("user:write"));
        assertEquals(200, check("audit:read"));

        asRole(2L, "pm");
        assertEquals(200, check("audit:read"));
        assertEquals(403, check("ai:config"));
    }

    @Test
    @DisplayName("未知 capability 返回 400，不静默放行")
    void unknownCapabilityIsBadRequest() throws Exception {
        asRole(1L, "admin");
        assertEquals(400, check("not:a:capability"));
    }

    private int check(String capability) throws Exception {
        return mockMvc.perform(get("/api/me/menu-capabilities/check")
                .param("capability", capability)).andReturn().getResponse().getStatus();
    }
}
