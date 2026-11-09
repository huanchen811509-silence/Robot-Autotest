package com.qa.autotest.api.iam;

import com.qa.autotest.api.AuthInterceptor;
import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.api.asset.CredentialController;
import com.qa.autotest.api.asset.DeviceController;
import com.qa.autotest.api.caseasset.CaseController;
import com.qa.autotest.api.iam.AuditController;
import com.qa.autotest.api.iam.ProjectController;
import com.qa.autotest.api.iam.UserController;
import com.qa.autotest.api.plan.PlanController;
import com.qa.autotest.application.asset.CredentialService;
import com.qa.autotest.application.asset.DeviceService;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditQueryService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.AuthService;
import com.qa.autotest.application.iam.PasswordPolicy;
import com.qa.autotest.application.iam.ProjectService;
import com.qa.autotest.application.iam.UserService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.plan.PlanService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.application.repository.SessionTokenRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.domain.asset.Credential;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.plan.PlanRecord;
import com.qa.autotest.domain.plan.PlanStatus;
import com.qa.autotest.domain.plan.OverlapPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * HRD-01：4 角色 × 7 资源 × 4 动作 = 112 格全量校验。
 *
 * <p>期望值只从 YAML 读取（由 PRD §3.1 手工推导），<b>不调用 PermissionMatrix</b>，
 * 避免「拿生产矩阵自证生产矩阵」。每格打真实控制器 + 真实 AccessControlService，
 * 仓库用伪实现；ALLOW 必须证明业务成功（2xx + code=0 + 业务结果），
 * DENY 必须 403 且不泄露资源名 / 存在性，无入口格只校验中文理由。
 */
class AuthzMatrixTest {

    private static final long ALPHA = 7L;
    private static final long ADMIN = 1L;
    private static final long PM = 2L;
    private static final long ENGINEER = 3L;
    private static final long VIEWER = 4L;
    private static final long NOBODY = 5L;

    private static final Object UNHANDLED = new Object();
    private static final List<Cell> CELLS = loadCells();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    private MockMvc mockMvc;
    private FakeSessions sessions;
    private final Set<String> deleted = new LinkedHashSet<>();

