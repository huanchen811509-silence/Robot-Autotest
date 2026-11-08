package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.application.repository.ProjectCardRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.AuditEntry;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectCard;
import com.qa.autotest.domain.iam.ProjectMember;
import java.lang.reflect.Proxy;
import java.time.Clock;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI-02：卡牌可见范围必须<b>照抄</b>项目列表（PRD v2.3 FR-UI-10，纯单测不连库）。
 *
 * <p>库里放 5 个项目，当前用户只是其中 2 个的成员。判据有三条，缺一条都不算过：
 * <ol>
 *   <li>输出只有这 2 个；</li>
 *   <li>聚合查询收到的 id 集合正好是这 2 个（不是全表 5 个）；</li>
 *   <li>过程中没有触发任何「逐项目查项目表」的调用（{@code findById} 直接抛错）。</li>
 * </ol>
 * 第 3 条同时说明：卡牌的基座字段来自 {@code projects.visible()} 的返回值，而不是自己再查一遍。
 */
class ProjectCardVisibilityTest {

    private static final long USER = 15L;
    private static final long MINE_A = 29L;
    private static final long MINE_B = 44L;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private FakeProjects projectRepo;
    private FakeCards cardRepo;
    private ProjectCardService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(USER, "qa-e2e-engineer", null);
        projectRepo = new FakeProjects(List.of(
                new Project(MINE_A, "qa-e2e-fixture", "验收夹具", "我是成员", true, 17L, "private"),
                new Project(MINE_B, "qa-e2e-orch02", "HTTP 全链路", "我也是成员", true, null, "private")));
        // 库里有、但当前用户看不到的项目：任何一条都不许出现在输出里。
        projectRepo.hidden = List.of(
                new Project(1L, "demo", "演示项目", null, true, 2L, "private"),
                new Project(77L, "ED202-COMMON", "公共用例库", null, true, 17L, "private"),
                new Project(61L, "qa-e2e-platform-fixture", "平台属主夹具", null, true, null, "private"));
        cardRepo = new FakeCards();
        service = newProjectCardService();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("只输出 visible() 的 2 个项目，聚合只带这 2 个 id")
    void onlyVisibleProjectsAppear() {
        List<ProjectCard> cards = service.cards("7d", null);

        assertEquals(2, cards.size(), "库里有 5 个项目，但当前用户只可见 2 个");
        assertEquals(List.of(MINE_A, MINE_B), ids(cards));
        assertEquals(List.of(MINE_A, MINE_B), cardRepo.lastIds,
                "聚合查询必须只针对可见项目，不能查全表再过滤");
        assertEquals(1, cardRepo.calls);
    }

    @Test
    @DisplayName("卡牌 id 集合与 GET /api/projects 的可见集合逐 id 相等")
    void sameIdSetAsProjectList() {
        List<Long> listIds = projectRepo.findByMember(USER).stream().map(Project::id).toList();
        List<Long> cardIds = ids(service.cards("7d", null));

        assertEquals(listIds, cardIds, "两处口径必须一致，否则会出现「列表看得到、卡牌看不到」");
    }

    @Test
    @DisplayName("看不到的项目即使 current 指向它也不会被凭空补出来")
    void currentForInvisibleProjectAddsNothing() {
        List<ProjectCard> cards = service.cards("7d", 77L);

        assertEquals(2, cards.size());
        assertTrue(cards.stream().noneMatch(ProjectCard::isCurrent),
                "不可见项目不能因为 current 参数而出现在结果里");
        assertEquals(List.of(MINE_A, MINE_B), cardRepo.lastIds);
    }

    // ------------------------------------------------------------------

    private static List<Long> ids(List<ProjectCard> cards) {
        return cards.stream().map(ProjectCard::id).toList();
    }

    private ProjectCardService newProjectCardService() {
        List<String> globalRoles = List.of();
        AccessControlService access = new AccessControlService(stubUsers(globalRoles), projectRepo);
        ProjectService projects = new ProjectService(projectRepo, stubUsers(globalRoles), access,
                new AuditService(new NoopAudit(), Runnable::run));
        return new ProjectCardService(projects, cardRepo, access, Clock.fixed(NOW, ZoneOffset.UTC));
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

    /** 只暴露「当前用户可见的那批」；{@code findById} 直接抛错，反证没有另查项目。 */
    private static final class FakeProjects implements ProjectRepository {

        private final List<Project> mine;
        private List<Project> hidden = List.of();

        FakeProjects(List<Project> mine) {
            this.mine = mine;
        }

        @Override
        public List<Project> findByMember(long userId) {
            return mine;
        }

        @Override
        public PageResult<Project> page(String keyword, int page, int size) {
            List<Project> all = new ArrayList<>(mine);
            all.addAll(hidden);
            return new PageResult<>(all, all.size(), page, size);
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

    private static final class FakeCards implements ProjectCardRepository {

        private final Map<Long, ProjectCardAggregate> data = new LinkedHashMap<>();
        private int calls;
        private List<Long> lastIds = List.of();

        @Override
        public Map<Long, ProjectCardAggregate> aggregate(Collection<Long> projectIds,
                                                         Instant windowStart, long viewerUserId) {
            calls++;
            lastIds = new ArrayList<>(projectIds);
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
