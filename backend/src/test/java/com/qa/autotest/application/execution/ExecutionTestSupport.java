package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** 执行域单测共用的轻量装配：管理员身份 + 固定时钟 + 无审计落库。 */
final class ExecutionTestSupport {

    static final long PROJECT = 7L;

    static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-03T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ExecutionTestSupport() {
    }

    static ExecutionProperties props() {
        return new ExecutionProperties();
    }

    /** 脚本仓储桩：{@code listAll} 返回给定脚本，其余方法按默认值（EXEC-05 测试用）。 */
    static RobotFileRepository scriptRepo(List<RobotFile> files) {
        return proxy(RobotFileRepository.class, (proxy, method, args) ->
                "listAll".equals(method.getName()) ? files : defaultValue(method.getReturnType()));
    }

    static RunService runService(InMemoryRunRepository repo) {
        return new RunService(repo, access(), audit(), new RunIdGenerator(), props(), CLOCK);
    }

    static AccessControlService access() {
        return new AccessControlService(adminUsers(), emptyProjects());
    }

    static AuditService audit() {
        return new AuditService(noopAudit());
    }

    static RunRecord run(String runId, RunState state, Instant startedAt) {
        return new RunRecord(1L, runId, PROJECT, null, state, 0, 1, 0,
                0, 0, 0, null, null, null, null, CLOCK.instant(), startedAt, null, null, null);
    }

    private static UserRepository adminUsers() {
        return proxy(UserRepository.class, (proxy, method, args) ->
                "roleCodesOf".equals(method.getName()) ? List.of("admin") : defaultValue(method.getReturnType()));
    }

    private static ProjectRepository emptyProjects() {
        return proxy(ProjectRepository.class, (proxy, method, args) ->
                defaultValue(method.getReturnType()));
    }

    private static AuditRepository noopAudit() {
        return proxy(AuditRepository.class, (proxy, method, args) ->
                defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }
}
