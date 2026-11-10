package com.qa.autotest.application.iam;

import com.qa.autotest.application.asset.CredentialService;
import com.qa.autotest.application.caseasset.AssetVersionService;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.LeaseService;
import com.qa.autotest.application.plan.PlanCommand;
import com.qa.autotest.application.plan.PlanService;
import com.qa.autotest.application.repository.AssetVersionRepository;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ResourceLeaseRepository;
import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Credential;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.execution.Lease;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.iam.Role;
import com.qa.autotest.domain.plan.OverlapPolicy;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HRD-02：审计埋点、脱敏、失败不阻断、删除只记前值。
 *
 * <p>用伪仓库 + 同步执行器的真实 AuditService，不连数据库。
 */
class AuditAspectTest {

    private static final long ALPHA = 7L;
    private static final long ADMIN = 1L;
    private static final long ENGINEER = 3L;

    private static final Object UNHANDLED = new Object();

    private RecordingAudit auditRepo;
    private AuditService audit;
    private AccessControlService access;
    private UserService userService;
    private RoleService roleService;
    private ProjectService projectService;
    private CredentialService credentialService;
    private TestCaseService caseService;
    private TestSetService setService;
    private PlanService planService;
    private LeaseService leaseService;

    private Map<Long, AppUser> users;
    private Map<Long, List<String>> globalRoles;
    private Map<Long, Map<Long, String>> memberships;
    private Map<Long, Project> projects;
    private Map<Long, Role> roles;
    private Map<Long, Credential> credentials;
    private Map<Long, TestCase> cases;
    private Map<Long, TestSet> sets;
    private Map<Long, PlanRecord> plans;

