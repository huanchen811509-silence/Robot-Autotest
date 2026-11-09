package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ReportReadRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.report.CaseLogRef;
import com.qa.autotest.domain.report.CaseVerdict;
import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.RunCaseRow;
import com.qa.autotest.domain.report.RunSnapshot;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志分段接口的分页/上限/容错（RPT-02）。
 *
 * <p>真实 output.xml 行程由 {@link ReportLogReaderTest} 覆盖；本测试聚焦服务层的
 * limit 夹取、nextOffset 语义与空日志/不存在用例的边界。
 */
class ReportLogServiceTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "RUN-1";

    private FakeReadRepository read;
    private ReportLogService service;
    private Path fixture;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(1L, "admin", PROJECT);
        fixture = Path.of(ReportLogServiceTest.class.getClassLoader()
                .getResource("fixtures/output-xml/pabot-merged.xml").toURI());
        read = new FakeReadRepository();
        service = new ReportLogService(read, (projectId, runId) -> fixture,
                new LogXmlReader(), new JsonlLogReader(new com.fasterxml.jackson.databind.ObjectMapper()),
                new AccessControlService(adminUsers(), emptyProjects()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("默认 limit=200：小用例一次取完，nextOffset=null")
    void 默认一次取完() {
        read.caseLog(new CaseLogRef("Suite.A", 0L, "0:4"));

        ReportLogService.LogPage page = service.logs(PROJECT, RUN_ID, "Suite.A", 0, 200);

        assertEquals(4, page.total());
        assertEquals(4, page.lines().size());
        assertEquals(0L, page.lines().get(0).seq());
        assertNull(page.nextOffset());
    }

    @Test
    @DisplayName("limit=2 分页：第一页 nextOffset=2，第二页到底 nextOffset=null")
    void 分页增量加载() {
        read.caseLog(new CaseLogRef("Suite.A", 0L, "0:4"));

        ReportLogService.LogPage first = service.logs(PROJECT, RUN_ID, "Suite.A", 0, 2);
        assertEquals(2, first.lines().size());
        assertEquals(2, first.nextOffset());

        ReportLogService.LogPage second = service.logs(PROJECT, RUN_ID, "Suite.A", 2, 2);
        assertEquals(2, second.lines().size());
        assertEquals(2L, second.lines().get(0).seq(), "第二页首行 seq 应为 2");
        assertNull(second.nextOffset());
    }

    @Test
    @DisplayName("limit 上限 1000：传 5000 时 nextOffset 停在 1000")
    void 上限夹取() {
        read.caseLog(new CaseLogRef("Suite.B", 0L, "0:2000"));

        ReportLogService.LogPage page = service.logs(PROJECT, RUN_ID, "Suite.B", 0, 5000);

        assertEquals(2000, page.total());
        assertEquals(1000, page.nextOffset(), "limit 必须夹到 1000，nextOffset 才能停在 1000");
    }

    @Test
    @DisplayName("空日志：total=0、lines 为空、nextOffset=null")
    void 空日志() {
        read.caseLog(new CaseLogRef("Suite.SKIP", 0L, null));

        ReportLogService.LogPage page = service.logs(PROJECT, RUN_ID, "Suite.SKIP", 0, 200);

        assertEquals(0, page.total());
        assertTrue(page.lines().isEmpty());
        assertNull(page.nextOffset());
    }

    @Test
    @DisplayName("offset 越过末尾：返回空页而不是报错")
    void 越界返回空页() {
        read.caseLog(new CaseLogRef("Suite.A", 0L, "0:4"));

        ReportLogService.LogPage page = service.logs(PROJECT, RUN_ID, "Suite.A", 99, 200);

        assertEquals(4, page.total());
        assertTrue(page.lines().isEmpty());
        assertNull(page.nextOffset());
    }

    @Test
    @DisplayName("用例不存在：404")
    void 用例不存在() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.logs(PROJECT, RUN_ID, "Suite.MISSING", 0, 200));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    private static UserRepository adminUsers() {
        return proxy(UserRepository.class, (proxy, method, args) ->
                "roleCodesOf".equals(method.getName()) ? List.of("admin") : defaultValue(method.getReturnType()));
    }

    private static ProjectRepository emptyProjects() {
        return proxy(ProjectRepository.class, (proxy, method, args) ->
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

    private static final class FakeReadRepository implements ReportReadRepository {

        private final Map<String, CaseLogRef> logs = new LinkedHashMap<>();

        private void caseLog(CaseLogRef ref) {
            logs.put(ref.caseKey(), ref);
        }

        @Override
        public Optional<RunSnapshot> findRun(long projectId, String runId) {
            return Optional.of(new RunSnapshot(projectId, runId, "finished",
                    "GPU-01", "mlx5_0", Instant.parse("2026-10-10T00:00:00Z")));
        }

        @Override
        public Optional<RunSnapshot> findBaseline(long projectId, String dut, String link,
                                                  Instant before, String excludeRunId) {
            return Optional.empty();
        }

        @Override
        public List<RunSnapshot> listBaselineCandidates(long projectId, String dut, String link,
                                                        String excludeRunId, int limit) {
            return List.of();
        }

        @Override
        public List<CaseVerdict> listCaseVerdicts(long projectId, String runId) {
            return List.of();
        }

        @Override
        public List<RunCaseRow> listCases(long projectId, String runId) {
            return List.of();
        }

        @Override
        public List<KeywordRow> listKeywords(long projectId, String runId) {
            return List.of();
        }

        @Override
        public Optional<CaseLogRef> findCaseLog(long projectId, String runId, String caseKey) {
            return Optional.ofNullable(logs.get(caseKey));
        }
    }
}
