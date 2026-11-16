package com.qa.autotest.application.group;

import com.qa.autotest.domain.group.EffectiveRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/** 用户组 CRUD / 成员 / 组-项目授权（IAMX-01、IAMX-02）。 */
@Service
public class GroupService {

    private final JdbcTemplate jdbc;
    private final EffectiveRoleResolver resolver;

    public GroupService(JdbcTemplate jdbc, EffectiveRoleResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    /**
     * 组列表（带成员数与授权项目数）。
     *
     * <p>计数的过滤条件与关系聚合接口（`dimension=group`）逐字相同：只数标准项目、
     * 只数真实存在的关联行。两处数字若不一致，界面就会出现「列表说 3、点开 2」。
     */
    public List<Map<String, Object>> groups() {
        return jdbc.queryForList("""
                SELECT g.id AS id, g.name AS name, g.description AS description,
                       (SELECT COUNT(*) FROM user_group_member gm WHERE gm.group_id = g.id)
                           AS memberCount,
                       (SELECT COUNT(*) FROM project_group_role r
                          JOIN project p ON p.id = r.project_id
                         WHERE r.group_id = g.id AND p.kind <> 'platform') AS projectCount
                  FROM user_group g
                 ORDER BY g.id
                """);
    }

    public long createGroup(String name, String description) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO user_group (name, description) VALUES (?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            return ps;
        }, keys);
        resolver.invalidateAll();
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    public void addMember(long groupId, long userId) {
        jdbc.update("INSERT IGNORE INTO user_group_member (group_id, user_id) VALUES (?, ?)",
                groupId, userId);
        resolver.invalidateAll();
    }

    /**
     * 幂等添加成员（v5.9.0 / FR-v5.9-06）：返回 true 表示本次真的新增，false 表示已存在被跳过。
     *
     * <p>`INSERT IGNORE` 已经把「重复」处理成静默跳过，但调用方需要知道跳过了几项才能在界面上
     * 说「已存在 2 项，未重复添加」——所以这里用影响行数而不是再查一次。
     */
    public boolean addMemberIfAbsent(long groupId, long userId) {
        boolean added = jdbc.update(
                "INSERT IGNORE INTO user_group_member (group_id, user_id) VALUES (?, ?)",
                groupId, userId) > 0;
        resolver.invalidateAll();
        return added;
    }

    public void rename(long groupId, String name, String description) {
        jdbc.update("UPDATE user_group SET name = ?, description = ? WHERE id = ?",
                name, description, groupId);
        resolver.invalidateAll();
    }

    /** 组详情：成员与已授权项目（用户组页右侧工作区一次取全，避免两次往返）。 */
    public Map<String, Object> detail(long groupId) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT id, name, description FROM user_group WHERE id = ?", groupId);
        body.put("group", found.isEmpty() ? null : found.get(0));
        body.put("members", jdbc.queryForList("""
                SELECT u.id AS id, u.username AS username, u.display_name AS displayName
                  FROM app_user u JOIN user_group_member gm ON gm.user_id = u.id
                 WHERE gm.group_id = ? ORDER BY u.id
                """, groupId));
        body.put("projects", jdbc.queryForList("""
                SELECT p.id AS id, p.code AS code, p.name AS name, r.role_code AS roleCode
                  FROM project_group_role r JOIN project p ON p.id = r.project_id
                 WHERE r.group_id = ? ORDER BY p.id
                """, groupId));
        return body;
    }

    /**
     * 删组：先清两张关联表再删主表。
     *
     * <p>顺序不能反——`V11_66` 之后两张关联表对 `user_group` 是 `ON DELETE CASCADE`，
     * 但级联只能在有外键的库上生效；显式删除让「迁移未跑的环境」也不会留下孤儿行。
     */
    public void deleteGroup(long groupId) {
        jdbc.update("DELETE FROM user_group_member WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM project_group_role WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM user_group WHERE id = ?", groupId);
        resolver.invalidateAll();
    }

    public void removeMember(long groupId, long userId) {
        jdbc.update("DELETE FROM user_group_member WHERE group_id = ? AND user_id = ?",
                groupId, userId);
        resolver.invalidateAll();
    }

    /** 撤销组在某项目上的授权；返回是否真的删掉了（幂等：重复撤销不报错）。 */
    public boolean revoke(long projectId, long groupId) {
        boolean removed = jdbc.update(
                "DELETE FROM project_group_role WHERE project_id = ? AND group_id = ?",
                projectId, groupId) > 0;
        resolver.invalidate(projectId);
        return removed;
    }

    public List<Long> members(long groupId) {
        return jdbc.queryForList("SELECT user_id FROM user_group_member WHERE group_id = ?",
                Long.class, groupId);
    }

    public void grant(long projectId, long groupId, String roleCode) {
        if (EffectiveRole.parse(roleCode) == EffectiveRole.NONE) {
            throw new IllegalArgumentException("未知角色: " + roleCode);
        }
        // v5.21.0 / FR-v5.21-06：这里原来写的是 `toUpperCase()`——正是它让
        // project_group_role 里出现 `ENGINEER`，与 project_member_role 的 `viewer` 分裂成两种形态。
        // 角色代码统一小写，判权侧 RoleCode.from / EffectiveRole.parse 本来就大小写兼容。
        jdbc.update("INSERT INTO project_group_role (project_id, group_id, role_code) "
                        + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE role_code = VALUES(role_code)",
                projectId, groupId, roleCode.trim().toLowerCase(java.util.Locale.ROOT));
        resolver.invalidate(projectId);
    }

    /** 授权即时生效的读路径：变更后缓存已失效，这里重新解析。 */
    public Map<String, Object> effective(long projectId, long userId) {
        EffectiveRole role = resolver.resolve(userId, projectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("userId", userId);
        result.put("role", role.name());
        result.put("rank", role.rank());
        return result;
    }
}
