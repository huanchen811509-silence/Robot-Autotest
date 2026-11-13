package com.qa.autotest.api.iam;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.iam.ProjectService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.project.ProjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** EHT-05 / FR-v5.5-02：HTTP 层必须能把 kind 传到 6 参 create，且非法值不静默回落。 */
class ProjectControllerTest {

    private static final String BASE = "{\"code\":\"EHT-PYTEST-LIB\","
            + "\"name\":\"EHT pytest 用例库工程\",\"visibility\":\"private\","
            + "\"testKernel\":\"pytest\"";
    private static final String WITH_KIND = BASE + ",\"kind\":\"pytest-suite\"}";
    private static final String NO_KIND = BASE + "}";
    private static final String BAD_KIND = BASE + ",\"kind\":\"pytest-eht\"}";

    private MockMvc mvc(ProjectService projects) {
        return MockMvcBuilders.standaloneSetup(new ProjectController(projects))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ProjectService stub() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.create(any(), any(), any(), any(), any(), any())).thenReturn(null);
        return projects;
    }

    @Test
    @DisplayName("带 kind=pytest-suite：原样传给 6 参 create")
    void createWithKind() throws Exception {
        ProjectService projects = stub();
        mvc(projects).perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(WITH_KIND)).andExpect(status().isOk());
        verify(projects).create(eq("EHT-PYTEST-LIB"), eq("EHT pytest 用例库工程"), isNull(),
                eq("private"), eq("pytest"), eq("pytest-suite"));
    }

    @Test
    @DisplayName("不带 kind：仍走 6 参，kind 为 null（由 ProjectKind.of 回落 standard）")
    void createWithoutKind() throws Exception {
        ProjectService projects = stub();
        mvc(projects).perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(NO_KIND)).andExpect(status().isOk());
        verify(projects).create(eq("EHT-PYTEST-LIB"), eq("EHT pytest 用例库工程"), isNull(),
                eq("private"), eq("pytest"), isNull());
    }

    @Test
    @DisplayName("非法 kind：业务异常映射成 400，不静默回落 standard")
    void illegalKindIsBadRequest() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        when(projects.create(any(), any(), any(), any(), any(), eq("pytest-eht")))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "未知的工程类型: pytest-eht"));
        mvc(projects).perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(BAD_KIND)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("D-A=A：pytest-eht 不是合法 kind 值")
    void ehtIsNotAKind() {
        assertThrows(IllegalArgumentException.class, () -> ProjectKind.of("pytest-eht"));
    }
}
