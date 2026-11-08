package com.qa.autotest.application.repository;

import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import java.util.List;
import java.util.Optional;

/** 项目与项目成员仓储。 */
public interface ProjectRepository {

    Optional<Project> findById(long id);

    Optional<Project> findByCode(String code);

    PageResult<Project> page(String keyword, int page, int size);

    /** 当前用户可见的项目（管理员由上层决定返回全部）。 */
    List<Project> findByMember(long userId);

    long insert(Project project);

    void update(long id, String name, String description, boolean enabled, Long ownerId,
                String visibility, String testKernel, Long updatedBy);

    void delete(long id);

    List<ProjectMember> members(long projectId);

    Optional<ProjectMember> findMember(long projectId, long userId);

    void addMember(long projectId, long userId, String roleCode, Long operatorId);

    void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId);

    void removeMember(long projectId, long userId);

    boolean codeExists(String code, Long excludeId);
}
