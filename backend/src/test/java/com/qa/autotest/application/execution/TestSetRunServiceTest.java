package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.FileNode;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXEC-01 / EXEC-03 / EXEC-04 用例集立即执行：编号 → 套件与测试解析、测试级执行、空编号与未绑定拒绝。
 */
class TestSetRunServiceTest {

    private static final long PROJECT = 7L;
    private static final long SET_ID = 12L;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", PROJECT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("全部用例已绑定：按用例序号解析套件路径并触发")
    void 全部绑定触发() {
        List<TestCase> cases = List.of(testCase("PERF-001"), testCase("PERF-002"));
        Map<String, String> files = Map.of(
                "suites/perf.robot", robotFile("""
                        *** Settings ***
                        Resource    common.robot

                        *** Test Cases ***
                        PERF-001
                            执行压测    ${1}
                        """),
                "suites/extra.robot", robotFile("""
                        *** Test Cases ***
                        PERF-002
                            Log    hello
                        """));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, files, quick);

        RunRecord run = service.run(command());

        assertEquals(List.of("suites/perf.robot", "suites/extra.robot"), quick.last.suitePaths());
        assertEquals(List.of("PERF-001", "PERF-002"), quick.last.testNames());
        assertEquals(SET_ID, quick.last.testSetId());
        assertEquals("test-set", quick.last.source());
    }

    @Test
    @DisplayName("混合集合：存在未绑定用例 → 400 并列出编号，不触发")
    void 未绑定用例拒绝() {
        List<TestCase> cases = List.of(testCase("PERF-001"), testCase("PERF-003"));
        Map<String, String> files = Map.of(
                "suites/perf.robot", robotFile("""
                        *** Test Cases ***
                        PERF-001
                            Log    ok
                        """));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, files, quick);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.run(command()));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("PERF-003"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1 条"), ex.getMessage());
        assertTrue(quick.last == null, "有未绑定用例时不得触发执行");
    }

    @Test
    @DisplayName("空用例集 / 用例集不存在：400 / 404")
    void 空集合与不存在() {
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService empty = serviceWith(List.of(), Map.of(), quick);
        BusinessException emptyEx = assertThrows(BusinessException.class, () -> empty.run(command()));
        assertEquals(ErrorCode.INVALID_ARGUMENT, emptyEx.errorCode());

        TestSetRunService missing = new TestSetRunService(
                testSetRepository(List.of(), null), ExecutionTestSupport.access(),
                new CaseScriptResolver(robotFilesRepos(Map.of())), quick);
        BusinessException missingEx = assertThrows(BusinessException.class, () -> missing.run(command()));
        assertEquals(ErrorCode.NOT_FOUND, missingEx.errorCode());
    }

    @Test
    @DisplayName("标签绑定：测试名与编号不同，但 [Tags] 写了 case:<编号> 仍可解析")
    void 标签绑定() {
        List<TestCase> cases = List.of(testCase("PERF-004"));
        Map<String, String> files = Map.of(
                "suites/perf.robot", robotFile("""
                        *** Test Cases ***
                        200G 带宽压测
                            [Tags]    perf    case:PERF-004
                            Log    ok
                        """));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, files, quick);

        service.run(command());

        assertEquals(List.of("suites/perf.robot"), quick.last.suitePaths());
        assertEquals(List.of("200G 带宽压测"), quick.last.testNames());
    }

    @Test
    @DisplayName("测试级执行：集合只勾一条时，同套件的其它测试不进 --test")
    void 只跑所选测试() {
        List<TestCase> cases = List.of(testCase("PERF-005"));
        Map<String, String> files = Map.of(
                "suites/perf.robot", robotFile("""
                        *** Test Cases ***
                        PERF-005
                            Log    selected
                        PERF-006
                            Log    not-selected
                        """));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, files, quick);

        service.run(command());

        assertEquals(List.of("suites/perf.robot"), quick.last.suitePaths());
        assertEquals(List.of("PERF-005"), quick.last.testNames(),
                "同一套件里未勾选的 PERF-006 不得进入执行清单");
    }

    @Test
    @DisplayName("测试名去重：同一测试绑定多条用例时只传一次")
    void 测试名去重() {
        List<TestCase> cases = List.of(testCase("PERF-007"), testCase("PERF-008"));
        Map<String, String> files = Map.of(
                "suites/perf.robot", robotFile("""
                        *** Test Cases ***
                        200G 双口压测
                            [Tags]    case:PERF-007    case:PERF-008
                            Log    ok
                        """));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, files, quick);

        service.run(command());

        assertEquals(List.of("200G 双口压测"), quick.last.testNames());
    }

    @Test
    @DisplayName("用例编号为空：400 并列 id，不触发")
    void 空编号被拒() {
        TestCase blank = new TestCase(9L, PROJECT, "  ", "无编号", null, null, null, null,
                null, "draft", null, null, null, "L1");
        List<TestCase> cases = List.of(testCase("PERF-009"), blank);
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, Map.of(), quick);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.run(command()));

        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("编号为空"), ex.getMessage());
        assertTrue(ex.getMessage().contains("id=9"), ex.getMessage());
        assertNull(quick.last, "预检失败不得触发");
    }

    @Test
    @DisplayName("集合含 L2 / LX 用例：整单 403，一条都不跑")
    void 安全级拦截() {
        List<TestCase> cases = List.of(testCase("PERF-010"),
                testCase("DESTRUCT-001", "L2"), testCase("UNKNOWN-001", "LX"));
        RecordingQuickRun quick = new RecordingQuickRun();
        TestSetRunService service = serviceWith(cases, Map.of(), quick);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.run(command()));

        assertEquals(ErrorCode.SAFETY_LEVEL_BLOCKED, ex.errorCode());
        assertTrue(ex.getMessage().contains("DESTRUCT-001(L2)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("UNKNOWN-001(LX)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("PERF-SET"), ex.getMessage());
        assertNull(quick.last, "集合里有高危 / 未判定用例时不得部分执行");
    }

    private TestSetRunService serviceWith(List<TestCase> cases, Map<String, String> files,
                                          RecordingQuickRun quick) {
        return new TestSetRunService(testSetRepository(cases, set(cases)),
                ExecutionTestSupport.access(),
                new CaseScriptResolver(robotFilesRepos(files)), quick);
    }

    private static TestSetRunService.TestSetRunCommand command() {
        return new TestSetRunService.TestSetRunCommand(PROJECT, SET_ID, null, List.of(),
                null, null, null, null, null);
    }

    private static TestSet set(List<TestCase> cases) {
        return new TestSet(SET_ID, PROJECT, "PERF-SET", "性能集", null, 1L, "active", cases.size());
    }

    private static TestCase testCase(String code) {
        return testCase(code, "L1");
    }

    private static TestCase testCase(String code, String safetyLevel) {
        return new TestCase(1L, PROJECT, code, code, null, null, null, null,
                null, "draft", null, null, null, safetyLevel);
    }

    private static String robotFile(String content) {
        return content;
    }

    private static TestSetRepository testSetRepository(List<TestCase> cases, TestSet set) {
        return (TestSetRepository) Proxy.newProxyInstance(
                TestSetRepository.class.getClassLoader(), new Class<?>[]{TestSetRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> Optional.ofNullable(set);
                    case "items" -> cases;
                    case "page" -> PageResult.empty(1, 20);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static RobotFileRepository robotFilesRepos(Map<String, String> files) {
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
            return new RunRecord(1L, "RUN-1", PROJECT, cmd.testSetId(), RunState.QUEUED,
                    0, 1, 0, 0, 0, 0, null, null, null, null, Instant.EPOCH, null, null, null, null);
        }
    }
}
