package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXEC-01 手工触发单测：校验、落库、立即派发、RUN_TRIGGER 审计、权限。
 */
class QuickRunServiceTest {

    private static final long PROJECT = ExecutionTestSupport.PROJECT;

    private InMemoryRunRepository repo;
    private RecordingDispatcher dispatcher;
    private List<RunOrigin> origins;
    private List<AuditEntry> audits;
    private QuickRunService service;
    private ExecutionProperties props;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.set(1L, "admin", PROJECT);
        repo = new InMemoryRunRepository();
        dispatcher = new RecordingDispatcher();
        origins = new ArrayList<>();
        audits = new ArrayList<>();
        AuditService audit = auditService(audits);
        props = ExecutionTestSupport.props();
        props.setWorkspaceRoot(workspace.toString());
        writeSuite("suites/a.robot", "PERF-001");
        writeSuite("suites/b.robot", "PERF-002");
        ExecutionRequestPrecheck precheck = new ExecutionRequestPrecheck(
                ExecutionTestSupport.scriptRepo(List.of()), props, new NioFileSystemProbe());
        service = new QuickRunService(
                new RunService(recordingRepo(repo, origins), ExecutionTestSupport.access(), audit,
                        new RunIdGenerator(), props, ExecutionTestSupport.CLOCK),
                null,
                dispatcher,
                ExecutionTestSupport.access(),
                audit,
                props,
                precheck);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("合法提交：落库 manual、立即派发、写 RUN_TRIGGER 审计")
    void 合法提交立即派发() {
        RunRecord run = service.trigger(command("runs-view", "suites/a.robot", "suites/b.robot"));

        assertEquals(RunState.QUEUED, run.state());
        assertEquals(1, dispatcher.runIds.size(), "一次提交只派发一次");
        assertEquals(run.runId(), dispatcher.runIds.get(0));
        assertEquals(RunOrigin.MANUAL, origins.get(0).triggerType());
        assertTrue(origins.get(0).planId() == null, "手工运行不挂计划");

        AuditEntry audit = audits.stream()
                .filter(entry -> "RUN_TRIGGER".equals(entry.action()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 RUN_TRIGGER 审计: " + audits));
        assertEquals(run.runId(), audit.resourceId());
        assertTrue(audit.detail().contains("source=runs-view"), audit.detail());
        assertTrue(audit.detail().contains("suites=2"), audit.detail());
        assertTrue(audit.detail().contains("operator=admin"), audit.detail());
    }

    @Test
    @DisplayName("套件路径为空 / 绝对路径 / .. 一律 400，不落库不派发")
    void 非法套件路径被拒() {
        assertInvalid(new String[0], "至少选择一个套件路径");
        assertInvalid(new String[]{"/etc/passwd"}, "必须是相对路径");
        assertInvalid(new String[]{"../outside.robot"}, "不允许包含 ..");
        assertEquals(0, repo.insertCount());
        assertTrue(dispatcher.runIds.isEmpty());
    }

    @Test
    @DisplayName("同一 runRequestId 重放只落一条 run")
    void runRequestId_幂等() {
        RunRecord first = service.trigger(new QuickRunService.QuickRunCommand(
                PROJECT, null, List.of("suites/a.robot"), null, List.of(),
                null, null, null, null, "req-1", null, "runs-view"));
        RunRecord second = service.trigger(new QuickRunService.QuickRunCommand(
                PROJECT, null, List.of("suites/a.robot"), null, List.of(),
                null, null, null, null, "req-1", null, "runs-view"));

        assertEquals(first.runId(), second.runId());
        assertEquals(1, repo.insertCount());
    }

    @Test
    @DisplayName("访客只有 EXECUTION:READ，手工触发 403")
    void 访客触发被拒() {
        AccessControlService viewerAccess = new AccessControlService(viewerUsers(), viewerProject());
        QuickRunService viewerService = new QuickRunService(
                new RunService(repo, viewerAccess, auditService(audits), new RunIdGenerator(),
                        props, ExecutionTestSupport.CLOCK),
                null, dispatcher, viewerAccess,
                auditService(audits), props,
                new ExecutionRequestPrecheck(
                        ExecutionTestSupport.scriptRepo(List.of()), props, new NioFileSystemProbe()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> viewerService.trigger(command("runs-view", "suites/a.robot")));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertEquals(0, repo.insertCount());
    }

    @Test
    @DisplayName("已到终态的 run 不再派发")
    void 终态不派发() {
        RunRecord done = new RunRecord(1L, "RUN-DONE", PROJECT, null, RunState.FINISHED,
                0, 1, 0, 0, 0, 0, null, null, null, null,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, null, null, null);

        service.dispatch(done, new RunService.CreateRunCommand(
                PROJECT, null, List.of("suites/a.robot"), 1, null, List.of(),
                null, 0, 900, null, null, 0));

        assertTrue(dispatcher.runIds.isEmpty(), "终态 run 不应再次派发");
    }

    @Test
    @DisplayName("套件路径在工作区不存在：400，不落库不派发")
    void 套件不存在被拒() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.trigger(command("runs-view", "suites/missing.robot")));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
        assertEquals(0, repo.insertCount(), "预检失败不得落库");
        assertTrue(dispatcher.runIds.isEmpty(), "预检失败不得派发");
    }

    @Test
    @DisplayName("同名测试跨所选套件：400，不落库")
    void 同名测试被拒() throws IOException {
        writeSuite("suites/dup.robot", "PERF-001");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.trigger(new QuickRunService.QuickRunCommand(
                        PROJECT, null, List.of("suites/a.robot", "suites/dup.robot"), null,
                        List.of(), null, null, null, null, null, List.of("PERF-001"), "cases")));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
        assertEquals(0, repo.insertCount());
        assertTrue(dispatcher.runIds.isEmpty());
    }

