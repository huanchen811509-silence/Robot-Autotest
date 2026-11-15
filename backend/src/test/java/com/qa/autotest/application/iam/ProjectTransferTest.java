package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.mockito.ArgumentCaptor;

/**
 * v5.9.0 / FR-v5.9-11：owner 转移事务（W59-09）。
 *
 * <p>三条主线：① 新 owner 必须同时落进 {@code project.owner_id} / {@code project_member} /
 * {@code project_member_role}；② 成员写入失败时 owner 列**一步都不能执行**（顺序保证 + 事务兜底）；
 * ③ 不传 ownerId ≠ 把 owner 清空。
 */
class ProjectTransferTest {

    private static final long PROJECT_ID = 7L;
    private static final long OLD_OWNER = 2L;
    private static final long NEW_OWNER = 3L;

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
        when(projects.findById(PROJECT_ID))
                .thenReturn(Optional.of(project(OLD_OWNER)))
                .thenReturn(Optional.of(project(NEW_OWNER)));
        when(users.findById(NEW_OWNER)).thenReturn(Optional.of(user(NEW_OWNER)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Project project(Long ownerId) {
        return new Project(PROJECT_ID, "PRJ-RF-202611-001", "项目", null, true, ownerId,
                "private", "robot", "standard", (LocalDateTime) null);
    }

    private static AppUser user(long id) {
        return new AppUser(id, "u" + id, "用户" + id, null, "x", null, true, null, 0, null,
                Instant.now());
    }

    private static ProjectMember member(long userId, String... roles) {
        return new ProjectMember(1L, PROJECT_ID, userId, "u" + userId, roles[0], "用户" + userId,
                true, List.of(roles));
    }

    @Test
    @DisplayName("转移给非成员：补 member 行 + admin 角色，owner 列同时改")
    void 转移给非成员时补成员与角色() {
        when(projects.findMember(PROJECT_ID, NEW_OWNER)).thenReturn(Optional.empty());

        service.update(PROJECT_ID, "项目", null, true, NEW_OWNER, "private", "robot");

        verify(projects).addMember(PROJECT_ID, NEW_OWNER, "admin", 1L);
        ArgumentCaptor<Long> owner = ArgumentCaptor.forClass(Long.class);
        verify(projects).update(eq(PROJECT_ID), anyString(), any(), anyBoolean(),
                owner.capture(), anyString(), anyString(), anyLong());
        assertThat(owner.getValue()).isEqualTo(NEW_OWNER);
        verify(audit).recordChange("project.owner.transfer", "project",
                String.valueOf(PROJECT_ID), "owner=" + OLD_OWNER, "owner=" + NEW_OWNER);
    }

    @Test
    @DisplayName("转移给已有成员但无 admin：并集补 admin，不忘掉原有 engineer")
    void 转移时并集补角色() {
        when(projects.findMember(PROJECT_ID, NEW_OWNER))
                .thenReturn(Optional.of(member(NEW_OWNER, "engineer")));

        service.update(PROJECT_ID, "项目", null, true, NEW_OWNER, "private", "robot");

        ArgumentCaptor<List<String>> roles = ArgumentCaptor.forClass(List.class);
        verify(projects).replaceMemberRoles(eq(PROJECT_ID), eq(NEW_OWNER), roles.capture(),
                eq("admin"), eq(1L));
        assertThat(roles.getValue()).containsExactly("admin", "engineer");
        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("转移给已是 admin 的成员：不重复写成员表")
    void 已是管理员时不重复写() {
        when(projects.findMember(PROJECT_ID, NEW_OWNER))
                .thenReturn(Optional.of(member(NEW_OWNER, "admin")));

        service.update(PROJECT_ID, "项目", null, true, NEW_OWNER, "private", "robot");

        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
        verify(projects, never()).replaceMemberRoles(anyLong(), anyLong(), any(), anyString(),
                any());
        verify(projects).update(eq(PROJECT_ID), anyString(), any(), anyBoolean(), eq(NEW_OWNER),
                anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("故障注入：成员写入抛错 → owner 列一步都没执行（owner 未变）")
    void 成员写入失败时不动owner列() {
        when(projects.findMember(PROJECT_ID, NEW_OWNER)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("project_member insert failed"))
                .when(projects).addMember(PROJECT_ID, NEW_OWNER, "admin", 1L);

        assertThatThrownBy(() -> service.update(PROJECT_ID, "项目", null, true, NEW_OWNER,
                "private", "robot"))
                .isInstanceOf(IllegalStateException.class);

        verify(projects, never()).update(anyLong(), anyString(), any(), anyBoolean(), any(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("目标用户不存在：404 且不动任何表")
    void 目标用户不存在时拒绝() {
        when(users.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(PROJECT_ID, "项目", null, true, 99L,
                "private", "robot"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);

        verify(projects, never()).update(anyLong(), anyString(), any(), anyBoolean(), any(),
                anyString(), anyString(), any());
        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("ownerId 传 null：保持原 owner，不触发转移")
    void 不传ownerId时保持原归属() {
        service.update(PROJECT_ID, "项目", null, true, null, "private", "robot");

        ArgumentCaptor<Long> owner = ArgumentCaptor.forClass(Long.class);
        verify(projects).update(eq(PROJECT_ID), anyString(), any(), anyBoolean(),
                owner.capture(), anyString(), anyString(), anyLong());
        assertThat(owner.getValue()).isEqualTo(OLD_OWNER);
        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
        verify(audit, never()).recordChange(eq("project.owner.transfer"), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("owner 未变（显式传同一个 id）：不重复补成员")
    void owner未变时不补成员() {
        service.update(PROJECT_ID, "项目", null, true, OLD_OWNER, "private", "robot");

        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
        verify(projects, never()).findMember(anyLong(), anyLong());
    }

    @Test
    @DisplayName("平台项目仍不可转移所有权")
    void 平台项目拒绝转移() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(
                new Project(PROJECT_ID, "SYSTEM", "平台项目", null, true, OLD_OWNER,
                        "private", "robot", "platform", (LocalDateTime) null)));

        assertThatThrownBy(() -> service.update(PROJECT_ID, "平台项目", null, true, NEW_OWNER,
                "private", "robot"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(projects, never()).addMember(anyLong(), anyLong(), anyString(), any());
    }
}
