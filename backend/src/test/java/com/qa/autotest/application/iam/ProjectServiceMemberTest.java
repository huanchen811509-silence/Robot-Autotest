package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** v4.4.0 项目成员与编码治理单测（FR-v4.4-03/04/05/07/08）。 */
class ProjectServiceMemberTest {

    private ProjectRepository projects;
    private UserRepository users;
    private AccessControlService access;
    private AuditService audit;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        users = mock(UserRepository.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        service = new ProjectService(projects, users, access, audit);
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Project project(long id, String code, LocalDateTime codeChangedAt) {
        return new Project(id, code, "项目" + id, null, true, 2L, "private", "robot",
                "standard", codeChangedAt);
    }

    private static ProjectMember member(long projectId, long userId, String... roles) {
        return new ProjectMember(1L, projectId, userId, "u" + userId, roles[0], "用户" + userId,
                true, List.of(roles));
    }

    @Test
    @DisplayName("多角色添加：主角色按优先级归一，角色集合完整落库")
    void 多角色添加归一主角色() {
        when(users.findById(3L)).thenReturn(Optional.of(new AppUser(3L, "u3", "用户3", null,
                "x", null, true, null, 0, null, Instant.now())));
        when(projects.findMember(7L, 3L)).thenReturn(Optional.empty(),
                Optional.of(member(7L, 3L, "engineer", "pm")));

        ProjectMember saved = service.addMember(7L, 3L, List.of("engineer", "pm", "engineer"));

        verify(projects).replaceMemberRoles(eq(7L), eq(3L), eq(List.of("pm", "engineer")),
                eq("pm"), eq(1L));
        assertThat(saved.roleCodes()).containsExactly("engineer", "pm");
    }

    @Test
    @DisplayName("最后一名 PM 降权返回 409 LAST_PM_REQUIRED（FR-v4.4-05）")
    void 最后一名经理不可降权() {
        when(projects.findMember(7L, 3L)).thenReturn(Optional.of(member(7L, 3L, "pm")));
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "P-1", null)));
        when(projects.countManagers(7L)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateMemberRoles(7L, 3L, List.of("viewer")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.LAST_PM_REQUIRED);
        verify(projects, never()).replaceMemberRoles(anyLong(), anyLong(), any(), any(),
                anyLong());
    }

    @Test
    @DisplayName("最后一名 PM 直接移除同样被拒绝")
    void 最后一名经理不可移除() {
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "P-1", null)));
        when(projects.findMember(7L, 3L)).thenReturn(Optional.of(member(7L, 3L, "pm")));
        when(projects.countManagers(7L)).thenReturn(1L);

        assertThatThrownBy(() -> service.removeMember(7L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LAST_PM_REQUIRED");
        verify(projects, never()).removeMember(7L, 3L);
    }

    @Test
    @DisplayName("批量添加：未知用户逐人失败，已存在用户幂等跳过，不影响其他人")
    void 批量添加逐人失败() {
        when(users.findById(3L)).thenReturn(Optional.of(new AppUser(3L, "u3", "用户3", null,
                "x", null, true, null, 0, null, Instant.now())));
        when(projects.findMember(7L, 3L)).thenReturn(Optional.empty(),
                Optional.of(member(7L, 3L, "engineer")));
        when(users.findById(4L)).thenReturn(Optional.empty());

        var result = service.addMembers(7L, List.of(
                new ProjectService.MemberInput(3L, null, List.of("engineer")),
                new ProjectService.MemberInput(4L, null, List.of("viewer")),
                new ProjectService.MemberInput(3L, null, List.of("pm"))));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).extracting(ProjectService.MemberFailure::userId)
                .containsExactly(4L);
    }

    @Test
    @DisplayName("next-code：原子序号连续，不重复（FR-v4.4-07）")
    void 建议编码序号递增() {
        when(projects.nextCodeSequence(any())).thenReturn(1L, 2L);
        when(projects.codeExists(any(), eq(null))).thenReturn(false);

        String first = service.nextCode("ed202", "robot");
        String second = service.nextCode("ed202", "pytest");

        assertThat(first).matches("ED202-RF-\\d{6}-001");
        assertThat(second).matches("ED202-PYT-\\d{6}-002");
    }

    @Test
    @DisplayName("编码只能变更一次（FR-v4.4-08）：第二次直接 409")
    void 编码只能变更一次() {
        when(access.isAdmin(1L)).thenReturn(true);
        when(projects.findById(7L)).thenReturn(
                Optional.of(project(7L, "OLD-1", LocalDateTime.now())));

        assertThatThrownBy(() -> service.changeCode(7L, "NEW-1", "业务调整"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CODE_ALREADY_CHANGED);
        verify(projects, never()).changeCode(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("首次改码：校验原因、写审计并返回新编码")
    void 首次改码成功并审计() {
        when(access.isAdmin(1L)).thenReturn(true);
        when(projects.findById(7L)).thenReturn(
                Optional.of(project(7L, "OLD-1", null)),
                Optional.of(project(7L, "NEW-1", LocalDateTime.now())));
        when(projects.codeExists("NEW-1", 7L)).thenReturn(false);
        when(projects.changeCode(7L, "NEW-1", "业务调整", 1L)).thenReturn(true);

        Project changed = service.changeCode(7L, "NEW-1", "业务调整");

        assertThat(changed.code()).isEqualTo("NEW-1");
        verify(audit).recordChange("project.code.change", "project", "7",
                "code=OLD-1", "code=NEW-1; reason=业务调整");
    }

    @Test
    @DisplayName("非管理员改码被拒绝")
    void 非管理员不能改码() {
        when(access.isAdmin(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.changeCode(7L, "NEW-1", "业务调整"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
