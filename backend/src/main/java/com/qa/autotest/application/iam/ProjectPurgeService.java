package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.Project;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户自建项目的级联彻底删除（FR-ORIGIN-06/07）。
 *
 * <p>与 {@link ProjectService#delete(long)} 的「只清关系表、有数据 409」不同：本服务面向
 * {@code origin=USER} 的临时测试项目，按外键顺序级联清理全部业务子表后再删项目。
 * BUILTIN 与 platform 项目一律拒绝，保持「程序自带按老规则」口径。
 *
 * <p><b>事务是硬要求</b>（v5.17 1451 半删教训）：整个「间接子表 → 多轮业务表 → project」
 * 在同一事务里；任何外键冲突整体回滚，不留「成员已删、项目还在」的半删状态。
 */
@Service
public class ProjectPurgeService {

    private final ProjectRepository projects;
    private final AccessControlService access;
    private final AuditService audit;

    public ProjectPurgeService(ProjectRepository projects, AccessControlService access,
                               AuditService audit) {
        this.projects = projects;
        this.access = access;
        this.audit = audit;
    }

    /** 级联删除预览（FR-ORIGIN-07）：只读，返回逐表条数。 */
    public Map<String, Long> preview(long projectId) {
        Project p = requirePurgeable(projectId);
        return projects.purgeTargets(projectId);
    }

    /** 级联彻底删除（FR-ORIGIN-06）：事务内完成，随后孤儿扫描；有孤儿则回滚。 */
    @Transactional
    @Audited(action = "project:purge", resource = "project")
    public void purge(long projectId) {
        Project p = requirePurgeable(projectId);
        Map<String, Long> before = projects.purgeTargets(projectId);
        projects.purgeProject(projectId);
        Map<String, Long> orphans = projects.orphanReferences(projectId);
        if (!orphans.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "级联删除后仍有孤儿引用，事务回滚：" + orphans);
        }
        audit.recordChange("project:purge", "project", String.valueOf(projectId),
                "code=" + p.code() + "; name=" + p.name() + "; tables=" + before.keySet(),
                null);
    }

    /** 可否删除的前置校验：ADMIN + 非 platform + origin=USER；任一不满足即拒绝。 */
    private Project requirePurgeable(long projectId) {
        access.bindProject(projectId);
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以级联删除项目");
        }
        Project p = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (p.platform()) {
            throw new BusinessException(ErrorCode.CONFLICT, "平台项目不可删除");
        }
        if (!p.userOwned()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "程序自带项目只能停用（归档），不能彻底删除");
        }
        return p;
    }
}