    @BeforeEach
    void setUp() {
        TenantContext.set(ADMIN, "admin", ALPHA);
        users = new LinkedHashMap<>();
        users.put(ADMIN, new AppUser(ADMIN, "admin", "管理员", null, null, null, true, null, 0,
                null, null));
        users.put(ENGINEER, new AppUser(ENGINEER, "engineer", "工程师", null, null, null, true,
                null, 0, null, null));
        users.put(5L, new AppUser(5L, "tmp-user", "待删用户", null, null, null, true, null, 0,
                null, null));

        globalRoles = new LinkedHashMap<>();
        globalRoles.put(ADMIN, List.of("admin"));
        globalRoles.put(ENGINEER, List.of("engineer"));
        globalRoles.put(5L, List.of());

        memberships = new LinkedHashMap<>();
        memberships.put(ALPHA, new LinkedHashMap<>(Map.of(ADMIN, "admin", ENGINEER, "engineer")));
        projects = new LinkedHashMap<>();
        projects.put(ALPHA, new Project(ALPHA, "alpha", "Alpha", "基线项目", true, ADMIN, "private"));

        roles = new LinkedHashMap<>();
        roles.put(61L, new Role(61L, "qa_lead", "质量负责人", null, false, "{}"));

        credentials = new LinkedHashMap<>();
        credentials.put(21L, new Credential(21L, ALPHA, "probe-cred", "ssh", "root", true, 1, null));

        cases = new LinkedHashMap<>();
        cases.put(31L, new TestCase(31L, ALPHA, "TC-31", "待删用例", "功能", "P2", null, null,
                null, "draft", "manual", null, null, "L1"));

        sets = new LinkedHashMap<>();
        sets.put(41L, new TestSet(41L, ALPHA, "SET-41", "待删用例集", null, ADMIN, "draft", 0));

        plans = new LinkedHashMap<>();
        plans.put(51L, new PlanRecord(51L, ALPHA, "计划51", null, null, List.of("suites/a.robot"),
                null, 1, 60, "0 0 * * * ?", "Asia/Shanghai", PlanStatus.ENABLED,
                OverlapPolicy.SKIP, null, Instant.parse("2026-11-04T00:00:00Z"), ADMIN,
                Instant.parse("2026-11-01T00:00:00Z")));

        auditRepo = new RecordingAudit();
        audit = new AuditService(auditRepo, Runnable::run);
        access = new AccessControlService(userRepository(), projectRepository());

        userService = new UserService(userRepository(), roleRepository(), access, audit,
                new PasswordPolicy());
        roleService = new RoleService(roleRepository(), access, audit);
        projectService = new ProjectService(projectRepository(), userRepository(), access, audit);
        credentialService = new CredentialService(credentialRepository(),
                SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])),
                stub(ProjectAssetRepository.class, (name, args) -> UNHANDLED), access, audit);
        caseService = new TestCaseService(caseRepository(), access, audit);
        setService = new TestSetService(setRepository(), caseRepository(), access, audit,
                new AssetVersionService(stub(AssetVersionRepository.class,
                        (name, args) -> UNHANDLED), access, audit));
        planService = new PlanService(planRepository(), setRepository(), access, audit, null,
                new ExecutionProperties(), Clock.systemUTC());
        leaseService = new LeaseService(leaseRepository(), Clock.systemUTC(),
                Duration.ofMinutes(10), Duration.ofSeconds(30), millis -> {
        }, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 10 类动作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("user:create 产生一条记录，摘要不含口令")
    void userCreate() {
        userService.create("audit-user", "审计用户", null, null, "Str0ng!Passw0rd", List.of());
        AuditEntry entry = single();
        assertEquals("user:create", entry.action());
        assertEquals("app_user", entry.resourceType());
        assertTrue(entry.summary().contains("created=true"));
        assertFalse(entry.summary().contains("Str0ng!Passw0rd"), "摘要泄露了口令");
    }

    @Test
    @DisplayName("user:delete 只记前值")
    void userDelete() {
        userService.delete(5L);
        AuditEntry entry = single();
        assertEquals("user:delete", entry.action());
        assertTrue(entry.summary().startsWith("before={"));
        assertFalse(entry.summary().contains("after={"), "删除不应记后值");
        assertTrue(entry.summary().contains("deleted=true"));
    }

    @Test
    @DisplayName("role:update 记录前后名称")
    void roleUpdate() {
        roleService.update(61L, "质量负责人-改", null, "{}");
        AuditEntry entry = single();
        assertEquals("role:update", entry.action());
        assertEquals("role", entry.resourceType());
        assertTrue(entry.summary().contains("before={code=qa_lead"));
        assertTrue(entry.summary().contains("after={name=质量负责人-改}"));
    }

    @Test
    @DisplayName("project:create / project:delete 记录 owner 与前值")
    void projectCreateAndDelete() {
        projectService.create("audit-proj", "审计项目", null, null, null);
        projectService.delete(ALPHA);
        assertEquals(2, auditRepo.entries.size());
        assertEquals("project:create", auditRepo.entries.get(0).action());
        assertTrue(auditRepo.entries.get(0).summary().contains("owner=1"));
        assertEquals("project:delete", auditRepo.entries.get(1).action());
        assertTrue(auditRepo.entries.get(1).summary().contains("before={code=alpha"));
        assertTrue(auditRepo.entries.get(1).summary().contains("deleted=true"));
    }

    @Test
    @DisplayName("member:update 记录角色变化")
    void memberUpdate() {
        projectService.addMember(ALPHA, 5L, "viewer");
        projectService.updateMemberRole(ALPHA, 5L, "engineer");
        assertEquals(2, auditRepo.entries.size());
        // v4.4 起成员支持多角色，摘要口径由单值 `role=` 改为列表 `roles=`
        assertTrue(auditRepo.entries.get(0).summary().contains("before={roles=[]}"));
        assertTrue(auditRepo.entries.get(0).summary().contains("after={roles=[viewer]}"));
        assertTrue(auditRepo.entries.get(1).summary().contains("before={roles=[viewer]}"));
        assertTrue(auditRepo.entries.get(1).summary().contains("after={roles=[engineer]}"));
    }

    @Test
    @DisplayName("credential:upsert 只记已配置与目标账号，绝不含密文")
    void credentialUpsertMasked() {
        credentialService.create(ALPHA, "new-cred", "ssh", "root", "S3cret!Value");
        credentialService.update(ALPHA, 21L, "probe-cred", "root", "S3cret!Value2");
        assertEquals(2, auditRepo.entries.size());
        AuditEntry created = auditRepo.entries.get(0);
        assertEquals("credential:upsert", created.action());
        assertTrue(created.summary().contains("target=root"));
        assertTrue(created.summary().contains("configured=true"));
        assertFalse(created.summary().contains("S3cret"));
        AuditEntry updated = auditRepo.entries.get(1);
        assertTrue(updated.summary().contains("rotated=true"));
        assertFalse(updated.summary().contains("S3cret"));
    }

    @Test
    @DisplayName("case:delete / set:delete 均只记前值")
    void caseAndSetDelete() {
        caseService.delete(ALPHA, 31L);
        setService.delete(ALPHA, 41L);
        assertEquals(2, auditRepo.entries.size());
        assertEquals("case:delete", auditRepo.entries.get(0).action());
        assertEquals("set:delete", auditRepo.entries.get(1).action());
        for (AuditEntry entry : auditRepo.entries) {
            assertTrue(entry.summary().contains("deleted=true"));
            assertFalse(entry.summary().contains("after={"));
        }
    }

    @Test
    @DisplayName("schedule:update / schedule:delete 动作与资源正确")
    void scheduleUpdateAndDelete() {
        planService.update(51L, plan("计划51-改"));
        planService.delete(ALPHA, 51L);
        assertEquals(2, auditRepo.entries.size());
        assertEquals("schedule:update", auditRepo.entries.get(0).action());
        assertEquals("execution_plan", auditRepo.entries.get(0).resourceType());
        assertTrue(auditRepo.entries.get(0).summary().contains("after={name=计划51-改"));
        assertEquals("schedule:delete", auditRepo.entries.get(1).action());
        assertTrue(auditRepo.entries.get(1).summary().contains("deleted=true"));
    }

    @Test
    @DisplayName("lease:force-release 记录释放数量与原因")
    void leaseForceRelease() {
        int released = leaseService.forceReleaseRun("run-1", "admin-force");
        assertEquals(1, released);
        AuditEntry entry = single();
        assertEquals("lease:force-release", entry.action());
        assertEquals("resource_lease", entry.resourceType());
        assertTrue(entry.summary().contains("reason=admin-force"));
    }

    @Test
    @DisplayName("生命周期释放（正常结束/失败/取消）不写强制释放审计")
    void leaseLifecycleReleaseNotAudited() {
        assertEquals(1, leaseService.releaseRun("run-1", "finished"));
        assertTrue(auditRepo.entries.isEmpty(), "普通收尾不应产生 force-release 审计");
    }

    @Test
    @DisplayName("业务异常时不写「成功」记录")
    void failedBusinessWritesNoSuccess() {
        assertThrows(com.qa.autotest.common.BusinessException.class,
                () -> credentialService.update(ALPHA, 999L, "x", "root", "S3cret!Value"));
        assertTrue(auditRepo.entries.isEmpty(), "失败的业务不应产生成功审计");
    }

    @Test
    @DisplayName("审计写失败不影响业务")
    void auditFailureDoesNotBreakBusiness() {
        AuditService failing = new AuditService(new FailingAudit(), Runnable::run);
        UserService service = new UserService(userRepository(), roleRepository(), access, failing,
                new PasswordPolicy());
        var created = service.create("still-ok", "仍然成功", null, null, "Str0ng!Passw0rd",
                List.of());
        assertNotNull(created);
    }

    @Test
    @DisplayName("@Audited 注解声明的 action/resource 与实现一致")
    void annotationDeclarations() throws Exception {
        assertAnnotated(UserService.class, "create", "user:create", "app_user");
        assertAnnotated(UserService.class, "delete", "user:delete", "app_user");
        assertAnnotated(RoleService.class, "update", "role:update", "role");
        assertAnnotated(ProjectService.class, "create", "project:create", "project");
        assertAnnotated(ProjectService.class, "delete", "project:delete", "project");
        assertAnnotated(ProjectService.class, "addMember", "member:update", "project_member");
        assertAnnotated(ProjectService.class, "updateMemberRole", "member:update",
                "project_member");
        assertAnnotated(CredentialService.class, "create", "credential:upsert", "credential");
        assertAnnotated(CredentialService.class, "update", "credential:upsert", "credential");
        assertAnnotated(TestCaseService.class, "delete", "case:delete", "test_case");
        assertAnnotated(TestSetService.class, "delete", "set:delete", "test_set");
        assertAnnotated(PlanService.class, "update", "schedule:update", "execution_plan");
        assertAnnotated(PlanService.class, "delete", "schedule:delete", "execution_plan");
        assertAnnotated(LeaseService.class, "forceReleaseRun", "lease:force-release",
                "resource_lease");
    }

    @Test
    @DisplayName("脱敏函数覆盖 password/token/secret/口令")
    void sanitizePatterns() {
        // W40-01 起审计只用 SecretRedactor 一份规则，占位符统一为 <redacted>
        assertEquals("password=<redacted>; token=<redacted>; secret=<redacted>; 口令=<redacted>",
                AuditService.sanitize("password=abc; token=xyz; secret=123; 口令=mm"));
        assertEquals("{\"password\":\"<redacted>\"}", AuditService.sanitize("{\"password\":\"abc\"}"));
    }

    // ------------------------------------------------------------------

    private static void assertAnnotated(Class<?> type, String method, String action,
                                        String resource) throws Exception {
        // v4.4 起存在重载（单角色 / 多角色）：只要求同名方法里有一个带声明的重载，
        // 不依赖 `getDeclaredMethods()` 的返回顺序。
        Audited annotated = null;
        boolean found = false;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(method)) {
                found = true;
                Audited hit = candidate.getAnnotation(Audited.class);
                if (hit != null) {
                    annotated = hit;
                    break;
                }
            }
        }
        assertTrue(found, "找不到方法：" + type.getSimpleName() + "#" + method);
        assertNotNull(annotated, "缺少 @Audited：" + method);
        assertEquals(action, annotated.action());
        assertEquals(resource, annotated.resource());
    }

    private AuditEntry single() {
        assertEquals(1, auditRepo.entries.size(), "应恰好产生一条审计");
        return auditRepo.entries.get(0);
    }

    private static PlanCommand plan(String name) {
        return new PlanCommand(ALPHA, name, null, null, List.of("suites/a.robot"), null, 1, 60,
                "0 0 * * * ?", "Asia/Shanghai", "skip", List.of());
    }

    // ------------------------------------------------------------------
    // 伪仓库
    // ------------------------------------------------------------------

    private UserRepository userRepository() {
        return stub(UserRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(users.get(((Number) args[0]).longValue()));
            case "roleCodesOf" -> globalRoles.getOrDefault(((Number) args[0]).longValue(), List.of());
            case "rolesOf" -> List.of();
            case "usernameExists", "emailExists" -> false;
            case "insert" -> {
                AppUser draft = (AppUser) args[0];
                users.put(99L, new AppUser(99L, draft.username(), draft.displayName(),
                        draft.email(), draft.passwordHash(), draft.orgId(), true, null, 0, null,
                        null));
                yield 99L;
            }
            case "delete" -> {
                users.remove(((Number) args[0]).longValue());
                yield null;
            }
            default -> UNHANDLED;
        });
    }

    private RoleRepository roleRepository() {
        return stub(RoleRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(roles.get(((Number) args[0]).longValue()));
            case "update" -> {
                long id = ((Number) args[0]).longValue();
                Role old = roles.get(id);
                roles.put(id, new Role(id, old.code(), String.valueOf(args[1]),
                        args[2] == null ? null : String.valueOf(args[2]), old.builtIn(),
                        args[3] == null ? null : String.valueOf(args[3])));
                yield null;
            }
            case "codeExists" -> false;
            default -> UNHANDLED;
        });
    }

    private ProjectRepository projectRepository() {
        return stub(ProjectRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(projects.get(((Number) args[0]).longValue()));
            case "findMember" -> memberOf(((Number) args[0]).longValue(),
                    ((Number) args[1]).longValue());
            case "findByMember" -> List.of();
            case "insert" -> {
                Project draft = (Project) args[0];
                projects.put(98L, new Project(98L, draft.code(), draft.name(),
                        draft.description(), true, draft.ownerId(), draft.visibility()));
                yield 98L;
            }
            case "delete" -> {
                projects.remove(((Number) args[0]).longValue());
                yield null;
            }
            case "addMember", "updateMemberRole" -> {
                memberships.computeIfAbsent(((Number) args[0]).longValue(), k -> new LinkedHashMap<>())
                        .put(((Number) args[1]).longValue(), String.valueOf(args[2]));
                yield null;
            }
            case "codeExists" -> false;
            default -> UNHANDLED;
        });
    }

    private CredentialRepository credentialRepository() {
        return stub(CredentialRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(credentials.get(((Number) args[1]).longValue()));
            case "cipherOf" -> Optional.of("blob");
            case "insert" -> {
                credentials.put(96L, new Credential(96L, ((Number) args[0]).longValue(),
                        String.valueOf(args[1]), String.valueOf(args[2]),
                        args[3] == null ? null : String.valueOf(args[3]), true, 1, null));
                yield 96L;
            }
            case "update" -> {
                long id = ((Number) args[1]).longValue();
                Credential old = credentials.get(id);
                credentials.put(id, new Credential(id, old.projectId(), String.valueOf(args[2]),
                        old.credType(), args[3] == null ? null : String.valueOf(args[3]), true,
                        old.keyVersion(), Instant.now()));
                yield null;
            }
            case "nameExists" -> false;
            default -> UNHANDLED;
        });
    }

    private TestCaseRepository caseRepository() {
        return stub(TestCaseRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(cases.get(((Number) args[1]).longValue()));
            case "delete" -> {
                cases.remove(((Number) args[1]).longValue());
                yield null;
            }
            case "steps" -> List.of();
            default -> UNHANDLED;
        });
    }

    private TestSetRepository setRepository() {
        return stub(TestSetRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(sets.get(((Number) args[1]).longValue()));
            case "delete" -> {
                sets.remove(((Number) args[1]).longValue());
                yield null;
            }
            case "items" -> List.of();
            case "codeExists" -> false;
            default -> UNHANDLED;
        });
    }

    private PlanRepository planRepository() {
        return stub(PlanRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(plans.get(((Number) args[1]).longValue()));
            case "update" -> {
                PlanRecord draft = (PlanRecord) args[0];
                plans.put(draft.id(), draft);
                yield null;
            }
            case "delete" -> {
                plans.remove(((Number) args[1]).longValue());
                yield null;
            }
            case "updateStatus", "updateSchedule" -> 1;
            case "deleteAllEdges", "replaceDependencies" -> null;
            case "dependencies" -> List.of();
            case "latestRun" -> Optional.empty();
            case "allDependencies" -> Map.of();
            default -> UNHANDLED;
        });
    }

    private ResourceLeaseRepository leaseRepository() {
        return stub(ResourceLeaseRepository.class, (name, args) -> switch (name) {
            case "findByRunId" -> List.of(new Lease("GPU-01", 11L, "run-1", ALPHA,
                    Instant.now(), Instant.now().plusSeconds(600)));
            case "releaseOne" -> 1;
            default -> UNHANDLED;
        });
    }

    private Optional<ProjectMember> memberOf(long projectId, long userId) {
        Map<Long, String> members = memberships.get(projectId);
        if (members == null || !members.containsKey(userId)) {
            return Optional.empty();
        }
        AppUser user = users.get(userId);
        return Optional.of(new ProjectMember(null, projectId, userId,
                user == null ? "user-" + userId : user.username(), members.get(userId),
                user == null ? null : user.displayName(), user == null || user.enabled()));
    }

    /** 记录型审计仓库。 */
    private static final class RecordingAudit implements AuditRepository {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
            insertEntry(new AuditEntry(null, projectId, actorId, TenantContext.username(), action,
                    resourceType, resourceId, detail, detail, null, Instant.now()));
        }

        @Override
        public void insertEntry(AuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return entries.stream().map(AuditEntry::action).limit(limit).toList();
        }
    }

    /** 永远写失败的审计仓库。 */
    private static final class FailingAudit implements AuditRepository {
        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
            throw new IllegalStateException("disk full");
        }

        @Override
        public void insertEntry(AuditEntry entry) {
            throw new IllegalStateException("disk full");
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return List.of();
        }
    }

    @FunctionalInterface
    interface Handler {
        Object handle(String name, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object result = handler.handle(method.getName(),
                            args == null ? new Object[0] : args);
                    if (result != UNHANDLED) {
                        return result;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == Optional.class) {
                        return Optional.empty();
                    }
                    if (returnType == List.class) {
                        return List.of();
                    }
                    if (returnType == Map.class) {
                        return Map.of();
                    }
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
