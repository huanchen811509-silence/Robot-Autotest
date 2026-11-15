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

    private ProjectVisibilitySql() {
    }
}
