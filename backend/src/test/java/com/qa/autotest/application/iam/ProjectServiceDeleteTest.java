package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.iam.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 项目彻底删除的前置检查（v4.3.0 FR-v4.3-09）。
 *
 * <p>锁三件事：① 有业务数据时拒绝删除并说明卡在哪几类、各多少条；
 * ② 空项目可删且写审计；③ 项目不存在给 404 而不是静默成功。
 */
class ProjectServiceDeleteTest {

    private ProjectRepository projects;
    private AuditService audit;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        audit = mock(AuditService.class);
        service = new ProjectService(projects, mock(UserRepository.class),
                mock(AccessControlService.class), audit);
    }

    private static Project project(long id, String code, String name) {
        return new Project(id, code, name, null, true, 1L, "private", "robot");
    }

    @Test
    @DisplayName("项目下仍有用例/运行：拒绝删除并逐类报数")
    void 非空项目拒绝彻底删除() {
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "ED202", "公共用例库")));
        Map<String, Long> busy = new LinkedHashMap<>();
        busy.put("run", 8L);
        busy.put("test_case", 813L);
        when(projects.busyCounts(7L)).thenReturn(busy);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用例 813 条")
                .hasMessageContaining("运行 8 条")
                .hasMessageContaining("只能停用");

        verify(projects, never()).delete(7L);
    }

    @Test
    @DisplayName("空项目可彻底删除，并留下 code/name 审计")
    void 空项目可彻底删除() {
        when(projects.findById(9L)).thenReturn(Optional.of(project(9L, "tmp-9", "临时探针")));
        when(projects.busyCounts(9L)).thenReturn(Map.of());

        service.delete(9L);

        verify(projects).delete(9L);
        verify(audit).recordChange(eq("project:delete"), eq("project"), eq("9"),
                eq("code=tmp-9; name=临时探针"), isNull());
    }

    @Test
    @DisplayName("项目不存在返回 404，不执行删除")
    void 项目不存在返回404() {
        when(projects.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目不存在");

        verify(projects, never()).delete(404L);
    }
}
