package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RetentionRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.report.RetentionArtifact;
import com.qa.autotest.domain.report.RetentionCandidate;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保留策略（RPT-06）的行为测试。
 *
 * <p>不连库、不碰真实文件：仓储 / 文件删除 / 审计全部用内存伪造实现。
 * 因此「dry-run 不删文件」「实际执行只删文件」「库记录不删」都能被直接观测。
 */
class RetentionPlannerTest {

    private static final long PROJECT = 21L;
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;

    private FakeRetentionRepository retention;
    private FakeFileStore fileStore;
    private FakeAuditRepository audit;
    private RetentionProperties properties;
    private RetentionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", PROJECT);
        retention = new FakeRetentionRepository();
        fileStore = new FakeFileStore();
        audit = new FakeAuditRepository();
        properties = new RetentionProperties();
        service = new RetentionService(retention, fileStore, audit, properties,
                new AccessControlService(adminUsers(), emptyProjects()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("超龄入选：超过 max_days 的运行进入候选，原因 expired")
    void 超龄入选() {
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));
        retention.add(candidate(2, "RECENT", 1, MB, true), artifact(12, "RECENT", MB));
        properties.setKeepRecentRuns(1);

        RetentionService.RetentionResult result = service.plan(PROJECT);

        assertEquals(1, result.items().size());
        assertEquals("OLD", result.items().get(0).runId());
        assertEquals("expired", result.items().get(0).reason());
        assertEquals(MB, result.totalBytes());
    }

    @Test
    @DisplayName("未超龄不入选：未到期且项目未超限时清单为空")
    void 未超龄不入选() {
        retention.add(candidate(1, "FRESH", 10, MB, true), artifact(11, "FRESH", MB));
        properties.setKeepRecentRuns(0);

        RetentionService.RetentionResult result = service.plan(PROJECT);

        assertTrue(result.items().isEmpty());
        assertEquals(0L, result.totalBytes());
    }

    @Test
    @DisplayName("最近 20 次保护：最旧的 5 个入选，最新 20 个永不清理")
    void 最近二十次保护() {
        properties.setMaxDays(90);
        properties.setKeepRecentRuns(20);
        for (int i = 1; i <= 25; i++) {
            int age = 200 + i;
            retention.add(candidate(i, "R" + i, age, MB, true), artifact(100 + i, "R" + i, MB));
        }

        RetentionService.RetentionResult result = service.plan(PROJECT);

        assertEquals(5, result.items().size());
        assertEquals(List.of("R21", "R22", "R23", "R24", "R25"),
                result.items().stream().map(RetentionService.RetentionItem::runId).toList());
    }

    @Test
    @DisplayName("导出保护：有 export_record 的运行不入选")
    void 导出保护() {
        retention.add(candidate(1, "EXPORTED", 300, MB, true, true, false, false),
                artifact(11, "EXPORTED", MB));
        properties.setKeepRecentRuns(0);

        assertTrue(service.plan(PROJECT).items().isEmpty());
    }

    @Test
    @DisplayName("pinned 保护：run_artifact.pinned=1 的运行不入选")
    void pinned保护() {
        retention.add(candidate(1, "PINNED", 300, MB, true, false, true, false),
                artifact(11, "PINNED", MB));
        properties.setKeepRecentRuns(0);

        assertTrue(service.plan(PROJECT).items().isEmpty());
    }

    @Test
    @DisplayName("p0 证据保护：note 标记 p0_evidence 的运行不入选")
    void p0证据保护() {
        retention.add(candidate(1, "EVIDENCE", 300, MB, true, false, false, true),
                artifact(11, "EVIDENCE", MB));
        properties.setKeepRecentRuns(0);

        assertTrue(service.plan(PROJECT).items().isEmpty());
    }

    @Test
    @DisplayName("GB 上限触发：未超龄也按最旧优先清理到上限以下，原因 quota")
    void 项目超限触发() {
        properties.setMaxDays(3650);
        properties.setKeepRecentRuns(1);
        properties.setMaxProjectGb(1);
        retention.add(candidate(1, "OLDEST", 3, GB, true), artifact(11, "OLDEST", GB));
        retention.add(candidate(2, "MIDDLE", 2, GB, true), artifact(12, "MIDDLE", GB));
        retention.add(candidate(3, "NEWEST", 1, GB, true), artifact(13, "NEWEST", GB));

        RetentionService.RetentionResult result = service.plan(PROJECT);

        assertEquals(2, result.items().size());
        assertEquals("OLDEST", result.items().get(0).runId());
        assertEquals("MIDDLE", result.items().get(1).runId());
        assertTrue(result.items().stream().allMatch(item -> item.reason().equals("quota")));
        assertEquals(2 * GB, result.totalBytes());
    }

