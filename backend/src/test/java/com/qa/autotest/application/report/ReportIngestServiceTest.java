package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.execution.RunArtifact;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 报告入库编排（RPT-01 + RPT-07）的服务层行为。 */
class ReportIngestServiceTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "PERF-IPERF-200G-001_20261031_141207";

    private FakeRunRepository runs;
    private FakeReportRepository reports;
    private ReportIngestService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", PROJECT);
        runs = new FakeRunRepository();
        reports = new FakeReportRepository();
        service = new ReportIngestService(runs, new FakeArtifacts(), reports,
                new AccessControlService(adminUsers(), emptyProjects()), new OutputXmlParser());
        RunRecord stored = runs.insert(new RunRecord(0, RUN_ID, PROJECT, null, RunState.FINISHED,
                0, 1, 1, 0, 0, 0, null, null, null, null,
                Instant.parse("2026-10-31T05:00:00Z"), null, null, null), null, null, null);
        reports.bindRunId(stored.id(), RUN_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(ReportIngestServiceTest.class.getClassLoader()
                .getResource("fixtures/output-xml/" + name).toURI());
    }

    @Test
    @DisplayName("完整 metadata → exportable=true")
    void 完整metadata入库后可导出() throws Exception {
        ReportRecord record = service.parseAndIngest(PROJECT, RUN_ID, fixture("pabot-merged.xml").toString());

        assertTrue(record.exportable());
        assertTrue(record.metadataMissing().isEmpty());
        assertEquals(10, record.metadata().size());
        assertEquals("GPU-01", record.metadata().get("DUT"));
        assertEquals(RUN_ID, record.runId());
    }

    @Test
    @DisplayName("缺项 → exportable=false 且 missing 正确")
    void 缺项入库后不可导出() throws Exception {
        ReportRecord record = service.parseAndIngest(PROJECT, RUN_ID, fixture("ok.xml").toString());

        assertFalse(record.exportable());
        assertEquals(MetadataGate.REQUIRED, record.metadataMissing());
    }

    @Test
    @DisplayName("PASS/FAIL/SKIP 计数写入 run")
    void 计数落库() throws Exception {
        ReportRecord record = service.parseAndIngest(PROJECT, RUN_ID, fixture("pabot-merged.xml").toString());

        assertEquals(2, record.passCount());
        assertEquals(1, record.failCount());
        assertEquals(1, record.skipCount());
        assertEquals(4, reports.testRowCount(RUN_ID));
    }

    @Test
    @DisplayName("重复解析幂等：覆盖而非翻倍")
    void 重复解析幂等() throws Exception {
        Path xml = fixture("pabot-merged.xml");

        service.parseAndIngest(PROJECT, RUN_ID, xml.toString());
        ReportRecord second = service.parseAndIngest(PROJECT, RUN_ID, xml.toString());

        assertEquals(2, reports.replaceCount, "两次解析应触发两次覆盖写");
        assertEquals(4, reports.testRowCount(RUN_ID), "test_result 行数不应翻倍");
        assertEquals(2, second.passCount());
        assertEquals(1, reports.storedRunCount(), "同一 run 只保留一条报告记录");
    }

    @Test
    @DisplayName("已有补录时重析：门禁按解析值+补录值计算，不把补录冲掉")
    void 重析时叠加补录值算门禁() throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("CASE_ID", "HRD02-PATCH-001");
        overrides.put("RUN_ID", RUN_ID);
        overrides.put("QA_PROJECT", "robot-autotest");
        overrides.put("OPERATOR", "evergreen");
        overrides.put("DUT", "WSL-DEV-NODE-02");
        overrides.put("LINK", "wsl2-lo -> backend:8081");
        overrides.put("SW_BUILD", "Ubuntu 24.04 / RF 7.5.1");
        overrides.put("BASELINE", "2 passed 1 failed");
        overrides.put("STARTED_AT", "2026-10-31T06:12:07Z");
        overrides.put("CASE_KIND", "metadata-patch");
        ReportIngestService withPatch = new ReportIngestService(runs, new FakeArtifacts(), reports,
                new AccessControlService(adminUsers(), emptyProjects()), new OutputXmlParser(),
                null, new FakeRunMetadata(overrides), null);

        ReportRecord record = withPatch.parseAndIngest(PROJECT, RUN_ID, fixture("ok.xml").toString());

        assertTrue(record.exportable(), "十项已由补录补齐，重析后仍应可导出");
        assertTrue(record.metadataMissing().isEmpty());
        assertEquals(1, reports.replaceCount);
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

    private static final class FakeRunRepository implements RunRepository {

        private final Map<String, RunRecord> byRunId = new LinkedHashMap<>();
        private long seq;

        @Override
        public RunRecord insert(RunRecord draft, String jobNo, String runRequestId, Long operatorId) {
            long id = ++seq;
            RunRecord stored = new RunRecord(id, draft.runId(), draft.projectId(),
                    draft.testSetId(), draft.state(), draft.priority(), draft.concurrency(),
                    draft.attemptNo(), 0, 0, 0, null, null, null, draft.artifactsDir(),
                    draft.enqueuedAt(), draft.startedAt(), draft.finishedAt(), draft.deviceScope());
            byRunId.put(stored.runId(), stored);
            return stored;
        }

        @Override
        public Optional<RunRecord> findByRunId(long projectId, String runId) {
            RunRecord stored = byRunId.get(runId);
            return stored != null && stored.projectId() == projectId
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public Optional<RunRecord> findByRequestId(long projectId, String requestId) {
            return byRunId.values().stream()
                    .filter(r -> r.projectId() == projectId).findFirst();
        }

        @Override
        public int updateState(String runId, RunState from, RunState to,
                               Instant startedAt, Instant finishedAt,
                               String failureClass, String failureReason, String failureMessage) {
            return 1;
        }

        @Override
        public int updateCounts(String runId, int total, int passed, int failed, String artifactsDir) {
            return 1;
        }

        @Override
        public List<RunRecord> findActive() {
            return List.of();
        }

        @Override
        public PageResult<RunRecord> page(long projectId, RunState state, int page, int size) {
            List<RunRecord> items = byRunId.values().stream()
                    .filter(r -> r.projectId() == projectId).toList();
            return new PageResult<>(items, items.size(), page, size);
        }

        @Override
        public int markAttempt(String runId, int attemptNo, String retryOf) {
            return 0;
        }
    }

    private static final class FakeArtifacts implements RunArtifactRepository {

        @Override
        public void upsert(long projectId, long runDbId, String kind, String path,
                           String sha256, long sizeBytes, int shardNo, String note) {
            // 本测试全部显式传 outputXmlPath，无需登记产物
        }

        @Override
        public List<RunArtifact> list(long projectId, long runDbId) {
            return List.of();
        }
    }

    private static final class FakeRunMetadata implements RunMetadataRepository {

        private final Map<String, String> values;

        private FakeRunMetadata(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Map<String, String> findAll(long projectId, String runId) {
            return values;
        }

        @Override
        public void save(long projectId, String runId, Map<String, String> values,
                         java.util.Collection<String> removed, Long operatorId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeReportRepository implements ReportRepository {

        private final Map<String, ReportRecord> byRunId = new LinkedHashMap<>();
        private final Map<Long, String> runIdByDbId = new HashMap<>();
        private final Map<String, Integer> testRows = new HashMap<>();
        private int replaceCount;

        private void bindRunId(long runDbId, String runId) {
            runIdByDbId.put(runDbId, runId);
        }

        private int testRowCount(String runId) {
            return testRows.getOrDefault(runId, 0);
        }

        private int storedRunCount() {
            return byRunId.size();
        }

        @Override
        public void replace(long projectId, long runDbId, ParsedReport parsed, GateResult gate, Instant startedAt) {
            String runId = runIdByDbId.get(runDbId);
            replaceCount++;
            ReportRecord previous = byRunId.get(runId);
            Instant effectiveStarted = startedAt == null && previous != null
                    ? previous.startedAt() : startedAt;
            byRunId.put(runId, new ReportRecord(projectId, runId, RunState.FINISHED.dbValue(),
                    gate.exportable(), gate.missing(), parsed.metadata(),
                    parsed.passCount(), parsed.failCount(), parsed.skipCount(),
                    effectiveStarted, null));
            testRows.put(runId, parsed.totalCount());
        }

        @Override
        public Optional<ReportRecord> find(long projectId, String runId) {
            ReportRecord record = byRunId.get(runId);
            return record != null && record.projectId() == projectId
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public PageResult<ReportRecord> page(long projectId, int page, int size) {
            List<ReportRecord> items = byRunId.values().stream()
                    .filter(r -> r.projectId() == projectId).toList();
            return new PageResult<>(items, items.size(), page, size);
        }

        @Override
        public void updateGate(long projectId, String runId, GateResult gate) {
            // 入库路径不重算门禁
        }
    }
}