    private Map<Long, AppUser> users;
    private Map<Long, List<String>> globalRoles;
    private Map<Long, Map<Long, String>> memberships;
    private Map<Long, Project> projects;
    private Map<Long, Device> devices;
    private Map<Long, Credential> credentials;
    private Map<Long, TestCase> cases;
    private Map<Long, PlanRecord> plans;

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    private static List<Cell> loadCells() {
        try (InputStream in = AuthzMatrixTest.class.getClassLoader()
                .getResourceAsStream("authz-matrix.yaml")) {
            assertNotNull(in, "缺少 authz-matrix.yaml");
            MatrixFile file = new Yaml().loadAs(in, MatrixFile.class);
            assertEquals(112, file.matrix.size(), "矩阵必须是 112 格");
            return file.matrix;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** YAML 行结构；字段名与文件一致。 */
    public static class Cell {
        public String role;
        public String resource;
        public String action;
        public String expected;
        public String reason;
        public String case_id;
    }

    /** YAML 根结构。 */
    public static class MatrixFile {
        public List<Cell> matrix;
    }

    static Stream<Arguments> cells() {
        return CELLS.stream().map(cell -> Arguments.of(cell.case_id, cell));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        users = new LinkedHashMap<>();
        users.put(ADMIN, user(ADMIN, "admin"));
        users.put(PM, user(PM, "pm"));
        users.put(ENGINEER, user(ENGINEER, "engineer"));
        users.put(VIEWER, user(VIEWER, "viewer"));
        users.put(NOBODY, user(NOBODY, "nobody"));

        globalRoles = new LinkedHashMap<>();
        globalRoles.put(ADMIN, List.of("admin"));
        globalRoles.put(PM, List.of("pm"));
        globalRoles.put(ENGINEER, List.of("engineer"));
        globalRoles.put(VIEWER, List.of("viewer"));
        globalRoles.put(NOBODY, List.of());

        memberships = new LinkedHashMap<>();
        memberships.put(ALPHA, new LinkedHashMap<>(Map.of(PM, "pm", ENGINEER, "engineer",
                VIEWER, "viewer")));

        projects = new LinkedHashMap<>();
        projects.put(ALPHA, new Project(ALPHA, "alpha", "Alpha", "基线项目", true, PM, "private"));

        devices = new LinkedHashMap<>();
        devices.put(11L, new Device(11L, ALPHA, "probe-device", "server", "R750", "Dell",
                "SN-11", null, null, null, "compute", true, PM, null));

        credentials = new LinkedHashMap<>();
        credentials.put(21L, new Credential(21L, ALPHA, "probe-cred", "ssh", "root", true, 1, null));

        cases = new LinkedHashMap<>();
        cases.put(31L, new TestCase(31L, ALPHA, "TC-PROBE", "探针用例", "功能", "P2",
                null, null, null, "draft", "manual", null, null, "L1"));

        plans = new LinkedHashMap<>();
        plans.put(51L, new PlanRecord(51L, ALPHA, "计划探针", null, null, List.of("suites/a.robot"),
                null, 1, 60, "0 0 * * * ?", "Asia/Shanghai", PlanStatus.ENABLED,
                OverlapPolicy.SKIP, null, Instant.parse("2026-11-04T00:00:00Z"), ADMIN,
                Instant.parse("2026-11-01T00:00:00Z")));

        deleted.clear();
        sessions = new FakeSessions();

        AccessControlService access = new AccessControlService(userRepository(), projectRepository());
        AuditService audit = new AuditService(auditRepository(), Runnable::run);

        UserService userService = new UserService(userRepository(), roleRepository(), access, audit,
                new PasswordPolicy());
        ProjectService projectService = new ProjectService(projectRepository(), userRepository(),
                access, audit);
        DeviceService deviceService = new DeviceService(deviceRepository(), stub(ProjectAssetRepository.class, (name, args) -> UNHANDLED),
                access, audit);
        CredentialService credentialService = new CredentialService(credentialRepository(),
                SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])),
                stub(ProjectAssetRepository.class, (name, args) -> UNHANDLED), access, audit);
        TestCaseService caseService = new TestCaseService(caseRepository(), access, audit);
        PlanService planService = new PlanService(planRepository(), testSetRepository(), access,
                audit, null, new ExecutionProperties(), Clock.systemUTC());
        AuditQueryService auditQuery = new AuditQueryService(auditRepository(), projectRepository(),
                access);

