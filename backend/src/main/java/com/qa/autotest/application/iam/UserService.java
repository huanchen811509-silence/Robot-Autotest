package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.PasswordHasher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Role;
import com.qa.autotest.domain.iam.RoleCode;
import com.qa.autotest.domain.iam.UserSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户 CRUD（IAM-01）：仅管理员可写，写操作全落审计。 */
@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final AccessControlService access;
    private final AuditService audit;
    private final PasswordPolicy passwordPolicy;

    public UserService(UserRepository users, RoleRepository roles, AccessControlService access,
                       AuditService audit, PasswordPolicy passwordPolicy) {
        this.users = users;
        this.roles = roles;
        this.access = access;
        this.audit = audit;
        this.passwordPolicy = passwordPolicy;
    }

    public PageResult<UserSummary> page(String keyword, Integer status, int page, int size) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.READ);
        PageResult<AppUser> raw = users.page(keyword, status, page, size);
        List<UserSummary> items = raw.items().stream()
                .map(user -> UserSummary.withoutRoles(user).withRoles(users.roleCodesOf(user.id())))
                .toList();
        return new PageResult<>(items, raw.total(), raw.page(), raw.size());
    }

    public UserSummary get(long id) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.READ);
        AppUser user = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        return UserSummary.withoutRoles(user).withRoles(users.roleCodesOf(id));
    }

    @Audited(action = "user:create", resource = "app_user")
    @Transactional
    public UserSummary create(String username, String displayName, String email, Long orgId,
                              String rawPassword, List<Long> roleIds) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.CREATE);
        String name = requireText(username, "用户名");
        if (users.usernameExists(name)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在：" + name);
        }
        if (email != null && !email.isBlank() && users.emailExists(email, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已存在：" + email);
        }
        passwordPolicy.validate(rawPassword, name);
        Instant now = Instant.now();
        AppUser created = new AppUser(null, name, displayName, email, PasswordHasher.hash(rawPassword),
                orgId, true, passwordPolicy.expiresAt(now), 0, null, null);
        long id = users.insert(created, TenantContext.userId());
        users.replaceRoles(id, defaultRoles(roleIds), TenantContext.userId());
        audit.recordChange("user:create", "app_user", String.valueOf(id), null,
                "username=" + name + "; roles=" + defaultRoles(roleIds).size() + "; configured=true");
        return get(id);
    }

    @Transactional
    public UserSummary update(long id, String displayName, String email, Long orgId, boolean enabled,
                              List<Long> roleIds) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.UPDATE);
        AppUser user = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (email != null && !email.isBlank() && users.emailExists(email, id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已存在：" + email);
        }
        if (!enabled && id == TenantContext.requireUserId()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能停用当前登录账号");
        }
        users.updateProfile(id, displayName, email, orgId, enabled, TenantContext.userId());
        if (roleIds != null) {
            users.replaceRoles(id, defaultRoles(roleIds), TenantContext.userId());
        }
        audit.record("user.update", "app_user", String.valueOf(id), "username=" + user.username());
        return get(id);
    }

    /** 管理员重置口令：返回无需把新口令写进审计（审计只记动作）。 */
    @Transactional
    public void resetPassword(long id, String rawPassword) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.UPDATE);
        AppUser user = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        passwordPolicy.validate(rawPassword, user.username());
        users.updatePassword(id, PasswordHasher.hash(rawPassword),
                passwordPolicy.expiresAt(Instant.now()), TenantContext.userId());
        audit.record("user.reset-password", "app_user", String.valueOf(id), "username=" + user.username());
    }

    /**
     * 修改**自己**的口令（v4.12.0 / FR-v4.12-23）。
     *
     * <p>刻意**不要求** {@code USER_ROLE:UPDATE}：那是「改别人」的能力。要求它，等于把「改自己
     * 的密码」锁成管理员特权——工程师被要求定期换口令时只能找管理员代改，代改就得把新口令
     * 口头/微信传一遍，实际安全性更差（00-RESEARCH.md C3）。身份来源固定是 {@code TenantContext}
     * 的当前登录用户，请求体里没有 userId，所以「改自己」这件事在结构上不可能越权成「改别人」。
     *
     * <p>三道校验缺一不可：旧口令必须对（防会话被劫持后直接改掉）、新口令走与
     * {@link #resetPassword} 同一个 {@link PasswordPolicy}（否则「管理员重置」严、「自己改」松，
     * 弱口令从这里进来）、新旧不能相同（否则忘了轮换还显示成功）。
     *
     * <p>审计 detail 只有用户名，**不含新旧口令任何片段**。
     */
    @Transactional
    public void changeOwnPassword(String oldPassword, String newPassword) {
        long userId = TenantContext.requireUserId();
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "当前账号不存在"));
        if (oldPassword == null || oldPassword.isBlank()
                || !PasswordHasher.verify(oldPassword, user.passwordHash())) {
            // 不区分「没传」与「传错了」：对外只给一句话，避免被当成口令探测器
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "原口令不正确");
        }
        passwordPolicy.validate(newPassword, user.username());
        if (PasswordHasher.verify(newPassword, user.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "新口令不能与原口令相同");
        }
        users.updatePassword(userId, PasswordHasher.hash(newPassword),
                passwordPolicy.expiresAt(Instant.now()), userId);
        audit.record("user.password.self-change", "app_user", String.valueOf(userId),
                "username=" + user.username());
    }

    @Audited(action = "user:delete", resource = "app_user")
    @Transactional
    public void delete(long id) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.DELETE);
        if (id == TenantContext.requireUserId()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "不能删除当前登录账号");
        }
        // 删除类只记前值，便于事后还原「被删的是什么」
        AppUser existing = users.findById(id).orElse(null);
        users.delete(id);
        audit.recordChange("user:delete", "app_user", String.valueOf(id),
                existing == null ? null : "username=" + existing.username()
                        + "; enabled=" + existing.enabled(), null);
    }

    public List<Role> allRoles() {
        access.require(PermissionResource.USER_ROLE, PermissionAction.READ);
        return roles.findAll();
    }

    private List<Long> defaultRoles(List<Long> roleIds) {
        if (roleIds != null && !roleIds.isEmpty()) {
            return roleIds;
        }
        return roles.findByCode(RoleCode.VIEWER.code())
                .map(role -> List.of(role.id()))
                .orElse(List.of());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        return value.trim();
    }
}
