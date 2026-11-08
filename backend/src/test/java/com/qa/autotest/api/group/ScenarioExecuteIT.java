package com.qa.autotest.api.group;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.execution.RunExecutionService;
import com.qa.autotest.application.group.EffectiveRoleResolver;
import com.qa.autotest.application.group.GroupService;
import com.qa.autotest.application.group.ScenarioService;
import com.qa.autotest.application.group.SuperAdminViewService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SCEN-03 接口层验收：{@code POST /api/scenarios/{id}/execute} 不再是 404。
 *
 * <p><b>为什么用 standaloneSetup 而不是真库</b>：本包要证明的是「路由存在 + 每次调用都走
 * 一次 run 创建 + 返回体带 runId」，这三件事都发生在 MVC 与控制器这一层；{@code runId}
 * 的<b>唯一性生成</b>由 {@code RunIdGeneratorTest}（同秒单调序列）覆盖，跑库只会把同一条
 * 判据挪到更慢的地方。用真实 {@link GlobalExceptionHandler}，断言的是线上同一套异常映射。
 *
 * <p>注意：本类名按 DEV-PLAN §3.2 的验收命令固定为 {@code ScenarioExecuteIT}；它<b>不连库</b>，
 * 因此不加 {@code @Tag("it")}，会随门禁第 05 步（{@code mvn -o -B test}）一起执行。
 */
class ScenarioExecuteIT {

    private RunExecutionService executions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        executions = mock(RunExecutionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new GroupController(
                        mock(GroupService.class),
                        mock(ScenarioService.class),
                        mock(SuperAdminViewService.class),
                        mock(EffectiveRoleResolver.class),
                        mock(AccessControlService.class),
                        executions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("路由已注册（不再 404）+ 两次触发返回两个不同 runId")
    void 场景执行不再404且返回独立runId() throws Exception {
        when(executions.executeScenario(7L, 5L))
                .thenReturn(run(1001L, "RUN-SCEN-A"))
                .thenReturn(run(1002L, "RUN-SCEN-B"));

        // 路由缺失时 standalone MockMvc 会回 404，因此 200 本身就是「不再 404」的机检。
        mvc.perform(post("/api/scenarios/5/execute").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("RUN-SCEN-A"))
                .andExpect(jsonPath("$.data.scenarioId").value(5))
                .andExpect(jsonPath("$.data.projectId").value(7));

        mvc.perform(post("/api/scenarios/5/execute").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-SCEN-B"));

        verify(executions, times(2)).executeScenario(7L, 5L);
    }

    @Test
    @DisplayName("未绑定项目上下文且未传 projectId：明确报错而不是落一个 projectId=0 的 run")
    void 缺项目上下文时明确拒绝() throws Exception {
        mvc.perform(post("/api/scenarios/5/execute"))
                .andExpect(status().isBadRequest());

        verify(executions, times(0)).executeScenario(anyLong(), anyLong());
    }

    private static RunRecord run(long id, String runId) {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        return new RunRecord(id, runId, 7L, null, RunState.QUEUED, 0, 1, 1,
                0, 0, 0, null, null, null, null, now, null, null, null);
    }
}