        AuthService authService = new AuthService(userRepository(), sessions, new PasswordPolicy(),
                audit);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new UserController(userService, null),
                        new ProjectController(projectService),
                        new DeviceController(deviceService),
                        new CredentialController(credentialService),
                        new CaseController(caseService, null, null),
                        new PlanController(planService),
                        new AuditController(auditQuery))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(authService))
                .build();
    }

    private static AppUser user(long id, String name) {
        return new AppUser(id, name, name, null, null, null, true, null, 0, null, null);
    }

    // ------------------------------------------------------------------
    // 112 格参数化用例
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    @DisplayName("权限矩阵格")
    void matrixCell(String caseId, Cell cell) {
        assertFalse(cell.reason == null || cell.reason.isBlank(), "每格必须有中文理由：" + caseId);
        if ("N/A".equals(cell.expected)) {
            assertTrue(cell.reason.contains("审计") || cell.reason.contains("无"),
                    "无入口格必须写明理由：" + cell.reason);
            RESULTS.put(caseId, "N/A");
            return;
        }

        sessions.userId = userId(cell.role);
        MockHttpServletRequestBuilder request = requestFor(cell);
        MvcResult result;
        try {
            result = mockMvc.perform(request).andReturn();
        } catch (Exception ex) {
            RESULTS.put(caseId, "SKIP");
            throw new AssertionError("请求执行失败：" + caseId, ex);
        }

        int status = result.getResponse().getStatus();
        String body = body(result);
        if (status >= 200 && status < 300) {
            RESULTS.put(caseId, "ALLOW(" + status + ")");
        } else if (status == 403) {
            RESULTS.put(caseId, "DENY(" + status + ")");
        } else {
            RESULTS.put(caseId, "SKIP(" + status + ")");
        }

        if ("ALLOW".equals(cell.expected)) {
            assertEquals(200, status, "预期 ALLOW 实际 " + status + "：" + caseId + " " + body);
            assertTrue(body.contains("\"code\":0"), "业务未成功：" + caseId + " " + body);
            assertTrue(allowProof(cell), "缺少业务结果：" + caseId + " " + body);
        } else {
            assertEquals(403, status, "预期 DENY 实际 " + status + "：" + caseId + " " + body);
            assertFalse(body.contains(probeName(cell.resource)),
                    "403 响应泄露资源名：" + caseId + " " + body);
            assertFalse(body.contains("不存在"),
                    "403 响应泄露存在性：" + caseId + " " + body);
        }
    }

    @Test
    @DisplayName("登录但无任何项目成员身份：403 或空集，不得 500")
    void 无角色用户_不500() throws Exception {
        sessions.userId = NOBODY;
        MvcResult list = mockMvc.perform(auth(get("/api/projects"))).andReturn();
        assertEquals(200, list.getResponse().getStatus(), "项目列表应返回空集而非 5xx");
        assertTrue(body(list).contains("\"data\":[]"), "应为空集：" + body(list));

        MvcResult detail = mockMvc.perform(auth(get("/api/projects/{projectId}/cases", ALPHA)))
                .andReturn();
        assertEquals(403, detail.getResponse().getStatus(), "无成员身份访问项目资源应 403");
    }

    // ------------------------------------------------------------------
    // 请求映射
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder requestFor(Cell cell) {
        String key = cell.resource + "/" + cell.action;
        return switch (key) {
            case "用户角色/READ" -> auth(get("/api/users"));
            case "用户角色/WRITE" -> auth(post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"probe-user\",\"displayName\":\"探针用户\","
                            + "\"password\":\"Str0ng!Passw0rd\",\"roleIds\":[]}"));
            case "用户角色/DELETE" -> auth(delete("/api/users/{id}", NOBODY));
            case "用户角色/MANAGE" -> auth(put("/api/users/{id}", NOBODY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"探针改名\",\"enabled\":true,\"roleIds\":[]}"));

            case "项目/READ" -> auth(get("/api/projects/{projectId}", ALPHA));
            case "项目/WRITE" -> auth(post("/api/projects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"probe-proj\",\"name\":\"探针项目\"}"));
            case "项目/DELETE" -> auth(delete("/api/projects/{projectId}", ALPHA));
            case "项目/MANAGE" -> auth(put("/api/projects/{projectId}", ALPHA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Alpha 改名\",\"description\":\"探针描述\"}"));

            case "资产/READ" -> auth(get("/api/projects/{projectId}/devices", ALPHA));
            case "资产/WRITE" -> auth(post("/api/projects/{projectId}/devices", ALPHA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"probe-device\",\"deviceType\":\"server\","
                            + "\"model\":\"R750\"}"));
            case "资产/DELETE" -> auth(delete("/api/projects/{projectId}/devices/{id}", ALPHA, 11L));
            case "资产/MANAGE" -> auth(put("/api/projects/{projectId}/devices/{id}", ALPHA, 11L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"probe-device\",\"deviceType\":\"server\","
                            + "\"model\":\"R750\",\"enabled\":true}"));

            case "凭据/READ" -> auth(get("/api/projects/{projectId}/credentials", ALPHA));
            case "凭据/WRITE" -> auth(post("/api/projects/{projectId}/credentials", ALPHA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"probe-cred\",\"credType\":\"ssh\",\"username\":\"root\","
                            + "\"secret\":\"S3cret!Value\"}"));
            case "凭据/DELETE" -> auth(delete("/api/projects/{projectId}/credentials/{id}",
                    ALPHA, 21L));
            case "凭据/MANAGE" -> auth(put("/api/projects/{projectId}/credentials/{id}", ALPHA, 21L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"probe-cred\",\"username\":\"root\"}"));

            case "用例/READ" -> auth(get("/api/projects/{projectId}/cases", ALPHA));
            case "用例/WRITE" -> auth(post("/api/projects/{projectId}/cases", ALPHA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"caseCode\":\"TC-PROBE\",\"title\":\"探针用例\","
                            + "\"category\":\"功能\",\"priority\":\"P2\",\"steps\":[]}"));
            case "用例/DELETE" -> auth(delete("/api/projects/{projectId}/cases/{id}", ALPHA, 31L));
            case "用例/MANAGE" -> auth(put("/api/projects/{projectId}/cases/{id}", ALPHA, 31L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"caseCode\":\"TC-PROBE\",\"title\":\"探针用例-改\","
                            + "\"category\":\"功能\",\"priority\":\"P2\",\"steps\":[]}"));

            case "计划/READ" -> auth(get("/api/plans").param("projectId", String.valueOf(ALPHA)));
            case "计划/WRITE" -> auth(post("/api/plans")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"projectId\":7,\"name\":\"计划探针\","
                            + "\"suitePaths\":[\"suites/a.robot\"],\"cron\":\"0 0 * * * ?\","
                            + "\"timezone\":\"Asia/Shanghai\",\"dependencyIds\":[]}"));
            case "计划/DELETE" -> auth(delete("/api/plans/{id}", 51L)
                    .param("projectId", String.valueOf(ALPHA)));
            case "计划/MANAGE" -> auth(post("/api/plans/{id}/pause", 51L)
                    .param("projectId", String.valueOf(ALPHA)));

            case "审计/READ" -> auth(get("/api/audit")
                    .param("projectId", String.valueOf(ALPHA)));
            default -> throw new IllegalStateException("未映射矩阵格：" + key);
        };
    }

    private boolean allowProof(Cell cell) {
        String key = cell.resource + "/" + cell.action;
        return switch (key) {
            case "用户角色/READ" -> true;
            case "用户角色/WRITE", "用户角色/MANAGE" -> true;
            case "用户角色/DELETE" -> deleted.contains("user:" + NOBODY);
            case "项目/READ", "项目/WRITE", "项目/MANAGE" -> true;
            case "项目/DELETE" -> deleted.contains("project:" + ALPHA);
            case "资产/READ", "资产/WRITE", "资产/MANAGE" -> true;
            case "资产/DELETE" -> deleted.contains("device:11");
            case "凭据/READ", "凭据/WRITE", "凭据/MANAGE" -> true;
            case "凭据/DELETE" -> deleted.contains("credential:21");
            case "用例/READ", "用例/WRITE", "用例/MANAGE" -> true;
            case "用例/DELETE" -> deleted.contains("case:31");
            case "计划/READ", "计划/WRITE", "计划/MANAGE" -> true;
            case "计划/DELETE" -> deleted.contains("plan:51");
            case "审计/READ" -> true;
            default -> false;
        };
    }

    private static long userId(String role) {
        return switch (role) {
            case "ADMIN" -> ADMIN;
            case "PM" -> PM;
            case "ENGINEER" -> ENGINEER;
            case "VIEWER" -> VIEWER;
            default -> throw new IllegalArgumentException("未知角色：" + role);
        };
    }

    private static String probeName(String resource) {
        return switch (resource) {
            case "用户角色" -> "probe-user";
            case "项目" -> "Alpha";
            case "资产" -> "probe-device";
            case "凭据" -> "probe-cred";
            case "用例" -> "TC-PROBE";
            case "计划" -> "计划探针";
            default -> "audit-probe";
        };
    }

    private static String body(MvcResult result) throws AssertionError {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AssertionError("读取响应失败", ex);
        }
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer session-" + sessions.userId);
    }

    // ------------------------------------------------------------------
    // CSV 导出
    // ------------------------------------------------------------------

    @AfterAll
    static void exportCsv() throws IOException {
        String raw = System.getProperty("export");
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            Path cwd = Path.of("").toAbsolutePath();
            path = (cwd.getFileName() != null && cwd.getFileName().toString().equals("backend"))
                    ? cwd.getParent().resolve(path) : cwd.resolve(path);
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        StringBuilder csv = new StringBuilder("角色,资源,动作,预期,实际,用例ID,备注\n");
        for (Cell cell : CELLS) {
            String actual = RESULTS.getOrDefault(cell.case_id, "SKIP");
            csv.append(cell.role).append(',').append(cell.resource).append(',')
                    .append(cell.action).append(',').append(cell.expected).append(',')
                    .append(actual).append(',').append(cell.case_id).append(',')
                    .append(cell.reason).append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
        assertNotEquals("N/A", RESULTS.getOrDefault(CELLS.get(0).case_id, "N/A"),
                "矩阵用例应已执行");
    }

    // ------------------------------------------------------------------
    // 伪仓库
    // ------------------------------------------------------------------

    private UserRepository userRepository() {
        return stub(UserRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(users.get(((Number) args[0]).longValue()));
            case "findByUsername" -> users.values().stream()
                    .filter(u -> u.username().equals(String.valueOf(args[0]))).findFirst();
            case "roleCodesOf" -> globalRoles.getOrDefault(((Number) args[0]).longValue(), List.of());
            case "rolesOf" -> List.of();
            case "usernameExists", "emailExists" -> false;
            case "page" -> new PageResult<>(List.copyOf(users.values()), users.size(),
                    (Integer) args[2], (Integer) args[3]);
            case "insert" -> {
                AppUser draft = (AppUser) args[0];
                users.put(99L, new AppUser(99L, draft.username(), draft.displayName(),
                        draft.email(), draft.passwordHash(), draft.orgId(), true,
                        draft.passwordExpiresAt(), 0, null, null));
                yield 99L;
            }
            case "delete" -> {
                deleted.add("user:" + ((Number) args[0]).longValue());
                yield null;
            }
            default -> UNHANDLED;
        });
    }

    private RoleRepository roleRepository() {
        return stub(RoleRepository.class, (name, args) -> UNHANDLED);
    }

    private ProjectRepository projectRepository() {
        return stub(ProjectRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(projects.get(((Number) args[0]).longValue()));
            case "findByCode" -> projects.values().stream()
                    .filter(p -> p.code().equals(String.valueOf(args[0]))).findFirst();
            case "findMember" -> memberOf(((Number) args[0]).longValue(),
                    ((Number) args[1]).longValue());
            case "findByMember" -> projects.values().stream()
                    .filter(p -> memberships.getOrDefault(p.id(), Map.of())
                            .containsKey(((Number) args[0]).longValue())).toList();
            case "page" -> new PageResult<>(List.copyOf(projects.values()), projects.size(),
                    (Integer) args[1], (Integer) args[2]);
            case "insert" -> {
                Project draft = (Project) args[0];
                projects.put(98L, new Project(98L, draft.code(), draft.name(), draft.description(),
                        true, draft.ownerId(), draft.visibility()));
                yield 98L;
            }
            case "delete" -> {
                deleted.add("project:" + ((Number) args[0]).longValue());
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

    private DeviceRepository deviceRepository() {
        return stub(DeviceRepository.class, (name, args) -> switch (name) {
            case "find" -> Optional.ofNullable(devices.get(((Number) args[1]).longValue()));
            case "page" -> new PageResult<>(List.copyOf(devices.values()), devices.size(),
                    (Integer) args[4], (Integer) args[5]);
            case "insert" -> {
                Device draft = (Device) args[0];
                devices.put(97L, new Device(97L, draft.projectId(), draft.name(), draft.deviceType(),
                        draft.model(), draft.vendor(), draft.serialNo(), draft.managementIp(),
                        draft.location(), draft.rackNo(), draft.domainTag(), draft.enabled(),
                        draft.ownerId(), null));
                yield 97L;
            }
            case "update" -> {
                Device draft = (Device) args[0];
                devices.put(draft.id(), draft);
                yield null;
            }
            case "delete" -> {
                deleted.add("device:" + ((Number) args[1]).longValue());
                yield null;
            }
            case "serialExists", "portNameExists" -> false;
            case "domains", "ports" -> List.of();
            default -> UNHANDLED;
        });
    }

    private CredentialRepository credentialRepository() {
        return stub(CredentialRepository.class, (name, args) -> switch (name) {
            case "list" -> List.copyOf(credentials.values());
            case "find" -> Optional.ofNullable(credentials.get(((Number) args[1]).longValue()));
            case "cipherOf" -> Optional.of("blob");
            case "insert" -> {
                Credential draft = new Credential(96L, ((Number) args[0]).longValue(),
                        String.valueOf(args[1]), String.valueOf(args[2]),
                        args[3] == null ? null : String.valueOf(args[3]), true, 1, null);
                credentials.put(96L, draft);
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
            case "delete" -> {
                deleted.add("credential:" + ((Number) args[1]).longValue());
                yield null;
            }
            case "nameExists" -> false;
            default -> UNHANDLED;
        });
    }

    private TestCaseRepository caseRepository() {
        return stub(TestCaseRepository.class, (name, args) -> switch (name) {
            case "page" -> {
                int page = 1;
                int size = 20;
                if (args[7] instanceof com.qa.autotest.common.query.PageQuery paging) {
                    page = paging.page();
                    size = paging.size();
                } else {
                    page = (Integer) args[7];
                    size = (Integer) args[8];
                }
                yield new PageResult<>(List.copyOf(cases.values()), cases.size(), page, size);
            }
            case "find" -> Optional.ofNullable(cases.get(((Number) args[1]).longValue()));
            case "insert" -> {
                TestCase draft = (TestCase) args[0];
                cases.put(95L, new TestCase(95L, draft.projectId(), draft.caseCode(), draft.title(),
                        draft.category(), draft.priority(), draft.precondition(), draft.expected(),
                        draft.domainTag(), draft.reviewStatus(), draft.source(), draft.capability(),
                        draft.description(), draft.safetyLevel()));
                yield 95L;
            }
            case "update" -> {
                TestCase draft = (TestCase) args[0];
                cases.put(draft.id(), draft);
                yield null;
            }
            case "delete" -> {
                deleted.add("case:" + ((Number) args[1]).longValue());
                yield null;
            }
            case "steps" -> List.of();
            case "codeExists" -> false;
            default -> UNHANDLED;
        });
    }

    private PlanRepository planRepository() {
        return stub(PlanRepository.class, (name, args) -> switch (name) {
            case "page" -> new PageResult<>(List.copyOf(plans.values()), plans.size(),
                    (Integer) args[1], (Integer) args[2]);
            case "find" -> Optional.ofNullable(plans.get(((Number) args[1]).longValue()));
            case "insert" -> {
                PlanRecord draft = (PlanRecord) args[0];
                plans.put(94L, new PlanRecord(94L, draft.projectId(), draft.name(),
                        draft.testSetId(), draft.environmentId(), draft.suitePaths(),
                        draft.includeTag(), draft.concurrency(), draft.timeoutSeconds(),
                        draft.cron(), draft.timezone(), draft.status(), draft.overlapPolicy(),
                        draft.lastFireAt(), draft.nextFireAt(), draft.createdBy(), draft.createdAt()));
                yield 94L;
            }
            case "update" -> {
                PlanRecord draft = (PlanRecord) args[0];
                plans.put(draft.id(), draft);
                yield null;
            }
            case "delete" -> {
                deleted.add("plan:" + ((Number) args[1]).longValue());
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

    private TestSetRepository testSetRepository() {
        return stub(TestSetRepository.class, (name, args) -> UNHANDLED);
    }

    private AuditRepository auditRepository() {
        AuditEntry seed = new AuditEntry(1L, ALPHA, ADMIN, "admin", "user:create", "app_user",
                "99", null, "before={}; after={username=probe}", "127.0.0.1",
                Instant.parse("2026-11-01T00:00:00Z"));
        return stub(AuditRepository.class, (name, args) -> switch (name) {
            case "page" -> new PageResult<>(List.of(seed), 1, 1, 20);
            case "export" -> List.of(seed);
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

    /** 伪仓库方法分发：name 为方法名，args 为入参。 */
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
                    if (returnType == PageResult.class) {
                        return PageResult.empty(1, 20);
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

    /** 会话桩：findValidUserId 直接返回当前用例用户。 */
    private static final class FakeSessions implements SessionTokenRepository {
        private long userId;

        @Override
        public void insert(long userId, String tokenHash, Instant expiresAt) {
        }

        @Override
        public Optional<Long> findValidUserId(String tokenHash, Instant now) {
            return Optional.of(userId);
        }

        @Override
        public void touch(String tokenHash, Instant usedAt) {
        }

        @Override
        public void extend(String tokenHash, Instant expiresAt, Instant usedAt) {
        }

        @Override
        public void revoke(String tokenHash, Instant revokedAt) {
        }
    }
}