    @Test
    @DisplayName("设备范围随运行落库：去重保序、可按 runId 回溯（FR-v0.4-01）")
    void 设备范围落库并可回溯() {
        RunRecord run = service.trigger(new QuickRunService.QuickRunCommand(
                PROJECT, null, List.of("suites/a.robot"), null,
                List.of(" DUT-01/mlx5_0 ", "DUT-02/mlx5_1", "DUT-01/mlx5_0"),
                null, null, null, null, null, null, "runs-view"));

        assertEquals("DUT-01/mlx5_0,DUT-02/mlx5_1", run.deviceScope());
        assertEquals("DUT-01/mlx5_0,DUT-02/mlx5_1",
                repo.findByRunId(PROJECT, run.runId()).orElseThrow().deviceScope(),
                "写库后按 runId 回溯必须拿到同一设备范围");
    }

    @Test
    @DisplayName("未声明设备范围写 NULL，不制造伪造键")
    void 设备范围缺省为空() {
        RunRecord run = service.trigger(command("runs-view", "suites/a.robot"));

        assertTrue(run.deviceScope() == null, "空列表应落 NULL: " + run.deviceScope());
    }

    @Test
    @DisplayName("设备范围超过 512 字符：400，不落库不派发")
    void 设备范围超长被拒() {
        String key = "K".repeat(200);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.trigger(new QuickRunService.QuickRunCommand(
                        PROJECT, null, List.of("suites/a.robot"), null,
                        List.of(key + "-1", key + "-2", key + "-3"),
                        null, null, null, null, null, null, "runs-view")));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("deviceScope"), ex.getMessage());
        assertEquals(0, repo.insertCount(), "超长范围不得落库");
        assertTrue(dispatcher.runIds.isEmpty(), "超长范围不得派发");
    }

    private void writeSuite(String path, String testName) throws IOException {
        Path file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "*** Test Cases ***\n" + testName + "\n    Log    ok\n");
    }

    private void assertInvalid(String[] suites, String message) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.trigger(new QuickRunService.QuickRunCommand(
                        PROJECT, null, List.of(suites), null, List.of(),
                        null, null, null, null, null, null, "runs-view")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains(message), ex.getMessage());
    }

    @Test
    @DisplayName("单用例超时：本次触发可覆盖，超上限直接拒绝（EXEC-12）")
    void 单用例超时归一与上限() {
        RunRecord ok = service.trigger(new QuickRunService.QuickRunCommand(
                PROJECT, null, List.of("suites/a.robot"), null, List.of(),
                null, null, 120, null, null, null, "runs-view"));
        assertEquals(RunState.QUEUED, ok.state());
        assertEquals(1, dispatcher.runIds.size());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.trigger(new QuickRunService.QuickRunCommand(
                        PROJECT, null, List.of("suites/a.robot"), null, List.of(),
                        null, null, 86401, null, null, null, "runs-view")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("86400"), ex.getMessage());
        assertEquals(1, repo.insertCount(), "拒绝发生在落库之前");
    }

    @Test
    @DisplayName("单用例超时归一：空 / 非正数走全局默认，合法值原样保留（EXEC-12）")
    void 单用例超时归一规则() {
        assertEquals(0, RunService.normalizeTestTimeout(null));
        assertEquals(0, RunService.normalizeTestTimeout(0));
        assertEquals(0, RunService.normalizeTestTimeout(-5));
        assertEquals(120, RunService.normalizeTestTimeout(120));
        assertEquals(86400, RunService.normalizeTestTimeout(86400));
    }

    private static QuickRunService.QuickRunCommand command(String source, String... suites) {
        return new QuickRunService.QuickRunCommand(PROJECT, null, List.of(suites), null, List.of(),
                null, null, null, null, null, null, source);
    }

    private static AuditService auditService(List<AuditEntry> sink) {
        AuditRepository repository = new AuditRepository() {
            @Override
            public void insert(Long projectId, Long actorId, String action, String resourceType,
                               String resourceId, String detail) {
                // 旧的插入口不携带 operator/summary，本测试只走 insertEntry
            }

            @Override
            public List<String> recentActions(Long projectId, int limit) {
                return List.of();
            }

            @Override
            public void insertEntry(AuditEntry entry) {
                sink.add(entry);
            }
        };
        return new AuditService(repository, Runnable::run);
    }

    /** 记录 insert 的 RunOrigin，其余委托给内存仓储。 */
    private static RunRepository recordingRepo(RunRepository delegate, List<RunOrigin> sink) {
        return (RunRepository) Proxy.newProxyInstance(
                RunRepository.class.getClassLoader(), new Class<?>[]{RunRepository.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName()) && args != null && args.length == 5) {
                        sink.add((RunOrigin) args[4]);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
    }

    private static UserRepository viewerUsers() {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("roleCodesOf".equals(method.getName())) {
                        return List.of("viewer");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ProjectRepository viewerProject() {
        Project project = new Project(PROJECT, "demo", "演示项目", null, true, 1L, "private");
        ProjectMember member = new ProjectMember(1L, PROJECT, 1L, "viewer", "viewer", "访客", true);
        return (ProjectRepository) Proxy.newProxyInstance(
                ProjectRepository.class.getClassLoader(), new Class<?>[]{ProjectRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(project);
                    case "findMember" -> Optional.of(member);
                    default -> defaultValue(method.getReturnType());
                });
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
        if (Optional.class.equals(type)) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }

    /** 只记录提交，不真正执行。 */
    private static final class RecordingDispatcher implements RunDispatcher {
        private final List<String> runIds = new ArrayList<>();

        @Override
        public void submit(long projectId, Long planId, String runId, int priority, Runnable task) {
            runIds.add(runId);
        }
    }
}