    @Test
    @DisplayName("dry-run 不删文件、不改 availability、不写审计")
    void dryRun不删文件() {
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));
        properties.setKeepRecentRuns(0);

        RetentionService.RetentionResult result = service.plan(PROJECT);

        assertTrue(result.dryRun());
        assertEquals(1, result.items().size());
        assertTrue(fileStore.deleted.isEmpty(), "dry-run 不得删除任何文件");
        assertTrue(retention.purgedRunIds.isEmpty(), "dry-run 不得修改 availability");
        assertTrue(audit.calls.isEmpty(), "dry-run 不得写审计");
    }

    @Test
    @DisplayName("实际执行：只删除产物文件，不触碰 run / test_result 记录")
    void 实际执行只删文件() {
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));
        retention.runRecords = 5;
        retention.testResultRecords = 20;
        properties.setKeepRecentRuns(0);

        RetentionService.RetentionResult result = service.execute(PROJECT);

        assertFalse(result.dryRun());
        assertEquals(List.of("/artifacts/OLD/output.xml"), fileStore.deleted);
        assertEquals(5, retention.runRecords, "run 记录必须保留");
        assertEquals(20, retention.testResultRecords, "test_result 记录必须保留");
        assertEquals(1, result.deletedFiles());
    }

    @Test
    @DisplayName("availability 置 purged：被清理的产物行标记为 purged")
    void availability置purged() {
        RetentionArtifact artifact = artifact(11, "OLD", MB);
        retention.add(candidate(1, "OLD", 120, MB, true), artifact);
        properties.setKeepRecentRuns(0);

        service.execute(PROJECT);

        assertEquals("purged", retention.availabilityOf(11L));
        assertEquals(List.of("OLD"), retention.purgedRunIds);
    }

    @Test
    @DisplayName("审计写入：动作 artifact_purge，含 runId / 字节数 / 操作人")
    void 审计写入() {
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));
        properties.setKeepRecentRuns(0);

        service.execute(PROJECT);

        assertEquals(1, audit.calls.size());
        AuditCall call = audit.calls.get(0);
        assertEquals(PROJECT, call.projectId);
        assertEquals("artifact_purge", call.action);
        assertEquals("run", call.resourceType);
        assertEquals("OLD", call.resourceId);
        assertTrue(call.detail.contains("\"runId\":\"OLD\""));
        assertTrue(call.detail.contains("\"bytes\":" + MB));
        assertTrue(call.detail.contains("\"operator\":\"admin\""));
    }

    @Test
    @DisplayName("库记录不删：执行前后 run / test_result 行数不变")
    void 库记录不删() {
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));
        retention.runRecords = 3;
        retention.testResultRecords = 12;
        properties.setKeepRecentRuns(0);

        service.execute(PROJECT);

        assertEquals(3, retention.totalRuns());
        assertEquals(12, retention.totalTestResults());
    }

    @Test
    @DisplayName("非管理员调用：403，且不进入规划与删除")
    void 非管理员403() {
        TenantContext.set(2L, "viewer", PROJECT);
        RetentionService viewerService = new RetentionService(retention, fileStore, audit, properties,
                new AccessControlService(viewerUsers(), viewerProjects()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        retention.add(candidate(1, "OLD", 120, MB, true), artifact(11, "OLD", MB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> viewerService.run(PROJECT, false));

        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertTrue(ex.getMessage().contains("仅管理员"));
        assertTrue(fileStore.deleted.isEmpty());
    }

    // ---------- fixtures ----------

    private static RetentionCandidate candidate(long id, String runId, int ageDays, long bytes,
                                                boolean hasArtifacts) {
        return candidate(id, runId, ageDays, bytes, hasArtifacts, false, false, false);
    }

    private static RetentionCandidate candidate(long id, String runId, int ageDays, long bytes,
                                                boolean hasArtifacts, boolean exported,
                                                boolean pinned, boolean p0Evidence) {
        return new RetentionCandidate(id, runId, NOW.minusSeconds(ageDays * 86400L), bytes,
                hasArtifacts ? 1 : 0, pinned, p0Evidence, exported);
    }

    private static RetentionArtifact artifact(long id, String runId, long bytes) {
        return new RetentionArtifact(id, "/artifacts/" + runId + "/output.xml", bytes, false, null);
    }

    private record AuditCall(Long projectId, Long actorId, String action, String resourceType,
                             String resourceId, String detail) {
    }

    private static UserRepository adminUsers() {
        return proxy(UserRepository.class, (proxy, method, args) ->
                "roleCodesOf".equals(method.getName()) ? List.of("admin") : defaultValue(method.getReturnType()));
    }

    private static UserRepository viewerUsers() {
        return proxy(UserRepository.class, (proxy, method, args) ->
                "roleCodesOf".equals(method.getName()) ? List.of("viewer") : defaultValue(method.getReturnType()));
    }

    private static ProjectRepository emptyProjects() {
        return proxy(ProjectRepository.class, (proxy, method, args) ->
                defaultValue(method.getReturnType()));
    }

    private static ProjectRepository viewerProjects() {
        return proxy(ProjectRepository.class, (proxy, method, args) -> {
            if ("findById".equals(method.getName())) {
                return Optional.of(new Project(PROJECT, "P21", "项目21", null, true, 1L, "private"));
            }
            if ("findMember".equals(method.getName())) {
                return Optional.of(new ProjectMember(1L, PROJECT, 2L, "viewer", "viewer",
                        "viewer", true));
            }
            // v5.9.0：判权改读三源并集的两个入口，桩必须与 findMember 说的是同一件事，
            // 否则「是成员」的夹具会被判成「无权访问该项目」，测不到真正的管理员闸。
            if ("isVisibleTo".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("effectiveRoleCodes".equals(method.getName())) {
                return java.util.List.of("viewer");
            }
            return defaultValue(method.getReturnType());
        });
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

    private static final class FakeRetentionRepository implements RetentionRepository {

        private final List<RetentionCandidate> candidates = new ArrayList<>();
        private final Map<Long, List<RetentionArtifact>> artifacts = new LinkedHashMap<>();
        private final Map<Long, String> availability = new HashMap<>();
        private final List<String> purgedRunIds = new ArrayList<>();
        private int runRecords;
        private int testResultRecords;

        void add(RetentionCandidate candidate, RetentionArtifact artifact) {
            candidates.add(candidate);
            artifacts.computeIfAbsent(candidate.runDbId(), key -> new ArrayList<>()).add(artifact);
            availability.put(artifact.id(), "ok");
        }

        String availabilityOf(long artifactId) {
            return availability.get(artifactId);
        }

        int totalRuns() {
            return runRecords;
        }

        int totalTestResults() {
            return testResultRecords;
        }

        @Override
        public List<RetentionCandidate> listCandidates(long projectId) {
            return new ArrayList<>(candidates);
        }

        @Override
        public List<RetentionArtifact> listArtifacts(long projectId, long runDbId) {
            return new ArrayList<>(artifacts.getOrDefault(runDbId, List.of()));
        }

        @Override
        public long totalBytes(long projectId) {
            return candidates.stream().mapToLong(RetentionCandidate::bytes).sum();
        }

        @Override
        public void markPurged(long projectId, long runDbId, List<Long> artifactIds) {
            for (Long id : artifactIds) {
                availability.put(id, "purged");
            }
            purgedRunIds.add(candidates.stream()
                    .filter(candidate -> candidate.runDbId() == runDbId)
                    .map(RetentionCandidate::runId)
                    .findFirst()
                    .orElse("UNKNOWN"));
        }

        @Override
        public List<Long> listProjectIds() {
            return List.of(PROJECT);
        }
    }

    private static final class FakeFileStore implements ArtifactFileStore {

        private final List<String> deleted = new ArrayList<>();

        @Override
        public boolean delete(String path) {
            if (path == null || path.isBlank()) {
                return false;
            }
            deleted.add(path);
            return true;
        }
    }

    private static final class FakeAuditRepository implements AuditRepository {

        private final List<AuditCall> calls = new ArrayList<>();

        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
            calls.add(new AuditCall(projectId, actorId, action, resourceType, resourceId, detail));
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return List.of();
        }
    }
}
