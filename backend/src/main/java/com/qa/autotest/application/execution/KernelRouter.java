package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.domain.iam.Project;
import org.springframework.stereotype.Component;

/**
 * 项目内核查询（FR-UIE-09 的判据来源）。
 *
 * <p>为什么单抽一个接口而不是直接注入 {@link ProjectRepository}：用例集执行只需要
 * 「这个项目是 pytest 还是 robot」一个事实，直接依赖项目仓储会把 20 多个方法拖进
 * 测试桩里；一个方法的路由器让测试只需写 {@code id -> "robot"} 一行。
 *
 * <p>未登记项目按 robot 兜底：与 {@code KernelSelector} 的历史口径一致（run 表
 * 未建模内核时也按 robot 读）。
 */
public interface KernelRouter {

    /** 项目声明的测试内核；查不到时返回 {@code robot}。 */
    String kernelOf(long projectId);

    @Component
    class ProjectKernelRouter implements KernelRouter {

        private final ProjectRepository projects;

        public ProjectKernelRouter(ProjectRepository projects) {
            this.projects = projects;
        }

        @Override
        public String kernelOf(long projectId) {
            return projects.findById(projectId)
                    .map(Project::testKernel)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse("robot");
        }
    }
}
