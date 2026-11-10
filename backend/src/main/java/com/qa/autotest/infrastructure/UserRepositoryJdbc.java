package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.Role;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 用户仓储实现。所有查询都在 SQL 里显式带上限界条件；此处是白名单表，不强制租户列。 */
@Repository
public class UserRepositoryJdbc implements UserRepository {

    private static final String SELECT = """
            SELECT id, username, display_name, email, password_hash, org_id, status,
                   password_expires_at, failed_login_count, locked_until, last_login_at
            FROM app_user
            """;

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getObject("org_id") == null ? null : rs.getLong("org_id"),
            rs.getInt("status") == 1,
            JdbcSupport.instant(rs.getTimestamp("password_expires_at")),
            rs.getInt("failed_login_count"),
            JdbcSupport.instant(rs.getTimestamp("locked_until")),
            JdbcSupport.instant(rs.getTimestamp("last_login_at")));

    private final JdbcTemplate jdbc;

    public UserRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AppUser> findById(long id) {
        return JdbcSupport.first(jdbc.query(SELECT + " WHERE id = ?", MAPPER, id));
    }

    @Override
    public Optional<AppUser> findByUsername(String username) {
        return JdbcSupport.first(jdbc.query(SELECT + " WHERE username = ?", MAPPER, username));
    }

    @Override
    public PageResult<AppUser> page(String keyword, Integer status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (username LIKE ? OR display_name LIKE ? OR email LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_user" + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<AppUser> rows = jdbc.query(SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public long insert(AppUser user, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO app_user
                      (username, display_name, email, password_hash, org_id, status,
                       password_expires_at, failed_login_count, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user.username());
            ps.setString(2, user.displayName());
            ps.setString(3, user.email());
            ps.setString(4, user.passwordHash());
            if (user.orgId() == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, user.orgId());
            }
            ps.setInt(6, user.enabled() ? 1 : 0);
            ps.setTimestamp(7, user.passwordExpiresAt() == null
                    ? null : Timestamp.from(user.passwordExpiresAt()));
            ps.setObject(8, operatorId, java.sql.Types.BIGINT);
            ps.setObject(9, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void updateProfile(long id, String displayName, String email, Long orgId,
                              boolean enabled, Long updatedBy) {
        jdbc.update("""
                UPDATE app_user
                   SET display_name = ?, email = ?, org_id = ?, status = ?, updated_by = ?
                 WHERE id = ?
                """, displayName, email, orgId, enabled ? 1 : 0, updatedBy, id);
    }

    @Override
    public void updatePassword(long id, String passwordHash, Instant expiresAt, Long updatedBy) {
        jdbc.update("""
                UPDATE app_user
                   SET password_hash = ?, password_expires_at = ?, last_password_change_at = ?,
                       failed_login_count = 0, locked_until = NULL, updated_by = ?
                 WHERE id = ?
                """, passwordHash, Timestamp.from(expiresAt), Timestamp.from(Instant.now()),
                updatedBy, id);
    }

    @Override
    public void updateLoginSuccess(long id, Instant loginAt) {
        jdbc.update("""
                UPDATE app_user
                   SET last_login_at = ?, failed_login_count = 0, locked_until = NULL
                 WHERE id = ?
                """, Timestamp.from(loginAt), id);
    }

    @Override
    public void updateLoginFailure(long id, int failedCount, Instant lockedUntil) {
        jdbc.update("""
                UPDATE app_user SET failed_login_count = ?, locked_until = ? WHERE id = ?
                """, failedCount,
                lockedUntil == null ? null : Timestamp.from(lockedUntil), id);
    }

    @Override
    public void delete(long id) {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
        jdbc.update("DELETE FROM api_token WHERE user_id = ?", id);
        jdbc.update("DELETE FROM project_member WHERE user_id = ?", id);
        jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }

    @Override
    public List<String> roleCodesOf(long userId) {
        return jdbc.query("""
                SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? ORDER BY r.id
                """, (rs, rowNum) -> rs.getString(1), userId);
    }

    @Override
    public List<Role> rolesOf(long userId) {
        return jdbc.query("""
                SELECT r.id, r.code, r.name, r.description, r.built_in, r.permissions
                  FROM user_role ur JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? ORDER BY r.id
                """, (rs, rowNum) -> new Role(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("built_in") == 1,
                        rs.getString("permissions")), userId);
    }

    @Override
    public void replaceRoles(long userId, List<Long> roleIds, Long operatorId) {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            jdbc.update("""
                    INSERT INTO user_role (user_id, role_id, created_by, updated_by)
                    VALUES (?, ?, ?, ?)
                    """, userId, roleId, operatorId, operatorId);
        }
    }

    @Override
    public boolean usernameExists(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    @Override
    public boolean emailExists(String email, Long excludeUserId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user WHERE email = ? AND id <> ?
                """, Integer.class, email, excludeUserId == null ? -1L : excludeUserId);
        return count != null && count > 0;
    }

    @Override
    public String defaultUiMode(long userId) {
        String mode = jdbc.queryForObject(
                "SELECT default_ui_mode FROM app_user WHERE id = ?", String.class, userId);
        return mode == null || mode.isBlank() ? "project" : mode;
    }

    @Override
    public void updateDefaultUiMode(long userId, String mode) {
        jdbc.update("UPDATE app_user SET default_ui_mode = ? WHERE id = ?", mode, userId);
    }
}
