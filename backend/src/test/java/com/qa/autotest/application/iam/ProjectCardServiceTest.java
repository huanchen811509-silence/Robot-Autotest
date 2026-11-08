package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectCardRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectCard;
import com.qa.autotest.domain.iam.ProjectMember;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI-02：项目卡牌聚合的口径与「不用 N+1」（PRD v2.3 FR-UI-09，纯单测不连库）。
 *
 * <p>fake 仓储把 {@code aggregate} 的调用次数与入参全部记下来，所以「一次请求只查 1 次聚合」
 * 和「窗口起点算对」都是硬断言，不靠人肉 review。
 */
class ProjectCardServiceTest {

    private static final long ENGINEER = 15L;
    private static final long ADMIN = 17L;
    private static final long ALPHA = 1L;
    private static final long BETA = 29L;
    private static final long GAMMA = 77L;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private FakeProjects projectRepo;
    private FakeCards cardRepo;
    private List<Project> visible;

    @BeforeEach
    void setUp() {
        TenantContext.set(ENGINEER, "qa-e2e-engineer", null);
        visible = List.of(
                new Project(ALPHA, "demo", "演示项目", "基线项目", true, 2L, "private"),
                new Project(BETA, "qa-e2e-fixture", "验收夹具", null, false, null, "PRIVATE"),
                new Project(GAMMA, "ED202-COMMON", "公共用例库", "813 条", true, 17L, "private"));
        projectRepo = new FakeProjects(visible);
        cardRepo = new FakeCards();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("默认窗口 7d：窗口起点 = now - 7 天，且只查 1 次聚合")
    void defaultWindowIsSevenDays() {
        service(List.of("engineer")).cards(null, null);

        assertEquals(1, cardRepo.calls, "一次请求只能查 1 次聚合（禁止 N+1）");
        assertEquals(NOW.minus(Duration.ofDays(7)), cardRepo.lastWindowStart);
        assertEquals(ENGINEER, cardRepo.lastViewer);
        assertEquals(List.of(ALPHA, BETA, GAMMA), cardRepo.lastIds, "聚合必须一次带上全部可见项目 id");
    }

    @Test
    @DisplayName("window=30d：窗口起点 = now - 30 天；window 前后空白也认")
    void thirtyDayWindow() {
        service(List.of("engineer")).cards(" 30d ", null);
        assertEquals(NOW.minus(Duration.ofDays(30)), cardRepo.lastWindowStart);
    }

    @Test
    @DisplayName("非法 window（90d / 7 / 空串按默认）")
    void invalidWindowRejected() {
        ProjectCardService service = service(List.of("engineer"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cards("90d", null));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("90d"), "文案要带上非法值，便于前端定位：" + ex.getMessage());
        assertEquals(0, cardRepo.calls, "参数非法时不应发起聚合查询");

        assertThrows(BusinessException.class, () -> service.cards("7", null));
        assertThrows(BusinessException.class, () -> service.cards("WEEK", null));

        service.cards("", null);
        assertEquals(1, cardRepo.calls, "空串按默认 7d 处理，正常发起 1 次聚合");
    }

    @Test
    @DisplayName("isCurrent 回显调用方 current；不传则全部 false")
    void isCurrentEchoesCallerSuppliedId() {
        ProjectCardService service = service(List.of("engineer"));

        List<ProjectCard> withCurrent = service.cards("7d", BETA);
        assertEquals(3, withCurrent.size());
        assertFalse(withCurrent.get(0).isCurrent(), "alpha 不是当前项目");
        assertTrue(withCurrent.get(1).isCurrent(), "beta 是调用方声明的当前项目");
        assertFalse(withCurrent.get(2).isCurrent());

        List<ProjectCard> withoutCurrent = service.cards("7d", null);
        assertTrue(withoutCurrent.stream().noneMatch(ProjectCard::isCurrent),
                "不传 current 时不能凭空猜一个当前项目");
    }

    @Test
    @DisplayName("passRateBp：无分母为 null（不拿 0 冒充），有分母按万分位取整")
    void passRateNullWhenNoDenominator() {
        cardRepo.data.put(ALPHA, new ProjectCardRepository.ProjectCardAggregate(
                0, 0, 0, 0, null, 0, 0, null));
        cardRepo.data.put(GAMMA, new ProjectCardRepository.ProjectCardAggregate(
                813, 3, 36, 26, NOW.minus(Duration.ofDays(1)), 2, 4, "admin"));

        List<ProjectCard> cards = service(List.of("engineer")).cards("7d", null);

        assertNull(cards.get(0).passRateBp(), "从没跑过 → null，而不是 0");
        assertEquals(Integer.valueOf(7222), cards.get(2).passRateBp(), "26/36 = 72.22% → 7222bp");
        assertEquals(NOW.minus(Duration.ofDays(1)), cards.get(2).lastRunAt());
    }

    @Test
    @DisplayName("myRole：成员角色优先；全局 admin 无成员关系兜底 admin；都没有为 null")
    void myRoleFallback() {
        cardRepo.data.put(ALPHA, new ProjectCardRepository.ProjectCardAggregate(
                226, 2, 5, 4, NOW.minus(Duration.ofDays(3)), 1, 3, "engineer"));

        List<ProjectCard> asEngineer = service(List.of("engineer")).cards("7d", null);
        assertEquals("engineer", asEngineer.get(0).myRole());
        assertNull(asEngineer.get(1).myRole(), "非成员、非管理员 → null");
        assertNull(asEngineer.get(2).myRole());

        List<ProjectCard> asAdmin = service(List.of("admin")).cards("7d", null);
        assertEquals("engineer", asAdmin.get(0).myRole(), "有成员关系时仍以 member_role 为准");
        assertEquals("admin", asAdmin.get(1).myRole(), "全局 admin 无成员关系 → admin");
        assertEquals("admin", asAdmin.get(2).myRole());
    }

    @Test
    @DisplayName("基座字段直接来自 visible() 的 Project，不另查项目")
    void baseFieldsComeFromVisibleProjects() {
        List<ProjectCard> cards = service(List.of("engineer")).cards("7d", null);

        assertEquals(Long.valueOf(ALPHA), cards.get(0).id());
        assertEquals("demo", cards.get(0).code());
        assertEquals("演示项目", cards.get(0).name());
        assertEquals("基线项目", cards.get(0).description());
        assertTrue(cards.get(0).enabled());
        assertEquals("private", cards.get(0).visibility());
        assertEquals(Long.valueOf(2L), cards.get(0).ownerId());
        assertEquals("验收夹具", cards.get(1).name());
        assertFalse(cards.get(1).enabled(), "enabled 由 project.status 映射，要原样透传");
        assertEquals("PRIVATE", cards.get(1).visibility(), "visibility 原样透传，不在服务层改写大小写");
    }

    @Test
    @DisplayName("无可见项目：返回空列表，且不发聚合查询")
    void noVisibleProjectsSkipsAggregate() {
        projectRepo.rows = List.of();
        List<ProjectCard> cards = service(List.of("engineer")).cards("7d", null);
        assertTrue(cards.isEmpty());
        assertEquals(0, cardRepo.calls);
    }

    // ------------------------------------------------------------------

    private ProjectCardService service(List<String> globalRoles) {
        AccessControlService access = new AccessControlService(stubUsers(globalRoles), projectRepo);
        ProjectService projects = new ProjectService(projectRepo, stubUsers(globalRoles), access,
                new AuditService(new NoopAudit(), Runnable::run));
        return new ProjectCardService(projects, cardRepo, access,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserRepository stubUsers(List<String> globalRoles) {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "roleCodesOf" -> globalRoles;
                    case "rolesOf" -> List.of();
                    case "findById" -> Optional.empty();
                    case "toString" -> "stub-users";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "UserRepository." + method.getName() + " 不该被卡牌聚合调用");
                });
    }

