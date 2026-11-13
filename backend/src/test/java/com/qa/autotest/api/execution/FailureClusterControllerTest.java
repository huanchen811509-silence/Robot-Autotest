package com.qa.autotest.api.execution;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.execution.FailureClusterService;
import com.qa.autotest.application.execution.FailureClusterView;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** W5 跨 run 聚类接口：runs 默认 5，越界走统一错误体。 */
class FailureClusterControllerTest {

    private FailureClusterService clusters;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        clusters = mock(FailureClusterService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FailureClusterController(clusters))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/failure-clusters/recent 不带 runs 时默认 5")
    void recentDefaultsToFive() throws Exception {
        when(clusters.clusterRecent(29L, 5)).thenReturn(view("recent:5", 2));

        mvc.perform(get("/api/failure-clusters/recent").param("projectId", "29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("recent:5"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.classes[0].failureClass").value("ASSERT"))
                .andExpect(jsonPath("$.data.classes[0].clusters[0].count").value(2));

        verify(clusters).clusterRecent(29L, 5);
    }

    @Test
    @DisplayName("runs 越界 → 400 INVALID_ARGUMENT")
    void runsOutOfRangeIs400() throws Exception {
        when(clusters.clusterRecent(29L, 99)).thenThrow(
                new BusinessException(ErrorCode.INVALID_ARGUMENT, "runs 取值范围 1..20"));

        mvc.perform(get("/api/failure-clusters/recent")
                        .param("projectId", "29").param("runs", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"));
    }

    private static FailureClusterView view(String runId, int total) {
        return new FailureClusterView(runId, total, null, null,
                List.of(new FailureClusterView.ClassGroup("ASSERT", total, 1.0,
                        List.of(new FailureClusterView.Cluster(
                                "AssertionError: <n> != <n>",
                                "AssertionError: PFC 使能优先级期望 3 实际 5",
                                total, 1.0, List.of(), List.of(1L, 2L))))));
    }
}
