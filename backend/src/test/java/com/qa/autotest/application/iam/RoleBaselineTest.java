package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.PermissionMatrix;
import com.qa.autotest.domain.iam.Role;
import com.qa.autotest.domain.iam.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 角色基线单测（v5.21.0 / FR-v5.21-01、02 / W71-01~03）。
 *
 * <p>锁三件在界面上很难验、但一错就是"整个权限体系失效"的事：
 * <ol>
 *   <li>无全局角色 = **最小集**，既不是空集（前端会当"没加载"），也不是全量（放大权限）；</li>
 *   <li>内置角色自检**只补缺不覆盖**（管理员手工改过的角色名不能被重启回滚）；</li>
 *   <li>编辑用户时传空角色数组 = **明确清空**，不再回落到 viewer（否则"无角色账号"永远造不出来）。</li>
 * </ol>
 */
class RoleBaselineTest {

    private static final long TARGET_ID = 42L;

    private UserRepository users;
    private RoleRepository roles;
    private AccessControlService access;
    private AuditService audit;
    private UserService service;
    private RoleService roleService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        service = new UserService(users, roles, access, audit, null);
        roleService = new RoleService(roles, access, audit);
        TenantContext.set(1L, "admin", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("无全局角色：能力集 = about/changelog（最小集，不是空集也不是全量）")
    void minimalCapabilities() {
        assertThat(PermissionMatrix.minimalMenuCapabilities())
                .containsExactly("about", "changelog");
        // 空集会让前端把侧栏当成"还没加载"，全量则是放大权限——两个方向都要挡住
        assertThat(PermissionMatrix.minimalMenuCapabilities()).isNotEmpty();
        assertThat(PermissionMatrix.minimalMenuCapabilities()).doesNotContain("settings:read");
        assertThat(PermissionMatrix.minimalMenuCapabilities()).doesNotContain("case:write");
    }

    @Test
    @DisplayName("内置角色自检：缺 pm 时补一条 builtIn，已存在的不动")
    void ensureBuiltinRolesOnlyAddsMissing() {
        when(roles.codeExists(RoleCode.ADMIN.code(), null)).thenReturn(true);
        when(roles.codeExists(RoleCode.PM.code(), null)).thenReturn(false);
        when(roles.codeExists(RoleCode.ENGINEER.code(), null)).thenReturn(true);
        when(roles.codeExists(RoleCode.VIEWER.code(), null)).thenReturn(true);

        List<String> added = roleService.ensureBuiltinRoles();

        assertThat(added).containsExactly("pm");
        verify(roles).insert(org.mockito.ArgumentMatchers.argThat(role ->
                RoleCode.PM.code().equals(role.code()) && role.builtIn()
                        && RoleService.labelOf(RoleCode.PM).equals(role.name())));
        // 已存在的三条不得被 update/insert 覆盖
        verify(roles, never()).update(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("编辑用户：roleIds 传空数组 = 清空角色，不回落 viewer")
    void updateWithEmptyRolesClearsThem() {
        AppUser existing = new AppUser(TARGET_ID, "qa-e2e-nobody", "无角色", null, "x", null,
                true, Instant.now(), 0, null, null);
        when(users.findById(TARGET_ID)).thenReturn(Optional.of(existing));
        when(users.roleCodesOf(TARGET_ID)).thenReturn(List.of());

        service.update(TARGET_ID, "无角色", null, null, true, List.of());

        // 关键断言：传的是空列表本身，而不是被默认角色替换后的 [viewer]
        verify(users).replaceRoles(eq(TARGET_ID), eq(List.of()), any());
    }
}
