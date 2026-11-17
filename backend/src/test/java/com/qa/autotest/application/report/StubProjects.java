package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.domain.iam.Project;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * 测试用 {@link ProjectRepository} 最小桩（v5.22.0 / W72-02）。
 *
 * <p>渲染器只为「把 projectName 渲染成真名」需要一个读项目名的入口。让报告单测起真库不合适，
 * 让每个测试文件各写一套动态代理又太啰嗦——放在这里只回答 {@code findById}，
 * 其余方法一律给类型默认值（调用到它们说明用例走偏了，但不会因为 NPE 掩盖真正的问题）。
 */
final class StubProjects {

    private StubProjects() {
    }

    /** 指定「某个 id 对应某个项目名」，其余 id 一律查不到（验证回退分支）。 */
    static ProjectRepository withName(long projectId, String name) {
        return proxy(id -> id == projectId
                ? Optional.of(new Project(projectId, "STUB", name, "", true, null, "private",
                        "robot"))
                : Optional.empty());
    }

    /** 全空桩：任何 id 都查不到，用于只关心「不崩」的用例。 */
    static ProjectRepository empty() {
        return proxy(id -> Optional.empty());
    }

    private static ProjectRepository proxy(LongFunction<Optional<Project>> finder) {
        InvocationHandler handler = (proxyInstance, method, args) -> {
            if ("findById".equals(method.getName()) && args != null && args.length == 1) {
                return finder.apply(((Number) args[0]).longValue());
            }
            return defaultValue(method.getReturnType());
        };
        return (ProjectRepository) Proxy.newProxyInstance(
                ProjectRepository.class.getClassLoader(),
                new Class<?>[]{ProjectRepository.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class || type == short.class || type == byte.class) {
            return type == long.class ? 0L : 0;
        }
        if (type == double.class || type == float.class) {
            return 0.0;
        }
        if (Optional.class.equals(type)) {
            return Optional.empty();
        }
        if (List.class.equals(type)) {
            return List.of();
        }
        return null;
    }
}
