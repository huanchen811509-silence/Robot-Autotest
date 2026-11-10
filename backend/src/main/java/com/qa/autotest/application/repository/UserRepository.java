package com.qa.autotest.application.repository;

import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.Role;
import java.util.List;
import java.util.Optional;

/** 用户与用户-角色仓储（application 层定义的端口，infrastructure 实现）。 */
public interface UserRepository {

    Optional<AppUser> findById(long id);

    Optional<AppUser> findByUsername(String username);

    PageResult<AppUser> page(String keyword, Integer status, int page, int size);

    long insert(AppUser user, Long operatorId);

    void updateProfile(long id, String displayName, String email, Long orgId, boolean enabled,
                       Long updatedBy);

    void updatePassword(long id, String passwordHash, java.time.Instant expiresAt, Long updatedBy);

    void updateLoginSuccess(long id, java.time.Instant loginAt);

    void updateLoginFailure(long id, int failedCount, java.time.Instant lockedUntil);

    void delete(long id);

    /** 用户拥有的全局角色 code（小写）。 */
    List<String> roleCodesOf(long userId);

    /** 用户拥有的角色明细。 */
    List<Role> rolesOf(long userId);

    /** 全量替换用户角色。 */
    void replaceRoles(long userId, List<Long> roleIds, Long operatorId);

    boolean usernameExists(String username);

    boolean emailExists(String email, Long excludeUserId);

    /** 上次使用的界面模式：global / project（v4.5.0 / FR-v4.5-02）。 */
    String defaultUiMode(long userId);

    void updateDefaultUiMode(long userId, String mode);
}
