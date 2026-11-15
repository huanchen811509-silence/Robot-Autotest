package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.iam.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.9.0 判权与归属基座单测（FR-v5.9-04 / 06 / 08）。
 *
 * <p>三条都是**行为级**断言，不是「实现调了哪个方法」的断言：
 * <ul>
 *   <li>组授权必须真的进有效角色（此前只读 project_member_role，组授权形同装饰）；</li>
 *   <li>资源级校验与列表口径同源（负责人 / 组授权都要放行，否则「列表能看到、点开 403」）；</li>
 *   <li>重复加成员必须幂等（旧实现抛 409，批量勾选里混一个老成员就整批失败）。</li>
 * </ul>
 */
class AccessControlVisibilityTest {

    private ProjectRepository projects;
    private UserRepository users;
    private AccessControlService access;
    private ProjectService service;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        users = mock(UserRepository.class);
        access = new AccessControlService(users, projects);
        audit = mock(AuditService.class);
        // ProjectService 用**桩过的** access：本类要单独验判权，不希望 bindProject 先挡一道。
        service = new ProjectService(projects, users, mock(AccessControlService.class), audit);
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AppUser user(long id) {
        return new AppUser(id, "u" + id, "用户" + id, null, "x", null, true, null, 0, null,
                Instant.now());
    }

    @Test
    @DisplayName("组授权进有效角色：只有用户组授权（无成员行）也能拿到项目角色")
    void groupGrantContributesEffectiveRole() {
        when(users.roleCodesOf(1L)).thenReturn(List.of());
        // 成员表没有行 → 旧的 findMember 分支为空；组授权由三源并集给出 engineer。
        when(projects.findMember(7L, 1L)).thenReturn(Optional.empty());
        when(projects.effectiveRoleCodes(7L, 1L)).thenReturn(List.of("engineer"));

        assertThat(access.effectiveRoles(1L, 7L))
                .as("组授权必须进有效角色，否则「界面显示被提升、实际仍 403」")
                .contains(RoleCode.ENGINEER);
    }

    @Test
    @DisplayName("资源级校验与列表同源：负责人/组授权可见即放行，不可见即 403")
    void requireProjectAccessFollowsVisibility() {
        when(users.roleCodesOf(1L)).thenReturn(List.of()); // 非管理员
        when(projects.isVisibleTo(7L, 1L)).thenReturn(true);
        assertThatCode(() -> access.requireProjectAccess(7L))
                .as("三源之一命中就该放行，否则列表能看到、点开 403")
                .doesNotThrowAnyException();

        when(projects.isVisibleTo(8L, 1L)).thenReturn(false);
        assertThatThrownBy(() -> access.requireProjectAccess(8L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("重复加成员幂等：角色取并集、不新增行、不抛 409")
    void addMemberIsIdempotent() {
        when(users.findById(3L)).thenReturn(Optional.of(user(3L)));
        when(projects.findMember(7L, 3L)).thenReturn(Optional.of(
                new ProjectMember(11L, 7L, 3L, "u3", "viewer", "用户3", true, List.of("viewer"))));
        when(projects.findMember(7L, 3L)).thenReturn(Optional.of(
                new ProjectMember(11L, 7L, 3L, "u3", "engineer", "用户3", true,
                        List.of("viewer", "engineer"))));

        ProjectMember result = service.addMember(7L, 3L, "engineer");

        verify(projects, never()).addMember(anyLong(), anyLong(), any(), any());
        verify(projects).replaceMemberRoles(eq(7L), eq(3L), eq(List.of("viewer", "engineer")),
                eq("engineer"), any());
        assertThat(result.roleCodes()).containsExactly("viewer", "engineer");
    }
}
