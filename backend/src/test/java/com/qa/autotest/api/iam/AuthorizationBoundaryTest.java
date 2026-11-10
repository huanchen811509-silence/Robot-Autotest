package com.qa.autotest.api.iam;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.AuthInterceptor;
import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.api.asset.DeviceController;
import com.qa.autotest.api.caseasset.CaseController;
import com.qa.autotest.api.caseasset.FileController;
import com.qa.autotest.api.execution.RunController;
import com.qa.autotest.api.iam.ProjectController;
import com.qa.autotest.api.report.ReportController;
import com.qa.autotest.application.asset.DeviceService;
import com.qa.autotest.application.caseasset.CapabilityService;
import com.qa.autotest.application.caseasset.CaseImportService;
import com.qa.autotest.application.caseasset.FileWorkspaceService;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.RunIdGenerator;
import com.qa.autotest.application.execution.RunLogService;
import com.qa.autotest.application.execution.RunProgressRegistry;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.AuthService;
import com.qa.autotest.application.iam.PasswordPolicy;
import com.qa.autotest.application.iam.ProjectService;
import com.qa.autotest.application.report.OutputXmlParser;
import com.qa.autotest.application.report.ReportIngestService;
import com.qa.autotest.application.report.ReportQueryService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RobotFileRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.SessionTokenRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.PasswordHasher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AppUser;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.caseasset.TestCase;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IAM-05 越权（授权边界）测试集。
 *
 * <p>覆盖四个维度：未认证 / 跨项目 / 角色边界 / 无角色无成员身份。
 * 全部走 MockMvc 控制器级调用，配真实 {@link AccessControlService} 与伪造的
 * {@link UserRepository} / {@link ProjectRepository}，从而断言的是「拦截代码」本身，
 * 而不是被测服务是否正确。
 *
 * <p>每个 403 用例都同时断言：HTTP 403、{@code code=40300}、消息<b>不含</b>目标项目名与
 * 「不存在」字样，防止越权响应变成项目枚举通道。
 */
class AuthorizationBoundaryTest {

    /** 项目甲：被测用户 2/3/4 的归属项目。 */
    private static final long ALPHA = 7L;
    /** 项目乙：跨项目越权测试里的「别人的项目」。 */
    private static final long BETA = 8L;
    /** 项目丙：存在但当前用户不是成员，用于跨项目与防枚举对照。 */
    private static final long GAMMA = 9L;
    /** 不存在的项目：必须与 GAMMA 返回同一个 403 响应体。 */
    private static final long ABSENT = 999L;

    private static final String TOKEN_ADMIN = "tk-admin";
    private static final String TOKEN_PM = "tk-pm";
    private static final String TOKEN_ENGINEER = "tk-engineer";
    private static final String TOKEN_VIEWER = "tk-viewer";
    private static final String TOKEN_NOBODY = "tk-nobody";
    private static final String TOKEN_STRANGER = "tk-stranger";
    private static final String TOKEN_EXPIRED = "tk-expired";
    private static final String TOKEN_DISABLED = "tk-disabled";

    private static final String MSG_NO_PROJECT_ACCESS = "无权访问该项目";
    private static final String MSG_PROJECT_NAME_LEAK = "Gamma";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-03T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final Map<Long, AppUser> users = new LinkedHashMap<>();
    private final Map<Long, List<String>> globalRoles = new HashMap<>();
    private final Map<Long, Map<Long, String>> memberships = new LinkedHashMap<>();
    private final Map<Long, Project> projects = new LinkedHashMap<>();
    private final FakeSessions sessions = new FakeSessions();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        users.put(1L, appUser(1L, "admin", true));
        users.put(2L, appUser(2L, "pm", true));
        users.put(3L, appUser(3L, "engineer", true));
        users.put(4L, appUser(4L, "viewer", true));
        users.put(5L, appUser(5L, "nobody", true));
        users.put(6L, appUser(6L, "stranger", true));
        users.put(7L, appUser(7L, "disabled", false));

        globalRoles.put(1L, List.of("admin"));
        globalRoles.put(2L, List.of("pm"));
        globalRoles.put(3L, List.of("engineer"));
        globalRoles.put(4L, List.of("viewer"));
        globalRoles.put(5L, List.of());
        globalRoles.put(6L, List.of("engineer"));
        globalRoles.put(7L, List.of("viewer"));

