package com.qa.autotest.application.iam;

/**
 * 项目可见性与有效角色的**唯一 SQL 真源**（v5.9.0 / FR-v5.9-04 / W59-05）。
 *
 * <p>背景：判权与列表曾经各写一份「是不是这个项目的人」——列表认 {@code project_member}，
 * 判权也认 {@code project_member}，而 owner、用户组授权这两条真实归属在两边都不认。
 * 症状是「我参与的项目只有一个」（实际 3 个），以及「界面显示角色被提升、实际仍 403」。
 *
 * <p>现在三处（列表 {@code findByMember}、资源级校验 {@code requireProjectAccess}、
 * 有效角色 {@code effectiveRoles}）都从这里取片段，改口径只改一处。单测
 * {@code ProjectVisibilitySqlTest} 断言 {@code project_member} 字面量在 iam 包内只出现在本文件。
 *
 * <p>三个占位符都是 {@code user_id}，顺序：成员、负责人（owner）、用户组。
 */
public final class ProjectVisibilitySql {

    /**
     * 可见项目 id 的三源并集。
     *
     * <p>{@code UNION}（不是 {@code UNION ALL}）：同一项目多源命中只出现一次——
     * 「我参与的」列表不允许同一项目出现两行。
     */
    public static final String VISIBLE_PROJECT_IDS = """
            SELECT m.project_id AS project_id
              FROM project_member m
             WHERE m.user_id = ?
            UNION
            SELECT p.id AS project_id
              FROM project p
             WHERE p.owner_id = ?
            UNION
            SELECT r.project_id AS project_id
              FROM project_group_role r
              JOIN user_group_member gm ON gm.group_id = r.group_id
             WHERE gm.user_id = ?
            """;

    /**
     * 项目内有效角色码的三源并集：直接成员角色 ∪ 组授权角色（owner 隐含 PM）。
     *
     * <p>owner 不读 {@code project_member_role}：转移 owner 后旧 owner 的行可能残留，
     * 而「负责人」这个事实只有 {@code project.owner_id} 一处权威。
     */
    public static final String EFFECTIVE_ROLE_CODES = """
            SELECT mr.role_code AS role_code
              FROM project_member_role mr
             WHERE mr.project_id = ? AND mr.user_id = ?
            UNION
            SELECT 'pm' AS role_code
              FROM project p
             WHERE p.id = ? AND p.owner_id = ?
            UNION
            SELECT r.role_code AS role_code
              FROM project_group_role r
              JOIN user_group_member gm ON gm.group_id = r.group_id
             WHERE r.project_id = ? AND gm.user_id = ?
            """;

    /** 归属来源（取优先级最高者）：OWNER > MEMBER > GROUP，与 {@link #VISIBLE_PROJECT_IDS} 同源。 */
    public static final String VISIBLE_SOURCES = """
            SELECT x.project_id AS project_id,
                   CASE MIN(x.rank_no) WHEN 1 THEN 'OWNER' WHEN 2 THEN 'MEMBER' ELSE 'GROUP' END AS source
              FROM (
                    SELECT p.id AS project_id, 1 AS rank_no FROM project p WHERE p.owner_id = ?
                    UNION ALL
                    SELECT m.project_id AS project_id, 2 AS rank_no FROM project_member m WHERE m.user_id = ?
                    UNION ALL
                    SELECT r.project_id AS project_id, 3 AS rank_no
                      FROM project_group_role r
                      JOIN user_group_member gm ON gm.group_id = r.group_id
                     WHERE gm.user_id = ?
                   ) x
             GROUP BY x.project_id
            """;

    /**
     * 用户所属用户组（v5.9.0 / FR-v5.9-05 / W59-16）。
     *
     * <p>关系聚合接口的三个维度都从这里取片段：用户页「所属组」、组页「成员」、
     * 项目关联页「组授权」共用同一份表语义，避免三处各写一份 count。
     */
    public static final String GROUPS_OF_USER = """
            SELECT g.id AS id, g.name AS name
              FROM user_group g
              JOIN user_group_member gm ON gm.group_id = g.id
             WHERE gm.user_id = ?
             ORDER BY g.id
            """;

    /**
     * 用户参与的项目 + 归属来源（成员/负责人/组授权），带来源便于界面显示「为什么我能看到它」。
     *
     * <p>{@code kind <> 'platform'}：平台库不是「参与的项目」，与列表口径一致。
     */
    public static final String PROJECTS_OF_USER = """
            SELECT p.id AS id, p.code AS code, p.name AS name, s.source AS source
              FROM (
            """
            + VISIBLE_SOURCES
            + """
                   ) s
              JOIN project p ON p.id = s.project_id
             WHERE p.kind <> 'platform'
             ORDER BY p.id
            """;

    /** 组成员（关系聚合 group 维度）。 */
    public static final String MEMBERS_OF_GROUP = """
            SELECT u.id AS id, u.username AS username, u.display_name AS display_name
              FROM app_user u
              JOIN user_group_member gm ON gm.user_id = u.id
             WHERE gm.group_id = ?
             ORDER BY u.id
            """;

    /** 组已授权的项目及角色（只取标准项目）。 */
    public static final String PROJECTS_OF_GROUP = """
            SELECT p.id AS id, p.code AS code, p.name AS name, r.role_code AS role_code
              FROM project_group_role r
              JOIN project p ON p.id = r.project_id
             WHERE r.group_id = ? AND p.kind <> 'platform'
             ORDER BY p.id
            """;

    /** 项目上的组授权（关系聚合 project 维度）。 */
    public static final String GROUPS_OF_PROJECT = """
            SELECT g.id AS id, g.name AS name, r.role_code AS role_code
              FROM project_group_role r
              JOIN user_group g ON g.id = r.group_id
             WHERE r.project_id = ?
             ORDER BY g.id
            """;

    /**
     * 项目关联的人（负责人 ∪ 直接成员 ∪ 组授权成员），带来源优先级。
     *
     * <p>{@code MIN(rank_no)}：同一人既在组里又是直接成员时只出现一行，
     * 来源取优先级最高的那条（1=负责人，2=成员，3=组授权），与 {@link #VISIBLE_SOURCES} 同序。
     */
    public static final String USERS_OF_PROJECT = """
            SELECT u.id AS id, u.username AS username, u.display_name AS display_name,
                   MIN(x.rank_no) AS rank_no
              FROM (
                    SELECT p.owner_id AS user_id, 1 AS rank_no FROM project p WHERE p.id = ?
                    UNION ALL
                    SELECT m.user_id AS user_id, 2 AS rank_no FROM project_member m WHERE m.project_id = ?
                    UNION ALL
                    SELECT gm.user_id AS user_id, 3 AS rank_no
                      FROM project_group_role r
                      JOIN user_group_member gm ON gm.group_id = r.group_id
                     WHERE r.project_id = ?
                   ) x
              JOIN app_user u ON u.id = x.user_id
             GROUP BY u.id, u.username, u.display_name
             ORDER BY u.id
            """;

    private ProjectVisibilitySql() {
    }
}
