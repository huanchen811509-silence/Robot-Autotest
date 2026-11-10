package com.qa.autotest.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.application.repository.WorkspaceRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.RobotFile;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceReport;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 导出/导入服务分支（FR-v4.1-01/02）：任务终态、失败留痕、预检不落库、幂等、
 * 隔离与显式确认、包与目标项目不一致拒绝。
 *
 * <p>仓储用进程内假实现（只实现被测路径用到的方法），文件系统用真实临时目录——
 * 这样既能断言「SKIP 不写库」，也能断言「隔离副本真的在 .quarantine 下」。</p>
 */
class WorkspaceServiceTest {

    private static final String KEY =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final long PROJECT_ID = 29L;
    private static final long USER_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path root;

    private WorkspaceProperties properties;
    private WorkspaceBundleWriter writer;
    private WorkspaceBundleReader reader;
    private FakeWorkspaceRepo repo;
    private FakeAudit auditSink;
    private AuditService audit;
    private AccessControlService access;
    private ProjectRepository projects;

    @BeforeEach
    void setUp() {
        properties = new WorkspaceProperties();
        properties.setRoot(root.toString());
        properties.setSigningKey(KEY);
        WorkspaceBundleSigner signer = new WorkspaceBundleSigner(properties);
        writer = new WorkspaceBundleWriter(properties, signer, mapper);
        reader = new WorkspaceBundleReader(properties, signer, mapper);
        repo = new FakeWorkspaceRepo();
        auditSink = new FakeAudit();
        audit = new AuditService(auditSink.proxy());
        projects = stub(ProjectRepository.class, (proxy, method, args) ->
                method.getName().equals("findById")
                        ? Optional.of(project())
                        : defaultValue(method.getReturnType()));
        UserRepository users = stub(UserRepository.class, (proxy, method, args) ->
                method.getName().equals("roleCodesOf") ? List.of("admin")
                        : defaultValue(method.getReturnType()));
        access = new AccessControlService(users, projects);
        TenantContext.set(USER_ID, "qa-e2e-admin", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Project project() {
        return new Project(PROJECT_ID, "demo", "演示项目", "说明", true, USER_ID, "private", "robot");
    }

    private void seedSnapshot(boolean withEvil) {
        repo.snapshot = snapshot(withEvil);
    }

    private WorkspaceSnapshot snapshot(boolean withEvil) {
        List<WorkspaceSnapshot.FileInfo> files = new ArrayList<>();
        files.add(new WorkspaceSnapshot.FileInfo("hello.robot",
                "*** Test Cases ***\n登录\n    Log    1\n"));
        files.add(new WorkspaceSnapshot.FileInfo("sub/deep.py", "print('x')\n"));
        if (withEvil) {
            files.add(new WorkspaceSnapshot.FileInfo("evil.py", "import os\nos.system('id')\n"));
        }
        return new WorkspaceSnapshot(
                new WorkspaceSnapshot.ProjectInfo("demo", "演示项目", "说明", "robot"),
                List.of(new WorkspaceSnapshot.MemberInfo("qa", "QA", "admin")),
                List.of(new WorkspaceSnapshot.CaseInfo("TC-1", "登录", "ui", "manual", "P1",
                        "auth", "", List.of(new WorkspaceSnapshot.StepInfo(1, "打开", "看到"))),
                        new WorkspaceSnapshot.CaseInfo("TC-2", "退出", "ui", "manual", "P2",
                        "auth", "", List.of())),
                List.of(new WorkspaceSnapshot.CaseSetInfo("TS-1", "冒烟", "",
                        List.of(new WorkspaceSnapshot.SetItem("TC-1", 1)))),
                List.of(new WorkspaceSnapshot.AssetInfo("dev-1", "DEVICE", "10.0.0.1")),
                List.of(new WorkspaceSnapshot.CredentialInfo("cred-1", "SSH", "root", "***ot")),
                List.of(new WorkspaceSnapshot.EnvironmentInfo("env-1", "test", "")),
                List.of(new WorkspaceSnapshot.ReportInfo("RPT-1", "RUN-1", "报告", "draft",
                        "2026-11-09T00:00:00Z")),
                List.of(new WorkspaceSnapshot.RunInfo("RUN-1", "TS-1", "finished",
                        "2026-11-09T00:00:00Z")),
                files);
    }

    private WorkspaceExportService exportService() {
        return new WorkspaceExportService(repo, projects, writer, properties, audit, access, mapper,
                (org.springframework.transaction.support.TransactionTemplate) null);
    }

    private WorkspaceImportService importService(RobotFileRepository files,
                                                 TestCaseRepository cases, TestSetRepository sets) {
        return new WorkspaceImportService(repo, projects, files, cases, sets, reader,
                new ScriptSafetyScanner(), properties, audit, access, mapper,
                (org.springframework.transaction.support.TransactionTemplate) null);
    }

    @Test
    @DisplayName("导出：任务 SUCCESS、报告文件数正确、失败任务 FAILED 且审计留痕")
    void exportBookkeeping() throws Exception {
        seedSnapshot(false);
        WorkspaceReport ok = exportService().export(PROJECT_ID, "PROJECT", null);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.filesImported()).isEqualTo(12); // 10 顶层/域 + 2 源文件
        assertThat(ok.bundlePath()).isEqualTo("demo");
        assertThat(repo.jobs).hasSize(1);
        WorkspaceJob job = repo.jobs.get(0);
        assertThat(job.status()).isEqualTo("SUCCESS");
        assertThat(job.manifestSha256()).hasSize(64);
        assertThat(job.manifestSha256()).isEqualTo(WorkspaceBundleWriter.sha256Hex(
                Files.readAllBytes(root.resolve("demo/manifest.json"))));
        assertThat(auditSink.actions).contains("workspace:export");

        // 重试成功任务 = 再导一次（用于刷新包）
        WorkspaceReport again = exportService().retry(PROJECT_ID, job.id());
        assertThat(again.filesImported()).isEqualTo(12);
        assertThat(repo.jobs).hasSize(2);

        // 半途失败：任务已建，writer 拒绝越界范围
        assertThatThrownBy(() -> exportService().export(PROJECT_ID, "FILE", "files/../etc"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法段");
        assertThat(repo.jobs).hasSize(3);
        assertThat(repo.jobs.get(2).status()).isEqualTo("FAILED");
        assertThat(repo.jobs.get(2).errorMessage()).contains("非法段");
        assertThat(auditSink.actions).contains("workspace:export");
        // 失败不是「丢包」：上一份包仍可验签
        assertThat(reader.verify(root.resolve("demo")).project().code()).isEqualTo("demo");
    }

    @Test
    @DisplayName("导入：预检不写库；首导 4 条、重导全 SKIP；OVERWRITE 只覆盖脚本")
    void importIdempotent() {
        writer.write(root.resolve("demo"), snapshot(false), "PROJECT", null);
        Map<String, RobotFile> fileStore = new LinkedHashMap<>();
        Map<String, TestCase> caseStore = new LinkedHashMap<>();
        Map<String, TestSet> setStore = new LinkedHashMap<>();
        long[] ids = {1};
        RobotFileRepository files = stub(RobotFileRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByPath" -> {
                    return Optional.ofNullable(fileStore.get((String) args[1]));
                }
                case "insert" -> {
                    long id = ids[0]++;
                    fileStore.put((String) args[1], new RobotFile(id, (Long) args[0],
                            (String) args[1], (String) args[2], (String) args[3], 1));
                    return id;
                }
                case "updateContent" -> {
                    return null;
                }
                case "pathExists" -> {
                    return fileStore.containsKey((String) args[1]);
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        TestCaseRepository cases = stub(TestCaseRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByCode" -> {
                    return Optional.ofNullable(caseStore.get((String) args[1]));
                }
                case "insert" -> {
                    TestCase one = (TestCase) args[0];
                    long id = ids[0]++;
                    caseStore.put(one.caseCode(), new TestCase(id, one.projectId(), one.caseCode(),
                            one.title(), one.category(), one.priority(), null, null,
                            one.domainTag(), one.reviewStatus(), "import", null, "", "LX"));
                    return id;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        TestSetRepository sets = stub(TestSetRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "codeExists" -> {
                    return setStore.containsKey((String) args[1]);
                }
                case "insertWithKind" -> {
                    TestSet set = (TestSet) args[0];
                    setStore.put(set.setCode(), set);
                    return ids[0]++;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        WorkspaceImportService service = importService(files, cases, sets);

        WorkspaceReport pre = service.preflight(PROJECT_ID, "demo", "SKIP");
        assertThat(repo.jobs).as("预检不建任务").isEmpty();
        assertThat(pre.filesImported()).isEqualTo(5); // 2 脚本 + 2 用例 + 1 集合
        assertThat(pre.items()).anySatisfy(item -> assertThat(item.action()).isEqualTo("IMPORT"));

        WorkspaceReport first = service.importBundle(PROJECT_ID, "demo", "SKIP", false);
        assertThat(first.filesImported()).isEqualTo(5);
        assertThat(first.filesSkipped()).isEqualTo(6);
        assertThat(fileStore).hasSize(2);
        assertThat(caseStore).hasSize(2);
        assertThat(setStore).hasSize(1);
        assertThat(repo.jobs).singleElement().satisfies(j -> assertThat(j.status()).isEqualTo("SUCCESS"));

        WorkspaceReport second = service.importBundle(PROJECT_ID, "demo", "SKIP", false);
        assertThat(second.filesImported()).as("重复导入必须全部 SKIP").isZero();
        assertThat(second.filesSkipped()).isEqualTo(11);

        WorkspaceReport third = service.importBundle(PROJECT_ID, "demo", "OVERWRITE", false);
        assertThat(third.filesImported()).as("OVERWRITE 只覆盖两个脚本，用例/集合仍 SKIP").isEqualTo(2);
        assertThat(auditSink.actions).contains("workspace:import");
        assertThat(repo.jobs).hasSize(3);
    }

    @Test
    @DisplayName("隔离：可疑脚本不进库、副本落 .quarantine；确认后才写入并带 warning")
    void quarantineFlow() {
        writer.write(root.resolve("demo"), snapshot(true), "PROJECT", null);
        Map<String, RobotFile> fileStore = new LinkedHashMap<>();
        Map<String, TestCase> caseStore = new LinkedHashMap<>();
        Map<String, TestSet> setStore = new LinkedHashMap<>();
        long[] ids = {1};
        RobotFileRepository files = stub(RobotFileRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByPath" -> {
                    return Optional.ofNullable(fileStore.get((String) args[1]));
                }
                case "insert" -> {
                    long id = ids[0]++;
                    fileStore.put((String) args[1], new RobotFile(id, (Long) args[0],
                            (String) args[1], (String) args[2], (String) args[3], 1));
                    return id;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        TestCaseRepository cases = stub(TestCaseRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findByCode" -> {
                    return Optional.ofNullable(caseStore.get((String) args[1]));
                }
                case "insert" -> {
                    TestCase one = (TestCase) args[0];
                    long id = ids[0]++;
                    caseStore.put(one.caseCode(), new TestCase(id, one.projectId(), one.caseCode(),
                            one.title(), one.category(), one.priority(), null, null,
                            one.domainTag(), one.reviewStatus(), "import", null, "", "LX"));
                    return id;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        TestSetRepository sets = stub(TestSetRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "codeExists" -> {
                    return setStore.containsKey((String) args[1]);
                }
                case "insertWithKind" -> {
                    TestSet set = (TestSet) args[0];
                    setStore.put(set.setCode(), set);
                    return ids[0]++;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        });
        WorkspaceImportService service = importService(files, cases, sets);

        WorkspaceReport dry = service.preflight(PROJECT_ID, "demo", "SKIP");
        assertThat(dry.filesQuarantined()).isEqualTo(1);
        assertThat(fileStore).as("预检不写库").isEmpty();

        WorkspaceReport held = service.importBundle(PROJECT_ID, "demo", "SKIP", false);
        assertThat(held.filesQuarantined()).isEqualTo(1);
        assertThat(held.filesImported()).isEqualTo(5); // 2 干净脚本 + 2 用例 + 1 集合
        assertThat(fileStore).doesNotContainKey("evil.py");
        long jobId = repo.jobs.get(0).id();
        Path quarantine = root.resolve(".quarantine/demo/" + jobId + "/evil.py");
        assertThat(quarantine).exists();
        assertThat(auditSink.actions).contains("workspace:import-quarantine");

        WorkspaceReport confirmed = service.importBundle(PROJECT_ID, "demo", "SKIP", true);
        assertThat(confirmed.filesImported()).isEqualTo(1);
        assertThat(confirmed.warnings()).anySatisfy(w -> assertThat(w).contains("可疑"));
        assertThat(fileStore).containsKey("evil.py");
    }

    @Test
    @DisplayName("包内 code 与目标项目不一致：拒绝导入且不建任务")
    void rejectsForeignProjectBundle() {
        writer.write(root.resolve("demo"), snapshot(false), "PROJECT", null);
        ProjectRepository other = stub(ProjectRepository.class, (proxy, method, args) ->
                method.getName().equals("findById")
                        ? Optional.of(new Project(PROJECT_ID, "other", "别的", "", true, 1L,
                        "private", "robot"))
                        : defaultValue(method.getReturnType()));
        WorkspaceImportService service = new WorkspaceImportService(repo, other,
                stub(RobotFileRepository.class, (p, m, a) -> defaultValue(m.getReturnType())),
                stub(TestCaseRepository.class, (p, m, a) -> defaultValue(m.getReturnType())),
                stub(TestSetRepository.class, (p, m, a) -> defaultValue(m.getReturnType())),
                reader, new ScriptSafetyScanner(), properties, audit, access, mapper,
                (org.springframework.transaction.support.TransactionTemplate) null);
        assertThatThrownBy(() -> service.importBundle(PROJECT_ID, "demo", "SKIP", false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不一致");
        assertThat(repo.jobs).isEmpty();
    }

    // ---- 进程内假实现 ----

    static class FakeWorkspaceRepo implements WorkspaceRepository {
        final List<WorkspaceJob> jobs = new ArrayList<>();
        long seq = 1;

        @Override
        public long createJob(long projectId, String jobType, String granularity,
                              String targetPath, String strategy, String bundlePath,
                              Long operatorId) {
            long id = seq++;
            jobs.add(new WorkspaceJob(id, projectId, jobType, granularity, targetPath, "PENDING",
                    0, bundlePath, strategy, null, null, null, null, null, Instant.now()));
            return id;
        }

        @Override
        public Optional<WorkspaceJob> findJob(long projectId, long jobId) {
            return jobs.stream().filter(j -> j.id() == jobId).findFirst();
        }

        @Override
        public List<WorkspaceJob> recentJobs(long projectId, int limit) {
            return jobs.reversed().stream().limit(limit).toList();
        }

        @Override
        public void markRunning(long projectId, long jobId) {
            replace(jobId, "RUNNING", null, null, null, Instant.now(), null);
        }

        @Override
        public void finishJob(long projectId, long jobId, String status, String bundlePath,
                              String manifestSha256, String reportJson, String errorMessage,
                              Long operatorId) {
            replace(jobId, status, bundlePath, manifestSha256, errorMessage, null, Instant.now());
        }

        private void replace(long jobId, String status, String bundlePath, String sha,
                             String error, Instant startedAt, Instant finishedAt) {
            for (int i = 0; i < jobs.size(); i++) {
                WorkspaceJob j = jobs.get(i);
                if (j.id() == jobId) {
                    jobs.set(i, new WorkspaceJob(j.id(), j.projectId(), j.jobType(),
                            j.granularity(), j.targetPath(), status, 100,
                            bundlePath == null ? j.bundlePath() : bundlePath, j.strategy(),
                            sha, j.reportJson(), error,
                            startedAt == null ? j.startedAt() : startedAt,
                            finishedAt == null ? j.finishedAt() : finishedAt, j.createdAt()));
                }
            }
        }

        WorkspaceSnapshot snapshot;

        @Override
        public WorkspaceSnapshot snapshot(long projectId, WorkspaceSnapshot.ProjectInfo project) {
            return snapshot;
        }
    }

    static class FakeAudit {
        final List<String> actions = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        AuditRepository proxy() {
            return stub(AuditRepository.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "insertEntry" -> {
                        com.qa.autotest.domain.iam.AuditEntry entry =
                                (com.qa.autotest.domain.iam.AuditEntry) args[0];
                        actions.add(entry.action());
                        details.add(entry.detail());
                        return null;
                    }
                    case "insert" -> {
                        actions.add((String) args[2]);
                        details.add((String) args[5]);
                        return null;
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object value = handler.invoke(proxy, method, args == null ? new Object[0] : args);
                    return value == null ? defaultValue(method.getReturnType()) : value;
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }
}
