package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 项目与成员仓储实现。 */
@Repository
public class ProjectRepositoryJdbc implements ProjectRepository {

    private static final RowMapper<Project> MAPPER = (rs, rowNum) -> new Project(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getInt("status") == 1,
            rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
            rs.getString("visibility"), rs.getString("test_kernel"),
            rs.getString("kind"), toLocalDateTime(rs.getTimestamp("code_changed_at")));

    private static final String SELECT =
            "SELECT id, code, name, description, status, owner_id, visibility, test_kernel, "
                    + "kind, code_changed_at FROM project";

    /**
     * 彻底删除时**不阻塞**的 project_id 表：
     * <ul>
     *   <li>{@code project_setting / project_label / project_member / project_member_role}：
     *       {@link #delete(long)} 会一并清掉；</li>
     *   <li>{@code audit_log / slo_daily}：审计与统计按合规口径保留，且没有外键指向 project。</li>
     * </ul>
     * 其余带 {@code project_id} 的表一律视为业务数据。用动态发现而不是硬编码清单：
     * 新增项目级表时自动纳入，避免「漏登记 → 硬删成功 → 留孤儿行」。
     */
    private static final java.util.Set<String> BUSY_IGNORED = java.util.Set.of(
            "project_setting", "project_label", "project_member", "project_member_role",
            "audit_log", "slo_daily");

    private final JdbcTemplate jdbc;

    public ProjectRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    @Override
    public Optional<Project> findById(long id) {
        return JdbcSupport.first(jdbc.query(SELECT + " WHERE id = ?", MAPPER, id));
    }

    @Override
    public Optional<Project> findByCode(String code) {
        return JdbcSupport.first(jdbc.query(SELECT + " WHERE code = ?", MAPPER, code));
    }

    @Override
    public Optional<Project> platformProject() {
        return JdbcSupport.first(jdbc.query(SELECT + " WHERE kind = 'platform' ORDER BY id LIMIT 1",
                MAPPER));
    }

    @Override
    public PageResult<Project> page(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE kind <> 'platform'");
        List<Object> args = new ArrayList<>();
        appendKeyword(where, args, keyword);
        return page(where, args, page, size);
    }