    /** 只实现两条可见性入口；其余方法一律抛错，等于反证「没有逐项目查库」。 */
    private static final class FakeProjects implements ProjectRepository {

        private List<Project> rows;

        FakeProjects(List<Project> rows) {
            this.rows = rows;
        }

        @Override
        public List<Project> findByMember(long userId) {
            return rows;
        }

        @Override
        public PageResult<Project> page(String keyword, int page, int size) {
            return new PageResult<>(rows, rows.size(), page, size);
        }

        @Override
        public Optional<Project> findById(long id) {
            throw new AssertionError("卡牌聚合不应逐项目查库（N+1）：findById(" + id + ")");
        }

        @Override
        public Optional<Project> findByCode(String code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insert(Project project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(long id, String name, String description, boolean enabled, Long ownerId,
                           String visibility, Long updatedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProjectMember> members(long projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProjectMember> findMember(long projectId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addMember(long projectId, long userId, String roleCode, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateMemberRole(long projectId, long userId, String roleCode, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeMember(long projectId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean codeExists(String code, Long excludeId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 记账式 fake：调用次数、入参全留痕。 */
    private static final class FakeCards implements ProjectCardRepository {

        private final Map<Long, ProjectCardAggregate> data = new LinkedHashMap<>();
        private int calls;
        private List<Long> lastIds = List.of();
        private Instant lastWindowStart;
        private long lastViewer;

        @Override
        public Map<Long, ProjectCardAggregate> aggregate(Collection<Long> projectIds,
                                                         Instant windowStart, long viewerUserId) {
            calls++;
            lastIds = new ArrayList<>(projectIds);
            lastWindowStart = windowStart;
            lastViewer = viewerUserId;
            Map<Long, ProjectCardAggregate> result = new LinkedHashMap<>();
            for (Long id : projectIds) {
                if (data.containsKey(id)) {
                    result.put(id, data.get(id));
                }
            }
            return result;
        }
    }

    private static final class NoopAudit implements AuditRepository {

        @Override
        public void insert(Long projectId, Long actorId, String action, String resourceType,
                           String resourceId, String detail) {
        }

        @Override
        public PageResult<AuditEntry> page(AuditQuery query) {
            return PageResult.empty(query.page(), query.size());
        }

        @Override
        public List<AuditEntry> export(AuditQuery query, int limit) {
            return List.of();
        }

        @Override
        public List<String> recentActions(Long projectId, int limit) {
            return List.of();
        }
    }
}
