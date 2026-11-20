package com.qa.autotest.infrastructure;

import com.qa.autotest.application.iam.ProjectVisibilitySql;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.tenant.TenantContext;
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
            rs.getString("kind"), toLocalDateTime(rs.getTimestamp("code_changed_at")),
            rs.getString("origin") == null ? "BUILTIN" : rs.getString("origin"));

    private static final String SELECT =
            "SELECT id, code, name, description, status, owner_id, visibility, test_kernel, "
                    + "kind, code_changed_at, origin FROM project";

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
        // v5.9.0 / FR-v5.9-04：三源并集（成员 ∪ 负责人 ∪ 用户组授权），SQL 只有一份。
        // 旧实现只认 project_member，于是「自己建的 / 组授权进来」的项目全看不到。
        return jdbc.query(SELECT + """
                  WHERE id IN (
                """ + ProjectVisibilitySql.VISIBLE_PROJECT_IDS + """
                  ) AND kind <> 'platform' ORDER BY id
                """, MAPPER, userId, userId, userId);
    }

    @Override
    public Map<Long, String> visibleSources(long userId) {
        Map<Long, String> sources = new LinkedHashMap<>();
        jdbc.query(ProjectVisibilitySql.VISIBLE_SOURCES, rs -> {
            sources.put(rs.getLong("project_id"), rs.getString("source"));
        }, userId, userId, userId);
        return sources;
    }

    @Override
    public List<String> effectiveRoleCodes(long projectId, long userId) {
        return jdbc.queryForList(ProjectVisibilitySql.EFFECTIVE_ROLE_CODES, String.class,
                projectId, userId, projectId, userId, projectId, userId);
    }

    @Override
    public boolean isVisibleTo(long projectId, long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id = ? AND id IN ("
                        + ProjectVisibilitySql.VISIBLE_PROJECT_IDS + ")",
                Long.class, projectId, userId, userId, userId);
        return count != null && count > 0;
    }

    @Override
    public long insert(Project project) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO project (code, name, description, status, owner_id, visibility,
                                         test_kernel, kind, origin, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, project.code());
            ps.setString(2, project.name());
            ps.setString(3, project.description());
            ps.setInt(4, project.enabled() ? 1 : 0);
            ps.setObject(5, project.ownerId(), java.sql.Types.BIGINT);
            ps.setString(6, project.visibility() == null ? "private" : project.visibility());
            ps.setString(7, project.testKernel() == null ? "robot" : project.testKernel());
            ps.setString(8, project.kind() == null ? "standard" : project.kind());
            // FR-ORIGIN-01：新建即用户自建；显式写入，不依赖 DB 默认值（测试桩可能不带 origin）。
            ps.setString(9, project.origin() == null ? "USER" : project.origin());
            // v5.9.0 / FR-v5.9-03：创建人取当前请求主体。非请求上下文（迁移脚本、单测直插）
            // 写 NULL——与存量行同义（「本列上线前/无主体创建」），不猜、不回填。
            ps.setObject(10, TenantContext.userId(), java.sql.Types.BIGINT);
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

    /**
     * FR-ORIGIN-07：级联删除预览——逐表列出将被删除的行数（只读）。
     *
     * <p>与 {@link #purgeProject(long)} 用同一张表清单和同一套间接子表口径，保证「清单即实际删除面」。
     * 清单含两类：① 无 project_id、只能经 test_case/test_set 间接定位的子表；② 所有含 project_id
     * 的业务表（排除 BUSY_IGNORED）。count 用对应口径的 COUNT(*) 子查询。
     */
    @Override
    public Map<String, Long> purgeTargets(long projectId) {
        Map<String, Long> targets = new LinkedHashMap<>();
        // ① 间接子表：经 test_case / test_set 定位
        String caseIds = "(SELECT id FROM test_case WHERE project_id = " + projectId + ")";
        String setIds = "(SELECT id FROM test_set WHERE project_id = " + projectId + ")";
        countInto(targets, "test_case_step", "test_case_id IN " + caseIds);
        countInto(targets, "case_requirement", "test_case_id IN " + caseIds);
        countInto(targets, "case_requirement_version", "test_case_id IN " + caseIds);
        countInto(targets, "case_script_binding", "case_id IN " + caseIds);
        countInto(targets, "manual_execution", "case_id IN " + caseIds);
        countInto(targets, "test_set_item",
                "test_set_id IN " + setIds + " OR test_case_id IN " + caseIds);
        // ② 含 project_id 的业务表
        for (String table : projectIdTables()) {
            if (BUSY_IGNORED.contains(table)) {
                continue;
            }
            countInto(targets, table, "project_id = " + projectId);
        }
        return targets;
    }

    /**
     * FR-ORIGIN-06：级联彻底删除一个项目及其全部业务数据。
     *
     * <p>顺序复用已验证的 {@code purge-temp.sh}（v5.18，处理过 1451 半删）：
     * ① 先清 6 张无 project_id 的间接子表（按 test_case/test_set 定位）；
     * ② 再对所有含 project_id 的业务表做多轮删（解开 run ↔ test_set 相互引用）；
     * ③ 最后删 project 本身及 4 张关系表。事务由上层 {@code ProjectPurgeService} 保证。
     */
    @Override
    public void purgeProject(long projectId) {
        String caseIds = "(SELECT id FROM test_case WHERE project_id = " + projectId + ")";
        String setIds = "(SELECT id FROM test_set WHERE project_id = " + projectId + ")";
        // ① 间接子表
        jdbc.update("DELETE FROM test_case_step WHERE test_case_id IN " + caseIds);
        jdbc.update("DELETE FROM case_requirement WHERE test_case_id IN " + caseIds);
        jdbc.update("DELETE FROM case_requirement_version WHERE test_case_id IN " + caseIds);
        jdbc.update("DELETE FROM case_script_binding WHERE case_id IN " + caseIds);
        jdbc.update("DELETE FROM manual_execution WHERE case_id IN " + caseIds);
        jdbc.update("DELETE FROM test_set_item WHERE test_set_id IN " + setIds
                + " OR test_case_id IN " + caseIds);
        // ② 含 project_id 的业务表，多轮解开相互引用（最多 5 轮，与 purge-temp.sh 一致）
        List<String> tables = projectIdTables();
        for (int round = 0; round < 5; round++) {
            for (String table : tables) {
                if (BUSY_IGNORED.contains(table)) {
                    continue;
                }
                try {
                    jdbc.update("DELETE FROM `" + table + "` WHERE project_id = ?", projectId);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // 仍有更深层引用，下一轮再试；5 轮后由孤儿扫描兜底判失败
                }
            }
        }
        // ③ 关系表 + project（delete() 会清 4 张关系表再删 project）
        delete(projectId);
    }

    /** 删后孤儿扫描：返回仍悬空引用该 project 的表→行数；为空表示级联干净。 */
    @Override
    public Map<String, Long> orphanReferences(long projectId) {
        Map<String, Long> orphans = new LinkedHashMap<>();
        for (String table : projectIdTables()) {
            if (BUSY_IGNORED.contains(table) || "project".equals(table)) {
                continue;
            }
            countInto(orphans, table, "project_id = " + projectId);
        }
        return orphans;
    }

    /** 动态发现所有含 project_id 的表名（与 busyCounts 同源口径）。 */
    private List<String> projectIdTables() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND column_name = 'project_id' "
                        + "ORDER BY table_name",
                String.class);
    }

    /** 安全的 COUNT 填充：表名来自 information_schema 或固定清单，列名固定，无注入面。 */
    private void countInto(Map<String, Long> target, String table, String where) {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE " + where,
                    Long.class);
            if (n != null && n > 0) {
                target.put(table, n);
            }
        } catch (org.springframework.dao.DataAccessException e) {
            // 表/列结构差异（如间接子表被删）时跳过，不影响其余清单
        }
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
        String normalized = normalizeRoleCode(roleCode);
        jdbc.update("""
                INSERT INTO project_member (project_id, user_id, member_role, joined_at,
                                            created_by, updated_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(3), ?, ?)
                """, projectId, userId, normalized, operatorId, operatorId);
        addRole(projectId, userId, normalized, operatorId);
    }

    /**
     * 角色代码写入口统一小写（v5.21.0 / FR-v5.21-06）。
     *
     * <p>库内存量出现过 `viewer` 与 `ENGINEER` 并存：判权侧靠 {@code RoleCode.from()}
     * 的大小写兼容兜住了，但按 role_code 分组统计会分裂成两行、对外导出像两个角色。
     * 迁移 `V11_81` 负责洗存量，这里负责不再写脏。null 保持 null（无角色）。
     */
    private static String normalizeRoleCode(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void addRole(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                INSERT IGNORE INTO project_member_role (project_id, user_id, role_code, created_by)
                VALUES (?, ?, ?, ?)
                """, projectId, userId, normalizeRoleCode(roleCode), operatorId);
    }

    @Override
    public void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId) {
        jdbc.update("""
                UPDATE project_member SET member_role = ?, updated_by = ?
                 WHERE project_id = ? AND user_id = ?
                """, normalizeRoleCode(roleCode), operatorId, projectId, userId);
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
        // V11_43 给本表加了代理自增主键 id：INSERT 分支执行成功后 MySQL 会把
        // LAST_INSERT_ID() 覆盖成自增 id，导致首次分配返回错值（实测返回 6 而非 1）。
        // 因此用 ROW_COUNT() 区分分支（1=新插入，2=命中唯一键走 UPDATE），
        // 新插入固定返回 1；UPDATE 分支仍用语句内 LAST_INSERT_ID(next_no + 1)。
        // 两条语句依赖同一连接，调用方 ProjectService.nextCode 已标注 @Transactional。
        Long value = jdbc.queryForObject(
                "SELECT IF(ROW_COUNT() = 1, 1, LAST_INSERT_ID())", Long.class);
        return value == null ? 1 : value;
    }

    @Override
    public void markDemo(long projectId) {
        // 只动 is_demo 一位：不碰 status / owner / 可见性（FR-v5.9-09 口径）
        jdbc.update("UPDATE project SET is_demo = 1 WHERE id = ?", projectId);
    }

    @Override
    public void setOrigin(long projectId, String origin) {
        // FR-ORIGIN-04：只动 origin 一位。值由 service 层白名单校验（USER/BUILTIN），此处不猜。
        jdbc.update("UPDATE project SET origin = ? WHERE id = ?", origin, projectId);
    }

    @Override
    public boolean codeExists(String code, Long excludeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project WHERE code = ? AND id <> ?
                """, Integer.class, code, excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }
}
