package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.Project;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-ORIGIN-04：项目来源切换的两段式校验与审计。 */
class ProjectServiceOriginTest {

    private ProjectRepository projects;
    private AccessControlService access;
    private AuditService audit;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        service = new ProjectService(projects, mock(UserRepository.class), access, audit);
    }

    private static Project project(long id, String code, String origin, String kind) {
        return new Project(id, code, "项目", null, true, 1L, "private", "robot",
                kind, null, origin);
    }

    @Test
    @DisplayName("ADMIN + 代码匹配：切换成功并审计旧值→新值")
    void 切换成功并审计() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L))
                .thenReturn(Optional.of(project(157L, "PRJ-PYT-202611-003", "USER", "standard")))
                .thenReturn(Optional.of(project(157L, "PRJ-PYT-202611-003", "BUILTIN", "standard")));

        Project result = service.changeOrigin(157L, "BUILTIN", "PRJ-PYT-202611-003");

        assertThat(result.origin()).isEqualTo("BUILTIN");
        verify(projects).setOrigin(157L, "BUILTIN");
        verify(audit).recordChange(org.mockito.ArgumentMatchers.eq("project.origin.change"),
                anyString(), anyString(), org.mockito.ArgumentMatchers.contains("USER"),
                org.mockito.ArgumentMatchers.contains("BUILTIN"));
    }

    @Test
    @DisplayName("非 ADMIN 切换：403")
    void 非管理员拒绝() {
        when(access.isAdmin()).thenReturn(false);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "X", "USER", "standard")));

        assertThatThrownBy(() -> service.changeOrigin(157L, "BUILTIN", "X"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("代码不匹配：400 且不写库")
    void 代码不匹配拒绝() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "REAL-CODE", "USER", "standard")));

        assertThatThrownBy(() -> service.changeOrigin(157L, "BUILTIN", "WRONG"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    @DisplayName("platform 项目切换：409")
    void 平台项目拒绝() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(88L)).thenReturn(Optional.of(project(88L, "PLATFORM-LIB", "BUILTIN", "platform")));

        assertThatThrownBy(() -> service.changeOrigin(88L, "USER", "PLATFORM-LIB"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台项目");
    }

    @Test
    @DisplayName("非法来源值：400")
    void 非法来源拒绝() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "X", "USER", "standard")));

        assertThatThrownBy(() -> service.changeOrigin(157L, "ROOT", "X"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("USER 或 BUILTIN");
    }
}
