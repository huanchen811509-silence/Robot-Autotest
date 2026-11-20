package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.iam.Project;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-ORIGIN-06：级联删除的前置校验、级联调用与孤儿回滚。 */
class ProjectPurgeServiceTest {

    private ProjectRepository projects;
    private AccessControlService access;
    private AuditService audit;
    private ProjectPurgeService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        service = new ProjectPurgeService(projects, access, audit);
    }

    private static Project project(long id, String code, String origin, String kind) {
        return new Project(id, code, "项目", null, true, 1L, "private", "robot",
                kind, null, origin);
    }

    @Test
    @DisplayName("USER 项目：级联删除成功、孤儿扫描为空、写审计")
    void userProjectPurged() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "PRJ-PYT", "USER", "standard")));
        when(projects.purgeTargets(157L)).thenReturn(Map.of("test_case", 69L, "test_set", 1L));
        when(projects.orphanReferences(157L)).thenReturn(Map.of());

        service.purge(157L);

        verify(projects).purgeProject(157L);
        verify(audit).recordChange(org.mockito.ArgumentMatchers.eq("project:purge"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("PRJ-PYT"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("孤儿残留：抛异常阻断，级联已调用但事务由上层回滚")
    void orphanBlocksSuccess() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "PRJ-PYT", "USER", "standard")));
        when(projects.purgeTargets(157L)).thenReturn(Map.of());
        when(projects.orphanReferences(157L)).thenReturn(Map.of("run", 3L));

        assertThatThrownBy(() -> service.purge(157L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("孤儿");
    }

    @Test
    @DisplayName("BUILTIN 项目：拒绝级联删除（只能停用）")
    void builtinRejected() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(77L)).thenReturn(Optional.of(project(77L, "ED202", "BUILTIN", "standard")));

        assertThatThrownBy(() -> service.purge(77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("程序自带");
        verify(projects, never()).purgeProject(anyLong());
    }

    @Test
    @DisplayName("platform 项目：拒绝")
    void platformRejected() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(88L)).thenReturn(Optional.of(project(88L, "PLATFORM-LIB", "BUILTIN", "platform")));

        assertThatThrownBy(() -> service.purge(88L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台项目");
        verify(projects, never()).purgeProject(anyLong());
    }

    @Test
    @DisplayName("非 ADMIN：403，不做任何级联")
    void nonAdminRejected() {
        when(access.isAdmin()).thenReturn(false);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "X", "USER", "standard")));

        assertThatThrownBy(() -> service.purge(157L)).isInstanceOf(BusinessException.class);
        verify(projects, never()).purgeProject(anyLong());
    }

    @Test
    @DisplayName("预览为只读：不做级联、返回逐表条数")
    void previewReadOnly() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.findById(157L)).thenReturn(Optional.of(project(157L, "X", "USER", "standard")));
        when(projects.purgeTargets(157L)).thenReturn(Map.of("test_case", 69L));

        Map<String, Long> result = service.preview(157L);

        assertThat(result).containsEntry("test_case", 69L);
        verify(projects, never()).purgeProject(anyLong());
    }
}
