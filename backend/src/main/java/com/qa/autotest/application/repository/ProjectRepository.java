package com.qa.autotest.application.repository;

import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 项目与项目成员仓储。 */
public interface ProjectRepository {

    Optional<Project> findById(long id);

    Optional<Project> findByCode(String code);

    /** 平台项目（公共库载体，kind=platform，单例）。 */
    Optional<Project> platformProject();

    PageResult<Project> page(String keyword, int page, int size);

    /** 全局项目列表（v4.5.0）：普通项目 + 可见性过滤，平台项目默认排除。 */
    PageResult<Project> pageGlobal(String keyword, boolean includePlatform, int page, int size);

    /** 当前用户可见的项目（管理员由上层决定返回全部）。 */
    List<Project> findByMember(long userId);

    long insert(Project project);

    void update(long id, String name, String description, boolean enabled, Long ownerId,
                String visibility, String testKernel, Long updatedBy);

    /** 编码唯一一次变更（FR-v4.4-08）：仅当 code_changed_at 为空时生效。 */
    boolean changeCode(long id, String newCode, String reason, Long operatorId);

    void delete(long id);

    /**
     * 删除前置检查：项目下仍存在的业务数据（类别码 → 行数），只返回计数大于 0 的类别。
     *
     * <p>有任意一类大于 0 时只能停用（归档），不能彻底删除——硬删会撞外键或留下孤儿数据。
     */
    Map<String, Long> busyCounts(long projectId);

    List<ProjectMember> members(long projectId);

    Optional<ProjectMember> findMember(long projectId, long userId);

    /** 成员的全部项目角色（FR-v4.4-01）。 */
    List<String> rolesOf(long projectId, long userId);

    void addMember(long projectId, long userId, String roleCode, Long operatorId);

    void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId);

    /** 覆盖式保存多角色；主角色由调用方归一后传入。 */
    void replaceMemberRoles(long projectId, long userId, List<String> roleCodes,
                            String primaryRole, Long operatorId);

    void removeMember(long projectId, long userId);

    long countMembersWithRole(long projectId, String roleCode);

    /** 项目经理口径的人数：拥有 admin 或 pm 任一项目角色的去重用户数。 */
    long countManagers(long projectId);

    /** 编码序号：原子自增并返回本次序号（v4.4.0 / FR-v4.4-07）。 */
    long nextCodeSequence(String scope);

    boolean codeExists(String code, Long excludeId);
}
