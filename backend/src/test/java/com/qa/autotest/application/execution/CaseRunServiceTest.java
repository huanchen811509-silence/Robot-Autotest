package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.FileNode;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXEC-02 勾选台账用例执行：绑定解析到测试级、未绑定整单 400、权限与不存在映射。
 */
class CaseRunServiceTest {

    private static final long PROJECT = 7L;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", PROJECT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("全部绑定：套件去重，测试名进 --test，source=cases，testSet 为空")
    void 全部绑定触发() {
        Map<Long, TestCase> cases = Map.of(
                11L, testCase(11L, "PERF-001"),
                12L, testCase(12L, "PERF-002"));
        Map<String, String> files = Map.of(
                "suites/fallback.robot", """
                        *** Test Cases ***
                        PERF-001
                            Log    ok
                        """,
                "suites/tagged.robot", """
                        *** Test Cases ***
                        200G 带宽压测
                            [Tags]    case:PERF-002
                            Log    ok
                        """);
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(files)), quick);

        service.run(command(List.of(11L, 12L)));

        assertEquals(List.of("suites/fallback.robot", "suites/tagged.robot"), quick.last.suitePaths());
        assertEquals(List.of("PERF-001", "200G 带宽压测"), quick.last.testNames());
        assertEquals("cases", quick.last.source());
        assertNull(quick.last.testSetId());
    }

    @Test
    @DisplayName("存在未绑定用例：400 并列出编号，不触发")
    void 未绑定拒绝() {
        Map<Long, TestCase> cases = Map.of(
                11L, testCase(11L, "PERF-001"),
                13L, testCase(13L, "PERF-003"));
        Map<String, String> files = Map.of(
                "suites/fallback.robot", """
                        *** Test Cases ***
                        PERF-001
                            Log    ok
                        """);
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(files)), quick);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.run(command(List.of(11L, 13L))));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("PERF-003"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1 条"), ex.getMessage());
        assertTrue(quick.last == null, "有未绑定用例时不得触发执行");
    }

    @Test
    @DisplayName("用例不存在 404；空选 400")
    void 不存在与空选() {
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(Map.of()), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), quick);

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.run(command(List.of(99L))));
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode());

        BusinessException empty = assertThrows(BusinessException.class,
                () -> service.run(command(List.of())));
        assertEquals(ErrorCode.INVALID_ARGUMENT, empty.errorCode());
    }

    @Test
    @DisplayName("G2-3：L2 用例在执行入口整单拒绝，并列出编号与安全级")
    void L2被拦截() {
        Map<Long, TestCase> cases = Map.of(
                11L, testCase(11L, "PERF-001", "L1"),
                12L, testCase(12L, "DESTRUCT-001", "L2"));
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), quick);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.run(command(List.of(11L, 12L))));

        assertEquals(ErrorCode.SAFETY_LEVEL_BLOCKED, ex.errorCode());
        assertTrue(ex.getMessage().contains("DESTRUCT-001(L2)"), ex.getMessage());
        assertTrue(quick.last == null, "被安全级拦截时不得触发执行");
    }

    @Test
    @DisplayName("G2-3：LX 未判定按 L2 对待，同样拒执行；补判为 L1 后放行")
    void LX被拦截补判后放行() {
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService blocked = new CaseRunService(
                repo(Map.of(11L, testCase(11L, "PERF-001", "LX"))),
                ExecutionTestSupport.access(), new CaseScriptResolver(files(Map.of())), quick);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> blocked.run(command(List.of(11L))));
        assertEquals(ErrorCode.SAFETY_LEVEL_BLOCKED, ex.errorCode());
        assertTrue(ex.getMessage().contains("PERF-001(LX)"), ex.getMessage());
        assertTrue(quick.last == null);

        Map<String, String> files = Map.of("suites/fallback.robot", """
                *** Test Cases ***
                PERF-001
                    Log    ok
                """);
        CaseRunService allowed = new CaseRunService(
                repo(Map.of(11L, testCase(11L, "PERF-001", "L1"))),
                ExecutionTestSupport.access(), new CaseScriptResolver(files(files)), quick);
        allowed.run(command(List.of(11L)));
        assertEquals(List.of("PERF-001"), quick.last.testNames());
    }


    @Test
    @DisplayName("G2-3 预检：跨页选中的 L2 / LX 逐条列出，allowed=false，不触发执行")
    void 预检列出被拦用例() {
        Map<Long, TestCase> cases = Map.of(
                11L, testCase(11L, "PERF-001", "L1"),
                12L, testCase(12L, "DESTRUCT-001", "L2"),
                13L, testCase(13L, "UNKNOWN-001", "LX"));
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), quick);

        CaseRunService.SafetyPrecheck result = service.precheck(PROJECT, List.of(11L, 12L, 13L));

        assertEquals(3, result.total());
        assertTrue(!result.allowed(), "含 L2 时默认必须判为不可执行");
        // v5.31.0 / FR-v5.31-07：L2 与 LX 分列 —— L2 阻断，LX 进 unrated（默认 block 模式下仍不放行）
        assertEquals(List.of("DESTRUCT-001"),
                result.blocked().stream().map(CaseRunService.BlockedCase::caseCode).toList());
        assertEquals(List.of("UNKNOWN-001"),
                result.unrated().stream().map(CaseRunService.BlockedCase::caseCode).toList());
        assertTrue(quick.last == null, "预检是只读的，不得触发执行");
    }

    @Test
    @DisplayName("v5.31 FR-v5.31-08：未评审计数随选择返回，默认只提示不阻断")
    void 未评审软提示计数() {
        Map<Long, TestCase> cases = Map.of(
                21L, testCase(21L, "REV-001", "L1", "approved"),
                22L, testCase(22L, "REV-002", "L1", "draft"),
                23L, testCase(23L, "REV-003", "L1", "pending"));
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), new RecordingQuickRun());

        CaseRunService.SafetyPrecheck result = service.precheck(PROJECT, List.of(21L, 22L, 23L));

        assertEquals(3, result.total());
        assertEquals(2, result.unreviewed(), "draft + pending 计 2，approved 不计");
        assertTrue(result.allowed(), "默认 require-approved=false，评审态不得阻断");
        assertTrue(!result.requireApproved(), "预检回传开关状态，前端据此判断提示口径");
    }

    @Test
    @DisplayName("v5.31 FR-v5.31-08：require-approved 打开后未批准用例被拦，已批准仍可跑")
    void 评审门禁开关() {
        Map<Long, TestCase> cases = Map.of(
                21L, testCase(21L, "REV-001", "L1", "approved"),
                22L, testCase(22L, "REV-002", "L1", "draft"));
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), quick);
        service.setRequireApproved(true);

        CaseRunService.SafetyPrecheck blocked = service.precheck(PROJECT, List.of(21L, 22L));
        assertTrue(!blocked.allowed(), "含 draft 时必须判为不可执行");
        assertTrue(blocked.requireApproved());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.run(command(List.of(21L, 22L))));
        assertEquals(ErrorCode.SAFETY_LEVEL_BLOCKED, ex.errorCode());
        assertTrue(ex.getMessage().contains("未通过评审"), ex.getMessage());
        assertTrue(quick.last == null, "被拦时不得派发");

        assertTrue(service.precheck(PROJECT, List.of(21L)).allowed(), "全为 approved 时放行");
    }

    @Test
    @DisplayName("v5.31 FR-v5.31-07：confirm 模式下 LX 可放行，但必须带确认凭据")
    void 未定级知情确认() {
        Map<Long, TestCase> cases = Map.of(13L, testCase(13L, "UNKNOWN-001", "LX"));
        RecordingQuickRun quick = new RecordingQuickRun();
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), quick);
        service.setUnratedMode("confirm");

        CaseRunService.SafetyPrecheck pre = service.precheck(PROJECT, List.of(13L));
        assertTrue(pre.allowed(), "confirm 模式下预检应放行未定级用例");
        assertEquals(1, pre.unrated().size());

        // 不带确认凭据 → 400，且不产生执行
        assertThrows(com.qa.autotest.common.BusinessException.class,
                () -> service.run(command(List.of(13L))));
        assertTrue(quick.last == null, "未确认时不得下发执行");

        // 带确认凭据 → 越过安全闸（后续可能因未绑定脚本 400，但绝不能是「需显式确认」）
        CaseRunService.CaseRunCommand confirmed = new CaseRunService.CaseRunCommand(
                PROJECT, List.of(13L), null, null, null, null, null, null, null, true);
        try {
            service.run(confirmed);
        } catch (com.qa.autotest.common.BusinessException ex) {
            assertTrue(!String.valueOf(ex.getMessage()).contains("需显式确认"),
                    "确认后不应再被知情确认拦截，实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("G2-3 预检：全 L1 放行 allowed=true；空选 400")
    void 预检全L1放行() {
        Map<Long, TestCase> cases = Map.of(
                11L, testCase(11L, "PERF-001", "L1"),
                12L, testCase(12L, "PERF-002", "L0"));
        CaseRunService service = new CaseRunService(repo(cases), ExecutionTestSupport.access(),
                new CaseScriptResolver(files(Map.of())), new RecordingQuickRun());

        CaseRunService.SafetyPrecheck result = service.precheck(PROJECT, List.of(11L, 12L));

        assertEquals(2, result.total());
        assertTrue(result.allowed());
        assertTrue(result.blocked().isEmpty());

        BusinessException empty = assertThrows(BusinessException.class,
                () -> service.precheck(PROJECT, List.of()));
        assertEquals(ErrorCode.INVALID_ARGUMENT, empty.errorCode());
    }

    @Test
    @DisplayName("访客只有 EXECUTION:READ，执行所选 403")
    void 访客被拒() {
        RecordingQuickRun quick = new RecordingQuickRun();
        AccessControlService viewer = new AccessControlService(viewerUsers(), viewerProject());
        CaseRunService service = new CaseRunService(
                repo(Map.of(11L, testCase(11L, "PERF-001"))),
                viewer, new CaseScriptResolver(files(Map.of())), quick);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.run(command(List.of(11L))));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertTrue(quick.last == null);
    }

    private static CaseRunService.CaseRunCommand command(List<Long> ids) {
        return new CaseRunService.CaseRunCommand(PROJECT, ids, 3L, List.of(), 2, 600, null, null, null);
    }

    private static TestCase testCase(long id, String code) {
        return testCase(id, code, "L1");
    }

    private static TestCase testCase(long id, String code, String safetyLevel) {
        return testCase(id, code, safetyLevel, "draft");
    }

    private static TestCase testCase(long id, String code, String safetyLevel, String reviewStatus) {
        return new TestCase(id, PROJECT, code, code, null, null, null, null,
                null, reviewStatus, null, null, null, safetyLevel);
    }

    private static TestCaseRepository repo(Map<Long, TestCase> cases) {
        return (TestCaseRepository) Proxy.newProxyInstance(
                TestCaseRepository.class.getClassLoader(), new Class<?>[]{TestCaseRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> Optional.ofNullable(cases.get((Long) args[1]));
                    case "page" -> PageResult.empty(1, 20);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static RobotFileRepository files(Map<String, String> files) {
        return (RobotFileRepository) Proxy.newProxyInstance(
                RobotFileRepository.class.getClassLoader(), new Class<?>[]{RobotFileRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "nodes" -> files.keySet().stream()
                            .map(path -> new FileNode(path, false, 0, Instant.EPOCH)).toList();
                    case "findByPath" -> {
                        String path = (String) args[1];
                        String content = files.get(path);
                        yield content == null ? Optional.empty()
                                : Optional.of(new RobotFile(1L, PROJECT, path, content, "sha", 1));
                    }
                    default -> defaultValue(method.getReturnType());
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

    /** 记录传给手工触发命令，不真正建 run。 */
    private static final class RecordingQuickRun extends QuickRunService {
        private QuickRunService.QuickRunCommand last;

        private RecordingQuickRun() {
            super(null, null, null, null, null, ExecutionTestSupport.props(),
                    new ExecutionRequestPrecheck(
                        ExecutionTestSupport.scriptRepo(List.of()),
                        ExecutionTestSupport.props(), new NioFileSystemProbe()));
        }

        @Override
        public RunRecord trigger(QuickRunService.QuickRunCommand cmd) {
            this.last = cmd;
            return new RunRecord(1L, "RUN-1", PROJECT, null, RunState.QUEUED,
                    0, 1, 0, 0, 0, 0, null, null, null, null, Instant.EPOCH, null, null, null, null);
        }
    }
}
