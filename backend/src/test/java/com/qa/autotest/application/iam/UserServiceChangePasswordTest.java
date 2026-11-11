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

import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.security.PasswordHasher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.AppUser;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「修改自己的口令」单测（v4.12.0 / FR-v4.12-23）。
 *
 * <p>这条用例锁的是**权限口径**而不是功能本身：`resetPassword` 要求 `USER_ROLE:UPDATE`，
 * 而 `changeOwnPassword` 刻意不要求 —— 一旦有人「顺手统一一下」，工程师就又改不了自己的密码了。
 * 因此这里显式断言 `access` 从未被要求任何权限，并在注释里写明这不是漏测。
 */
class UserServiceChangePasswordTest {

    private static final long SELF_ID = 7L;
    private static final String OLD = "OldPassw0rd!";
    private static final String NEW = "NewPassw0rd!";

    private UserRepository users;
    private AccessControlService access;
    private AuditService audit;
    private UserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        service = new UserService(users, mock(RoleRepository.class), access, audit,
                new PasswordPolicy());
        TenantContext.set(SELF_ID, "engineer", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUser self() {
        return new AppUser(SELF_ID, "engineer", "工程师", "eng@example.com",
                PasswordHasher.hash(OLD), null, true, Instant.now(), 0, null, null);
    }

    @Test
    @DisplayName("旧口令正确：换哈希落库，审计只记用户名（不含任何口令片段）")
    void changeOwnPasswordWritesHashAndAuditsWithoutSecret() {
        when(users.findById(SELF_ID)).thenReturn(Optional.of(self()));

        service.changeOwnPassword(OLD, NEW);

        verify(users).updatePassword(eq(SELF_ID), any(String.class), any(Instant.class), eq(SELF_ID));
        verify(audit).record(eq("user.password.self-change"), eq("app_user"),
                eq(String.valueOf(SELF_ID)), eq("username=engineer"));
        // 权限口径的显式断言：改自己的口令不需要任何资源权限
        verify(access, never()).require(any(), any());
    }

    @Test
    @DisplayName("旧口令错误：400，且一个字节都不写库")
    void wrongOldPasswordIsRejected() {
        when(users.findById(SELF_ID)).thenReturn(Optional.of(self()));

        assertThatThrownBy(() -> service.changeOwnPassword("WrongOld0ne!", NEW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原口令不正确");
        verify(users, never()).updatePassword(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("没传旧口令：同样按「不正确」处理，不泄露「你没填」这种可用于探测的差异")
    void blankOldPasswordIsRejectedTheSameWay() {
        when(users.findById(SELF_ID)).thenReturn(Optional.of(self()));

        assertThatThrownBy(() -> service.changeOwnPassword(null, NEW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原口令不正确");
        assertThatThrownBy(() -> service.changeOwnPassword("   ", NEW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原口令不正确");
    }

    @Test
    @DisplayName("新口令不合规：走与管理员重置同一个 PasswordPolicy，弱口令进不来")
    void newPasswordFollowsTheSamePolicy() {
        when(users.findById(SELF_ID)).thenReturn(Optional.of(self()));

        assertThatThrownBy(() -> service.changeOwnPassword(OLD, "short"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少");
        assertThatThrownBy(() -> service.changeOwnPassword(OLD, "alllowercase1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("大写");
        verify(users, never()).updatePassword(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("新旧相同：拒绝，否则「轮换成功」是个假象")
    void sameAsOldIsRejected() {
        when(users.findById(SELF_ID)).thenReturn(Optional.of(self()));

        assertThatThrownBy(() -> service.changeOwnPassword(OLD, OLD))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能与原口令相同");
        verify(users, never()).updatePassword(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("当前账号查不到：404 而不是 500")
    void missingAccountIsNotFound() {
        when(users.findById(SELF_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeOwnPassword(OLD, NEW))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode().name())
                .isEqualTo("NOT_FOUND");
        assertThat(PasswordHasher.verify(OLD, PasswordHasher.hash(OLD))).isTrue();
    }
}
