package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户 / 用户组 / 项目三向关系聚合（v5.9.0 / FR-v5.9-05 / W59-16）。
 *
 * <p>为什么要有这一层：用户组页、用户页、项目关联页都要显示「关联了几个」。
 * 三处各写一条 count 的结果是**同一事实三个数字**——组页说 3 个项目、项目页说 5 个人，
 * 中间差了「owner 不是成员」「组授权不算成员」这类口径差。这里把读路径收敛成一处：
 * 三页的数字、点开后的列表、判权用的可见性 SQL 全部来自 {@link ProjectVisibilitySql}。
 *
 * <p>排序稳定（一律 {@code ORDER BY id}）故同一请求两次响应逐字节相同，可直接 diff。
 */
@Service
public class IamRelationService {

    private static final String DIM_USER = "user";
    private static final String DIM_GROUP = "group";
    private static final String DIM_PROJECT = "project";

    private final JdbcTemplate jdbc;
    private final ProjectRepository projects;

    public IamRelationService(JdbcTemplate jdbc, ProjectRepository projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    public Map<String, Object> of(String dimension, long id) {
        String dim = dimension == null ? "" : dimension.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (dim) {
            case DIM_USER -> byUser(id);
            case DIM_GROUP -> byGroup(id);
            case DIM_PROJECT -> byProject(id);
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "未知 dimension：" + dimension + "（仅支持 user / group / project）");
        };
    }

    private Map<String, Object> byUser(long userId) {
        List<Map<String, Object>> groups = rows(ProjectVisibilitySql.GROUPS_OF_USER, userId);
        List<Map<String, Object>> projectRows = new ArrayList<>();
        for (Map<String, Object> row : rows(ProjectVisibilitySql.PROJECTS_OF_USER, userId)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            long projectId = ((Number) row.get("id")).longValue();
            // 角色码取判权同源（成员 ∪ owner 隐含 pm ∪ 组授权），界面显示的就是真实生效的。
            item.put("roleCodes", List.copyOf(projects.effectiveRoleCodes(projectId, userId)));
            projectRows.add(item);
        }
        return envelope(DIM_USER, userId, groups, List.of(), projectRows);
    }

    private Map<String, Object> byGroup(long groupId) {
        List<Map<String, Object>> users = rows(ProjectVisibilitySql.MEMBERS_OF_GROUP, groupId);
        // 列名归一为 camelCase：DB 是 role_code / display_name，接口一律 roleCode / displayName，
        // 前端三个页面就不必各记一套列名（这也是「信息幂等」的一部分）。
        List<Map<String, Object>> projectRows = new ArrayList<>();
        for (Map<String, Object> row : rows(ProjectVisibilitySql.PROJECTS_OF_GROUP, groupId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("code", row.get("code"));
            item.put("name", row.get("name"));
            item.put("roleCode", row.get("role_code"));
            projectRows.add(item);
        }
        return envelope(DIM_GROUP, groupId, List.of(), users, projectRows);
    }

    private Map<String, Object> byProject(long projectId) {
        List<Map<String, Object>> groupRows = new ArrayList<>();
        for (Map<String, Object> row : rows(ProjectVisibilitySql.GROUPS_OF_PROJECT, projectId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("roleCode", row.get("role_code"));
            groupRows.add(item);
        }
        List<Map<String, Object>> groups = groupRows;
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> row : rows(ProjectVisibilitySql.USERS_OF_PROJECT, projectId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("username", row.get("username"));
            item.put("displayName", row.get("display_name"));
            item.put("source", sourceOf(((Number) row.get("rank_no")).intValue()));
            item.put("roleCodes", List.copyOf(projects.effectiveRoleCodes(projectId,
                    ((Number) row.get("id")).longValue())));
            users.add(item);
        }
        return envelope(DIM_PROJECT, projectId, groups, users, List.of());
    }

    /** 来源码与 {@link ProjectVisibilitySql} 的 rank_no 同序：1=负责人，2=成员，3=组授权。 */
    private static String sourceOf(int rankNo) {
        return switch (rankNo) {
            case 1 -> "OWNER";
            case 2 -> "MEMBER";
            default -> "GROUP";
        };
    }

    /**
     * 统一响应外壳：三个方向恒在（未用到的方向返回空数组），前端不必按 dimension 分支判空。
     *
     * <p>{@code counts} 只统计本方向真正有意义的两个维度，避免出现「用户维度 users=0」这种
     * 看起来像缺陷的读数。
     */
    private static Map<String, Object> envelope(String dimension, long id,
                                                List<Map<String, Object>> groups,
                                                List<Map<String, Object>> users,
                                                List<Map<String, Object>> projects) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dimension", dimension);
        body.put("id", id);
        body.put("groups", groups);
        body.put("users", users);
        body.put("projects", projects);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("groups", groups.size());
        counts.put("users", users.size());
        counts.put("projects", projects.size());
        body.put("counts", counts);
        return body;
    }

    /**
     * 各 SQL 的占位符个数不同（1 个或 3 个，且都是同一个 id）：按 `?` 个数裁剪参数，
     * 多传会被 JdbcTemplate 以「参数个数不匹配」拒绝，少传则是 SQL 语法错误。
     */
    private List<Map<String, Object>> rows(String sql, long id) {
        int slots = (int) sql.chars().filter(ch -> ch == '?').count();
        Object[] args = new Object[slots];
        java.util.Arrays.fill(args, id);
        return jdbc.queryForList(sql, args);
    }
}