        memberships.put(ALPHA, new LinkedHashMap<>(Map.of(2L, "pm", 3L, "engineer", 4L, "viewer")));
        memberships.put(BETA, new LinkedHashMap<>(Map.of(6L, "engineer")));

        projects.put(ALPHA, new Project(ALPHA, "alpha", "Alpha", "项目甲", true, 2L, "private"));
        projects.put(BETA, new Project(BETA, "beta", "Beta", "项目乙", true, 6L, "private"));
        projects.put(GAMMA, new Project(GAMMA, "gamma", MSG_PROJECT_NAME_LEAK, "项目丙", true, 1L, "private"));

        sessions.issue(TOKEN_ADMIN, 1L);
        sessions.issue(TOKEN_PM, 2L);
        sessions.issue(TOKEN_ENGINEER, 3L);
        sessions.issue(TOKEN_VIEWER, 4L);
        sessions.issue(TOKEN_NOBODY, 5L);
        sessions.issue(TOKEN_STRANGER, 6L);
        sessions.issue(TOKEN_DISABLED, 7L);
        sessions.issue(TOKEN_EXPIRED, 4L);
        sessions.expire(TOKEN_EXPIRED);

        mockMvc = buildMockMvc();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 维度一：未认证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IAM05-01 缺少 Bearer 令牌 → 401")
    void 缺少令牌_401() throws Exception {
        assertUnauthenticated(get("/api/projects/{projectId}/cases", ALPHA), "缺少访问令牌");
    }