    @Override
    public PageResult<Project> pageGlobal(String keyword, boolean includePlatform,
                                          int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!includePlatform) {
            where.append(" AND kind <> 'platform'");
        }
        appendKeyword(where, args, keyword);
        return page(where, args, page, size);
    }

    private static void appendKeyword(StringBuilder where, List<Object> args, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private PageResult<Project> page(StringBuilder where, List<Object> args, int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM project" + where,
                Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((page - 1) * size);
        List<Project> rows = jdbc.query(SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, queryArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public List<Project> findByMember(long userId) {
        return jdbc.query("""
                SELECT p.id, p.code, p.name, p.description, p.status, p.owner_id, p.visibility,
                       p.test_kernel, p.kind, p.code_changed_at
                  FROM project p JOIN project_member m ON m.project_id = p.id
                 WHERE m.user_id = ? AND p.kind <> 'platform' ORDER BY p.id
                """, MAPPER, userId);
    }

    @Override
    public long insert(Project project) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO project (code, name, description, status, owner_id, visibility,
                                         test_kernel, kind)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, project.code());
            ps.setString(2, project.name());
            ps.setString(3, project.description());
            ps.setInt(4, project.enabled() ? 1 : 0);
            ps.setObject(5, project.ownerId(), java.sql.Types.BIGINT);
            ps.setString(6, project.visibility() == null ? "private" : project.visibility());
            ps.setString(7, project.testKernel() == null ? "robot" : project.testKernel());
            ps.setString(8, project.kind() == null ? "standard" : project.kind());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(long id, String name, String description, boolean enabled, Long ownerId,
                       String visibility, String testKernel, Long updatedBy) {
        jdbc.update("""
                UPDATE project SET name = ?, description = ?, status = ?, owner_id = ?,
                       visibility = ?, test_kernel = ?, updated_by = ? WHERE id = ?
                """, name, description, enabled ? 1 : 0, ownerId, visibility,
                testKernel == null ? "robot" : testKernel, updatedBy, id);
    }

    @Override
    public boolean changeCode(long id, String newCode, String reason, Long operatorId) {
        return jdbc.update("""
                UPDATE project
                   SET code = ?, code_changed_at = CURRENT_TIMESTAMP(3), updated_by = ?
                 WHERE id = ? AND code_changed_at IS NULL
                """, newCode, operatorId, id) > 0;
    }

    @Override
    public void delete(long id) {
        jdbc.update("DELETE FROM project_member_role WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project_setting WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project_label WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project_member WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project WHERE id = ?", id);
    }

    @Override
    public Map<String, Long> busyCounts(long projectId) {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND column_name = 'project_id' "
                        + "ORDER BY table_name",
                String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            if (BUSY_IGNORED.contains(table) || !table.matches("[a-z0-9_]+")) {
                continue;
            }
            String sql = "SELECT COUNT(*) FROM `" + table + "` WHERE project_id = ?";
            if (TenantGuard.requiresTenant(table)) {
                TenantGuard.requireProjectScope(sql, table);
            }
            Long count = jdbc.queryForObject(sql, Long.class, projectId);
            if (count != null && count > 0) {
                counts.put(table, count);
            }
        }
        return counts;
    }

    @Override
    public List<ProjectMember> members(long projectId) {
        List<ProjectMember> rows = jdbc.query("""
                SELECT m.id, m.project_id, m.user_id, u.username, m.member_role,
                       u.display_name, u.status
                  FROM project_member m JOIN app_user u ON u.id = m.user_id
                 WHERE m.project_id = ? ORDER BY m.id
                """, (rs, rowNum) -> new ProjectMember(
                        rs.getLong("id"), rs.getLong("project_id"), rs.getLong("user_id"),
                        rs.getString("username"), rs.getString("member_role"),
                        rs.getString("display_name"), rs.getInt("status") == 1), projectId);
        Map<Long, List<String>> roles = rolesByUser(projectId);
        List<ProjectMember> result = new ArrayList<>(rows.size());
        for (ProjectMember row : rows) {
            List<String> codes = roles.getOrDefault(row.userId(), List.of(row.roleCode()));
            result.add(new ProjectMember(row.id(), row.projectId(), row.userId(), row.username(),
                    row.roleCode(), row.displayName(), row.enabled(), List.copyOf(codes)));
        }
        return result;
    }

    private Map<Long, List<String>> rolesByUser(long projectId) {
        Map<Long, List<String>> roles = new HashMap<>();
        jdbc.query("SELECT user_id, role_code FROM project_member_role WHERE project_id = ? "
                        + "ORDER BY user_id, role_code",
                rs -> {
                    roles.computeIfAbsent(rs.getLong("user_id"), key -> new ArrayList<>())
                            .add(rs.getString("role_code"));
                }, projectId);
        return roles;
    }

    @Override
    public Optional<ProjectMember> findMember(long projectId, long userId) {
        return JdbcSupport.first(jdbc.query("""
                SELECT m.id, m.project_id, m.user_id, u.username, m.member_role,
                       u.display_name, u.status
                  FROM project_member m JOIN app_user u ON u.id = m.user_id
                 WHERE m.project_id = ? AND m.user_id = ?
                """, (rs, rowNum) -> new ProjectMember(
                        rs.getLong("id"), rs.getLong("project_id"), rs.getLong("user_id"),
                        rs.getString("username"), rs.getString("member_role"),
                        rs.getString("display_name"), rs.getInt("status") == 1,
                        rolesOf(projectId, userId)),
                projectId, userId));
    }

    @Override
    public List<String> rolesOf(long projectId, long userId) {
        return jdbc.queryForList("""
                SELECT role_code FROM project_member_role
                 WHERE project_id = ? AND user_id = ? ORDER BY role_code
                """, String.class, projectId, userId);
    }

    @Override
    public void addMember(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                INSERT INTO project_member (project_id, user_id, member_role, joined_at,
                                            created_by, updated_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(3), ?, ?)
                """, projectId, userId, roleCode, operatorId, operatorId);
        addRole(projectId, userId, roleCode, operatorId);
    }

    private void addRole(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                INSERT IGNORE INTO project_member_role (project_id, user_id, role_code, created_by)
                VALUES (?, ?, ?, ?)
                """, projectId, userId, roleCode, operatorId);
    }

    @Override
    public void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                UPDATE project_member SET member_role = ?, updated_by = ?
                 WHERE project_id = ? AND user_id = ?
                """, roleCode, operatorId, projectId, userId);
        jdbc.update("DELETE FROM project_member_role WHERE project_id = ? AND user_id = ?",
                projectId, userId);
        addRole(projectId, userId, roleCode, operatorId);
    }

    @Override
    public void replaceMemberRoles(long projectId, long userId, List<String> roleCodes,
                                   String primaryRole, Long operatorId) {
        jdbc.update("""
                UPDATE project_member SET member_role = ?, updated_by = ?
                 WHERE project_id = ? AND user_id = ?
                """, primaryRole, operatorId, projectId, userId);
        jdbc.update("DELETE FROM project_member_role WHERE project_id = ? AND user_id = ?",
                projectId, userId);
        for (String roleCode : roleCodes) {
            addRole(projectId, userId, roleCode, operatorId);
        }
    }

    @Override
    public void removeMember(long projectId, long userId) {
        jdbc.update("DELETE FROM project_member_role WHERE project_id = ? AND user_id = ?",
                projectId, userId);
        jdbc.update("DELETE FROM project_member WHERE project_id = ? AND user_id = ?",
                projectId, userId);
    }

    @Override
    public long countMembersWithRole(long projectId, String roleCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_member_role WHERE project_id = ? AND role_code = ?
                """, Long.class, projectId, roleCode);
        return count == null ? 0 : count;
    }

    @Override
    public long countManagers(long projectId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT user_id) FROM project_member_role
                 WHERE project_id = ? AND role_code IN ('admin', 'pm')
                """, Long.class, projectId);
        return count == null ? 0 : count;
    }

    @Override
    public long nextCodeSequence(String scope) {
        jdbc.update("""
                INSERT INTO project_code_sequence (scope, next_no)
                VALUES (?, LAST_INSERT_ID(1))
                ON DUPLICATE KEY UPDATE next_no = LAST_INSERT_ID(next_no + 1)
                """, scope);
        Long value = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return value == null ? 1 : value;
    }

    @Override
    public boolean codeExists(String code, Long excludeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project WHERE code = ? AND id <> ?
                """, Integer.class, code, excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }
}
