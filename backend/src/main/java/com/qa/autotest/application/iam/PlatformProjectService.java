package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.domain.iam.Project;
import org.springframework.stereotype.Service;

/**
 * 平台项目（公共库载体，v4.5.0 / FR-v4.6-01）。
 *
 * <p>平台项目是单例行：不出现在普通项目列表、不可停用/删除/改码，承载公共用例、公共资产与模板。
 * 迁移已插入；若因历史库缺失，这里做一次兜底创建（owner 为空，管理员仍可治理）。
 */
@Service
public class PlatformProjectService {

    public static final String CODE = "PLATFORM-LIB";

    private final ProjectRepository projects;
    private volatile Long cachedId;

    public PlatformProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    /** 平台项目 id。 */
    public long id() {
        Long id = cachedId;
        if (id != null) {
            return id;
        }
        synchronized (this) {
            if (cachedId == null) {
                cachedId = projects.platformProject()
                        .map(Project::id)
                        .orElseGet(this::create);
            }
            return cachedId;
        }
    }

    public Project project() {
        long id = id();
        return projects.findById(id).orElseThrow(() ->
                new com.qa.autotest.common.BusinessException(
                        com.qa.autotest.common.ErrorCode.NOT_FOUND, "平台项目不存在"));
    }

    private long create() {
        return projects.insert(new Project(null, CODE, "公共库",
                "平台公共用例、公共资产与模板的载体项目（不可见、不可删）",
                true, null, "private", "robot", "platform", null));
    }
}
