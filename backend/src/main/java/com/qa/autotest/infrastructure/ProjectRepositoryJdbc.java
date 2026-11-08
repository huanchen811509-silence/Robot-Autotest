package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.util.ArrayList;
import java.util.List;
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
            rs.getString("visibility"), rs.getString("test_kernel"));

    private static final String SELECT =
            "SELECT id, code, name, description, status, owner_id, visibility, test_kernel FROM project";

    private final JdbcTemplate jdbc;

    public ProjectRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
    public PageResult<Project> page(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM project" + where,
                Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<Project> rows = jdbc.query(SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public List<Project> findByMember(long userId) {
        return jdbc.query("""
                SELECT p.id, p.code, p.name, p.description, p.status, p.owner_id, p.visibility,
                       p.test_kernel
                  FROM project p JOIN project_member m ON m.project_id = p.id
                 WHERE m.user_id = ? ORDER BY p.id
                """, MAPPER, userId);
    }

    @Override
    public long insert(Project project) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO project (code, name, description, status, owner_id, visibility,
                                         test_kernel)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, project.code());
            ps.setString(2, project.name());
            ps.setString(3, project.description());
            ps.setInt(4, project.enabled() ? 1 : 0);
            ps.setObject(5, project.ownerId(), java.sql.Types.BIGINT);
            ps.setString(6, project.visibility() == null ? "private" : project.visibility());
            ps.setString(7, project.testKernel() == null ? "robot" : project.testKernel());
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
    public void delete(long id) {
        jdbc.update("DELETE FROM project_setting WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project_label WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project_member WHERE project_id = ?", id);
        jdbc.update("DELETE FROM project WHERE id = ?", id);
    }

    @Override
    public List<ProjectMember> members(long projectId) {
        return jdbc.query("""
                SELECT m.id, m.project_id, m.user_id, u.username, m.member_role,
                       u.display_name, u.status
                  FROM project_member m JOIN app_user u ON u.id = m.user_id
                 WHERE m.project_id = ? ORDER BY m.id
                """, (rs, rowNum) -> new ProjectMember(
                        rs.getLong("id"), rs.getLong("project_id"), rs.getLong("user_id"),
                        rs.getString("username"), rs.getString("member_role"),
                        rs.getString("display_name"), rs.getInt("status") == 1), projectId);
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
                        rs.getString("display_name"), rs.getInt("status") == 1),
                projectId, userId));
    }

    @Override
    public void addMember(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                INSERT INTO project_member (project_id, user_id, member_role, joined_at,
                                            created_by, updated_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(3), ?, ?)
                """, projectId, userId, roleCode, operatorId, operatorId);
    }

    @Override
    public void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                UPDATE project_member SET member_role = ?, updated_by = ?
                 WHERE project_id = ? AND user_id = ?
                """, roleCode, operatorId, projectId, userId);
    }

    @Override
    public void removeMember(long projectId, long userId) {
        jdbc.update("DELETE FROM project_member WHERE project_id = ? AND user_id = ?",
                projectId, userId);
    }

    @Override
    public boolean codeExists(String code, Long excludeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project WHERE code = ? AND id <> ?
                """, Integer.class, code, excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }
}