    @Test
    @DisplayName("IAM05-02 伪造令牌 → 401（不区分「格式非法」与「不认识」之外的信息）")
    void 伪造令牌_401() throws Exception {
        assertUnauthenticated(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer tk-forged"), "令牌无效或已过期");
    }

    @Test
    @DisplayName("IAM05-03 过期令牌 → 401")
    void 过期令牌_401() throws Exception {
        assertUnauthenticated(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer " + TOKEN_EXPIRED), "令牌无效或已过期");
    }

    @Test
    @DisplayName("IAM05-04 停用账号的令牌 → 401")
    void 停用账号令牌_401() throws Exception {
        assertUnauthenticated(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer " + TOKEN_DISABLED), "令牌无效或已过期");
    }

    @Test
    @DisplayName("IAM05-05 Bearer 后为空 → 401（不能当成合法令牌）")
    void 空令牌_401() throws Exception {
        assertUnauthenticated(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer   "), "缺少访问令牌");
    }

    @Test
    @DisplayName("IAM05-06 X-Project-Id 非数字 → 400 INVALID_ARGUMENT")
    void 项目头非数字_400() throws Exception {
        assertInvalidArgument(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer " + TOKEN_VIEWER)
                .header("X-Project-Id", "abc"), "X-Project-Id 不是数字");
    }

    @Test
    @DisplayName("IAM05-07 X-Project-Id 溢出 long → 400（不落 500）")
    void 项目头溢出_400() throws Exception {
        assertInvalidArgument(get("/api/projects/{projectId}/cases", ALPHA)
                .header("Authorization", "Bearer " + TOKEN_VIEWER)
                .header("X-Project-Id", "999999999999999999999"), "X-Project-Id 不是数字");
    }

    // ------------------------------------------------------------------
    // 维度二：跨项目（用户 6 仅属于 BETA，访问 GAMMA）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IAM05-08 非成员读别人的运行 → 403")
    void 跨项目读运行_403() throws Exception {
        assertForbidden(auth(get("/api/execution/runs/{runId}", "RUN-cross-1")
                .param("projectId", String.valueOf(GAMMA)), TOKEN_STRANGER), MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-09 非成员在别人的项目里创建运行 → 403")
    void 跨项目创建运行_403() throws Exception {
        assertForbidden(auth(post("/api/execution/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":9,"suitePaths":["suites/a.robot"],"concurrency":1,
                         "priority":0,"timeoutSeconds":60}
                        """), TOKEN_STRANGER), MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-10 非成员读别人的设备列表 → 403")
    void 跨项目读设备_403() throws Exception {
        assertForbidden(auth(get("/api/projects/{projectId}/devices", GAMMA), TOKEN_STRANGER),
                MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-11 非成员删别人的设备 → 403（写路径同样先拦项目）")
    void 跨项目删设备_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}/devices/{id}", GAMMA, 1L),
                TOKEN_STRANGER), MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-12 非成员读别人的用例列表 → 403")
    void 跨项目读用例_403() throws Exception {
        assertForbidden(auth(get("/api/projects/{projectId}/cases", GAMMA), TOKEN_STRANGER),
                MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-13 非成员在别人的项目建用例 → 403")
    void 跨项目建用例_403() throws Exception {
        assertForbidden(auth(post("/api/projects/{projectId}/cases", GAMMA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseCode\":\"TC-X\",\"title\":\"越权用例\"}"), TOKEN_STRANGER),
                MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-14 非成员读别人的文件工作区 → 403")
    void 跨项目读文件_403() throws Exception {
        assertForbidden(auth(get("/api/projects/{projectId}/files", GAMMA), TOKEN_STRANGER),
                MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-15 非成员读别人的报告列表 → 403")
    void 跨项目读报告_403() throws Exception {
        assertForbidden(auth(get("/api/reports")
                .param("projectId", String.valueOf(GAMMA)), TOKEN_STRANGER), MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-16 存在项目与不存在项目返回同一 403 响应体（防枚举）")
    void 跨项目防枚举_同响应体() throws Exception {
        MvcResult existing = mockMvc.perform(auth(get("/api/projects/{projectId}/devices", GAMMA),
                        TOKEN_STRANGER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andReturn();
        MvcResult missing = mockMvc.perform(auth(get("/api/projects/{projectId}/devices", ABSENT),
                        TOKEN_STRANGER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andReturn();

        assertEquals(bodyOf(existing), bodyOf(missing),
                "项目存在与否必须返回完全相同的 403 响应，否则可枚举项目");
        assertFalse(bodyOf(existing).contains(MSG_PROJECT_NAME_LEAK), "响应体泄露了项目名");
        assertFalse(bodyOf(existing).contains("不存在"), "响应体泄露了项目存在性");
    }

    @Test
    @DisplayName("IAM05-17 伪造 X-Project-Id 指向自己项目，不能放行别人的项目路径")
    void 项目头不能替代路径归属() throws Exception {
        assertForbidden(auth(get("/api/projects/{projectId}/cases", GAMMA), TOKEN_STRANGER)
                .header("X-Project-Id", String.valueOf(BETA)), MSG_NO_PROJECT_ACCESS);
    }

    // ------------------------------------------------------------------
    // 维度三：角色边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IAM05-18 VIEWER 读用例 → 200（只读允许）")
    void 访客读用例_200() throws Exception {
        assertOk(auth(get("/api/projects/{projectId}/cases", ALPHA), TOKEN_VIEWER));
    }

    @Test
    @DisplayName("IAM05-19 VIEWER 建用例 → 403 CASE:CREATE")
    void 访客建用例_403() throws Exception {
        assertForbidden(auth(post("/api/projects/{projectId}/cases", ALPHA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseCode\":\"TC-V\",\"title\":\"访客越权\"}"), TOKEN_VIEWER),
                "无权限：CASE:CREATE");
    }

    @Test
    @DisplayName("IAM05-20 VIEWER 删用例 → 403 CASE:DELETE")
    void 访客删用例_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}/cases/{id}", ALPHA, 1L),
                TOKEN_VIEWER), "无权限：CASE:DELETE");
    }

    @Test
    @DisplayName("IAM05-21 VIEWER 删文件 → 403 CASE:DELETE（文件工作区走同一动作）")
    void 访客删文件_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}/files", ALPHA)
                .param("path", "cases/a.robot"), TOKEN_VIEWER), "无权限：CASE:DELETE");
    }

    @Test
    @DisplayName("IAM05-22 VIEWER 创建运行 → 403 EXECUTION:CREATE")
    void 访客建运行_403() throws Exception {
        assertForbidden(auth(post("/api/execution/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":7,"suitePaths":["suites/a.robot"],"concurrency":1,
                         "priority":0,"timeoutSeconds":60}
                        """), TOKEN_VIEWER), "无权限：EXECUTION:CREATE");
    }

    @Test
    @DisplayName("IAM05-23 VIEWER 删设备 → 403 ASSET:DELETE")
    void 访客删设备_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}/devices/{id}", ALPHA, 1L),
                TOKEN_VIEWER), "无权限：ASSET:DELETE");
    }

    @Test
    @DisplayName("IAM05-24 ENGINEER 建用例 → 200（允许：CASE:CREATE）")
    void 工程师建用例_200() throws Exception {
        mockMvc.perform(auth(post("/api/projects/{projectId}/cases", ALPHA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseCode\":\"TC-ENG-001\",\"title\":\"工程师新建用例\"}"),
                TOKEN_ENGINEER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.testCase.caseCode").value("TC-ENG-001"));
    }

    /**
     * 与任务书「ENGINEER 不可删用例」不同：v0.2 PRD §3.1 冻结矩阵对「测试工程师 × 用例/用例集」
     * 写的是「增删改查」，{@code PermissionMatrix} 也是 CASE 全动作。本用例按生产口径断言
     * ALLOW，并把差异写进证据文档；要收紧必须先改矩阵（HRD-01 变更），不能在测试里倒逼生产。
     */
    @Test
    @DisplayName("IAM05-25 ENGINEER 删用例 → 200（按 PRD 冻结矩阵：CASE 全动作）")
    void 工程师删用例_200_按冻结矩阵() throws Exception {
        assertOk(auth(delete("/api/projects/{projectId}/cases/{id}", ALPHA, 1L), TOKEN_ENGINEER));
    }

    @Test
    @DisplayName("IAM05-26 ENGINEER 删设备 → 403 ASSET:DELETE（只可增改）")
    void 工程师删设备_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}/devices/{id}", ALPHA, 1L),
                TOKEN_ENGINEER), "无权限：ASSET:DELETE");
    }

    @Test
    @DisplayName("IAM05-27 ENGINEER 管理项目成员 → 403 PROJECT:UPDATE")
    void 工程师管成员_403() throws Exception {
        assertForbidden(auth(post("/api/projects/{projectId}/members", ALPHA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":5,\"roleCode\":\"viewer\"}"), TOKEN_ENGINEER),
                "无权限：PROJECT:UPDATE");
    }

    @Test
    @DisplayName("IAM05-28 PM 管理项目成员 → 200（允许：PROJECT:UPDATE）")
    void 项目经理管成员_200() throws Exception {
        mockMvc.perform(auth(post("/api/projects/{projectId}/members", ALPHA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":5,\"roleCode\":\"viewer\"}"), TOKEN_PM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.userId").value(5))
                .andExpect(jsonPath("$.data.roleCode").value("viewer"));
    }

    @Test
    @DisplayName("IAM05-29 PM 建项目 → 200（v4.3.0 起允许：PROJECT:CREATE 对 PM 开放）")
    void 项目经理建项目_200() throws Exception {
        mockMvc.perform(auth(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"newproj\",\"name\":\"新项目\"}"), TOKEN_PM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.code").value("newproj"));
    }

    @Test
    @DisplayName("IAM05-30 PM 删项目 → 403 PROJECT:DELETE")
    void 项目经理删项目_403() throws Exception {
        assertForbidden(auth(delete("/api/projects/{projectId}", ALPHA), TOKEN_PM),
                "无权限：PROJECT:DELETE");
    }

    @Test
    @DisplayName("IAM05-31 ADMIN 跨项目读设备 → 200（设计如此，ALLOW 用例）")
    void 管理员跨项目读设备_200() throws Exception {
        assertOk(auth(get("/api/projects/{projectId}/devices", GAMMA), TOKEN_ADMIN));
    }

    @Test
    @DisplayName("IAM05-32 ADMIN 跨项目读报告 → 200（设计如此，ALLOW 用例）")
    void 管理员跨项目读报告_200() throws Exception {
        assertOk(auth(get("/api/reports").param("projectId", String.valueOf(GAMMA)), TOKEN_ADMIN));
    }

    @Test
    @DisplayName("IAM05-33 ADMIN 跨项目读用例 → 200（设计如此，ALLOW 用例）")
    void 管理员跨项目读用例_200() throws Exception {
        assertOk(auth(get("/api/projects/{projectId}/cases", GAMMA), TOKEN_ADMIN));
    }

    // ------------------------------------------------------------------
    // 维度四：无角色 / 无项目成员身份
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IAM05-34 无任何角色的用户可见项目为空集 → 200 []，不 500")
    void 无角色用户项目列表_空集() throws Exception {
        mockMvc.perform(auth(get("/api/projects"), TOKEN_NOBODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("IAM05-35 无角色用户访问项目资源 → 403，不 500")
    void 无角色用户访问项目资源_403() throws Exception {
        assertForbidden(auth(get("/api/projects/{projectId}/cases", ALPHA), TOKEN_NOBODY),
                MSG_NO_PROJECT_ACCESS);
    }

    @Test
    @DisplayName("IAM05-36 无角色用户读运行分页 → 403，不 500")
    void 无角色用户读运行_403() throws Exception {
        assertForbidden(auth(get("/api/execution/runs")
                .param("projectId", String.valueOf(ALPHA)), TOKEN_NOBODY), MSG_NO_PROJECT_ACCESS);
    }

    // ------------------------------------------------------------------
    // 维度五：项目 owner 保护（PRJ-01）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PRJ01-01 移除项目 owner → 409（不是 400）")
    void 移除项目所有者_409() throws Exception {
        assertConflict(auth(delete("/api/projects/{projectId}/members/{userId}", ALPHA, 2L),
                TOKEN_ADMIN), "不能移除项目所有者；如需变更请先转移所有权");
    }

    @Test
    @DisplayName("PRJ01-02 降级项目 owner（admin→engineer）→ 409")
    void 降级项目所有者_409() throws Exception {
        assertConflict(auth(put("/api/projects/{projectId}/members/{userId}", ALPHA, 2L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCode\":\"engineer\"}"), TOKEN_ADMIN),
                "不能降级项目所有者；如需变更请先转移所有权");
    }

    @Test
    @DisplayName("PRJ01-03 移除普通成员 → 200（对照，保护不能误伤）")
    void 移除普通成员_200() throws Exception {
        assertOk(auth(delete("/api/projects/{projectId}/members/{userId}", ALPHA, 3L), TOKEN_ADMIN));
    }

    @Test
    @DisplayName("PRJ01-04 降级普通成员 → 200（对照）")
    void 降级普通成员_200() throws Exception {
        assertOk(auth(put("/api/projects/{projectId}/members/{userId}", ALPHA, 4L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCode\":\"engineer\"}"), TOKEN_ADMIN));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private MockMvc buildMockMvc() {
        AccessControlService access = new AccessControlService(userRepository(), projectRepository());
        AuditService audit = new AuditService(stub(AuditRepository.class, (name, args) -> UNHANDLED));

        AtomicReference<TestCase> storedCase = new AtomicReference<>(new TestCase(1L, ALPHA, "TC-SEED",
                "种子用例", "功能", "P2", null, null, null, "draft", "manual", null, null, "L1"));
        TestCaseRepository caseRepository = stub(TestCaseRepository.class, (name, args) -> switch (name) {
            case "page" -> {
                // 仓储有两个重载：PageQuery 版（args[7] 是对象）与兼容的 int/int 版
                int page = 1;
                int size = 20;
                if (args[7] instanceof com.qa.autotest.common.query.PageQuery paging) {
                    page = paging.page();
                    size = paging.size();
                } else {
                    page = (Integer) args[7];
                    size = (Integer) args[8];
                }
                yield PageResult.<TestCase>empty(page, size);
            }
            case "find" -> {
                TestCase current = storedCase.get();
                yield current != null && current.projectId() == ((Number) args[0]).longValue()
                        ? Optional.of(current) : Optional.<TestCase>empty();
            }
            case "insert" -> {
                TestCase draft = (TestCase) args[0];
                storedCase.set(new TestCase(1L, draft.projectId(), draft.caseCode(), draft.title(),
                        draft.category(), draft.priority(), draft.precondition(), draft.expected(),
                        draft.domainTag(), draft.reviewStatus(), draft.source(), draft.capability(),
                        draft.description(), draft.safetyLevel()));
                yield 1L;
            }
            case "steps" -> List.of();
            case "codeExists" -> false;
            case "delete", "update", "replaceSteps" -> null;
            default -> UNHANDLED;
        });

        TestCaseService cases = new TestCaseService(caseRepository, access, audit);
        CaseController caseController = new CaseController(cases,
                new CapabilityService(caseRepository, access),
                new CaseImportService(caseRepository, access, audit));

        DeviceController deviceController = new DeviceController(new DeviceService(
                stub(DeviceRepository.class, (name, args) -> switch (name) {
                    case "page" -> PageResult.empty(1, 20);
                    case "find", "findPort" -> Optional.empty();
                    case "serialExists", "portNameExists" -> false;
                    case "ports", "domains" -> List.of();
                    default -> UNHANDLED;
                }), stub(ProjectAssetRepository.class, (name, args) -> UNHANDLED), access, audit));

        FileController fileController = new FileController(new FileWorkspaceService(
                stub(RobotFileRepository.class, (name, args) -> switch (name) {
                    case "nodes", "versions" -> List.of();
                    case "findByPath", "find" -> Optional.empty();
                    case "pathExists" -> false;
                    default -> UNHANDLED;
                }), access, audit));

        ExecutionProperties props = new ExecutionProperties();
        RunRepository runRepository = stub(RunRepository.class, (name, args) -> switch (name) {
            case "page" -> PageResult.empty(1, 20);
            case "findByRunId", "findByRequestId" -> Optional.empty();
            case "findActive" -> List.of();
            case "insert" -> args[0];
            default -> UNHANDLED;
        });
        RunService runService = new RunService(runRepository, access, audit,
                new RunIdGenerator(), props, CLOCK);
        // 取消 / 日志端点不在本测试集范围内；控制器构造需要占位，故仅日志服务给出真实实例。
        RunController runController = new RunController(runService, null, new RunLogService(CLOCK), props,
                null, null, null, null, null, null, new RunProgressRegistry(CLOCK));

        ReportRepository reportRepository = stub(ReportRepository.class, (name, args) -> switch (name) {
            case "page" -> PageResult.empty((Integer) args[1], (Integer) args[2]);
            case "find" -> Optional.empty();
            default -> UNHANDLED;
        });
        ReportIngestService ingest = new ReportIngestService(runRepository,
                stub(RunArtifactRepository.class, (name, args) -> UNHANDLED),
                reportRepository, access, new OutputXmlParser());
        ReportController reportController = new ReportController(ingest,
                new ReportQueryService(reportRepository, access), access);

        ProjectController projectController = new ProjectController(new ProjectService(
                projectRepository(), userRepository(), access, audit));

        AuthService authService = new AuthService(userRepository(), sessions,
                new PasswordPolicy(), audit);
        AuthInterceptor interceptor = new AuthInterceptor(authService);

        return MockMvcBuilders.standaloneSetup(runController, deviceController, caseController,
                        fileController, reportController, projectController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(interceptor)
                .build();
    }

    private UserRepository userRepository() {
        return stub(UserRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(users.get(((Number) args[0]).longValue()));
            case "findByUsername" -> users.values().stream()
                    .filter(u -> u.username().equals(args[0]))
                    .findFirst();
            case "roleCodesOf" -> globalRoles.getOrDefault(((Number) args[0]).longValue(), List.of());
            case "rolesOf" -> List.of();
            case "usernameExists", "emailExists" -> false;
            case "page" -> PageResult.empty(1, 20);
            default -> UNHANDLED;
        });
    }

    private ProjectRepository projectRepository() {
        return stub(ProjectRepository.class, (name, args) -> switch (name) {
            case "findById" -> Optional.ofNullable(projects.get(((Number) args[0]).longValue()));
            case "findByCode" -> projects.values().stream()
                    .filter(p -> p.code().equals(String.valueOf(args[0])))
                    .findFirst();
            case "findMember" -> memberOf(((Number) args[0]).longValue(),
                    ((Number) args[1]).longValue());
            case "findByMember" -> projectsOf(((Number) args[0]).longValue());
            case "members" -> membersOf(((Number) args[0]).longValue());
            case "page" -> new PageResult<>(List.copyOf(projects.values()), projects.size(),
                    (Integer) args[1], (Integer) args[2]);
            // v4.3.0：PM 建项目不再是「被拦截」，桩仓储需真实落一行，create 才能返回 200。
            case "insert" -> {
                Project incoming = (Project) args[0];
                long id = 1000L + projects.size();
                projects.put(id, new Project(id, incoming.code(), incoming.name(),
                        incoming.description(), incoming.enabled(), incoming.ownerId(),
                        incoming.visibility(), incoming.testKernel()));
                yield id;
            }
            case "addMember", "updateMemberRole" -> {
                long projectId = ((Number) args[0]).longValue();
                long userId = ((Number) args[1]).longValue();
                memberships.computeIfAbsent(projectId, key -> new LinkedHashMap<>())
                        .put(userId, String.valueOf(args[2]));
                yield null;
            }
            case "removeMember" -> {
                Map<Long, String> members = memberships.get(((Number) args[0]).longValue());
                if (members != null) {
                    members.remove(((Number) args[1]).longValue());
                }
                yield null;
            }
            case "codeExists" -> false;
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

    private List<ProjectMember> membersOf(long projectId) {
        Map<Long, String> members = memberships.get(projectId);
        if (members == null) {
            return List.of();
        }
        return members.keySet().stream().map(userId -> memberOf(projectId, userId).orElseThrow()).toList();
    }

    private List<Project> projectsOf(long userId) {
        return memberships.entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(userId))
                .map(entry -> projects.get(entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ------------------------------------------------------------------
    // 断言工具
    // ------------------------------------------------------------------

    private void assertUnauthenticated(MockHttpServletRequestBuilder request, String message)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()))
                .andExpect(jsonPath("$.codeName").value(ErrorCode.UNAUTHENTICATED.codeName()))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private void assertInvalidArgument(MockHttpServletRequestBuilder request, String message)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_ARGUMENT.code()))
                .andExpect(jsonPath("$.codeName").value(ErrorCode.INVALID_ARGUMENT.codeName()))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private void assertOk(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
    }

    /** 409 断言：状态码 + 错误码 + 消息（owner 保护用）。 */
    private void assertConflict(MockHttpServletRequestBuilder request, String message)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.code()))
                .andExpect(jsonPath("$.codeName").value(ErrorCode.CONFLICT.codeName()))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /**
     * 403 断言三件套：状态码、错误码、消息；外加「不泄露」——响应体不得出现目标项目名，
     * 也不得出现「不存在」字样（否则等于告诉调用方该项目是否存在）。
     */
    private void assertForbidden(MockHttpServletRequestBuilder request, String expectedMessage)
            throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andExpect(jsonPath("$.codeName").value(ErrorCode.FORBIDDEN.codeName()))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        String body = bodyOf(result);
        assertFalse(body.contains(MSG_PROJECT_NAME_LEAK),
                "403 响应体泄露了项目名：" + body);
        assertFalse(body.contains("不存在"),
                "403 响应体泄露了项目存在性：" + body);
        assertFalse(body.contains("Alpha"),
                "403 响应体泄露了其他项目名：" + body);
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,
                                                      String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static AppUser appUser(long id, String username, boolean enabled) {
        return new AppUser(id, username, username.toUpperCase(Locale.ROOT), username + "@example.com",
                "placeholder-hash", null, enabled, null, 0, null, null);
    }

    // ------------------------------------------------------------------
    // 仓储桩
    // ------------------------------------------------------------------

    private interface Handler {
        Object handle(String method, Object[] args);
    }

    private static final Object UNHANDLED = new Object();

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "stub:" + type.getSimpleName();
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Object value = handler.handle(method.getName(), args);
                    return value == UNHANDLED ? defaultValue(method.getReturnType()) : value;
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
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }

    /** 会话令牌桩：只保留「令牌哈希 → 用户 + 过期时间」的判定语义。 */
    private static final class FakeSessions implements SessionTokenRepository {

        private final Map<String, Long> userIdByHash = new HashMap<>();
        private final Map<String, Instant> expiryByHash = new HashMap<>();

        void issue(String token, long userId) {
            String hash = PasswordHasher.sha256Hex(token);
            userIdByHash.put(hash, userId);
            expiryByHash.put(hash, Instant.now().plus(Duration.ofHours(8)));
        }

        void expire(String token) {
            expiryByHash.put(PasswordHasher.sha256Hex(token), Instant.now().minusSeconds(60));
        }

        @Override
        public void insert(long userId, String tokenHash, Instant expiresAt) {
            userIdByHash.put(tokenHash, userId);
            expiryByHash.put(tokenHash, expiresAt);
        }

        @Override
        public Optional<Long> findValidUserId(String tokenHash, Instant now) {
            Long userId = userIdByHash.get(tokenHash);
            Instant expiresAt = expiryByHash.get(tokenHash);
            if (userId == null || expiresAt == null || !expiresAt.isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(userId);
        }

        @Override
        public void touch(String tokenHash, Instant usedAt) {
            // 使用时间不影响判权，测试无需记录
        }

        @Override
        public void extend(String tokenHash, Instant expiresAt, Instant usedAt) {
            if (expiryByHash.containsKey(tokenHash)) {
                expiryByHash.put(tokenHash, expiresAt);
            }
        }

        @Override
        public void revoke(String tokenHash, Instant revokedAt) {
            userIdByHash.remove(tokenHash);
            expiryByHash.remove(tokenHash);
        }
    }
}
