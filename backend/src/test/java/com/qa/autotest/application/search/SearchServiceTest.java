package com.qa.autotest.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.SearchRepository;
import com.qa.autotest.domain.iam.Project;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 全局搜索：空查询、按类型分组与截断、scope=all 的可见项目口径。 */
class SearchServiceTest {

    private static final long PROJECT = 1L;
    private static final Instant UPDATED = Instant.parse("2026-11-08T00:00:00Z");

    private final FakeSearchRepository repository = new FakeSearchRepository();
    private AccessControlService access;
    private SearchService service;

    @BeforeEach
    void setUp() {
        access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(anyLong())).thenAnswer(invocation -> {
            long id = ((Number) invocation.getArgument(0)).longValue();
            return Optional.of(new Project(id, "P" + id, "项目" + id, null, true, 1L, "private"));
        });
        service = new SearchService(repository, access, projects);
    }

    @Test
    @DisplayName("空关键字：不查库，返回空列表与全类型零计数")
    void 空关键字() {
        SearchService.Response response = service.search(PROJECT, "   ", "current", null, 5);

        assertThat(response.query()).isEmpty();
        assertThat(response.items()).isEmpty();
        assertThat(response.truncated()).isFalse();
        assertThat(response.counts()).containsOnlyKeys("case", "run", "report", "plan", "asset")
                .allSatisfy((type, count) -> assertThat(count).isZero());
    }

    @Test
    @DisplayName("按类型分别截断：每类型 limit 条，总数保留并标记 truncated")
    void 按类型分组与截断() {
        repository.put(PROJECT, "case", List.of(hit("case", 1, PROJECT),
                hit("case", 2, PROJECT), hit("case", 3, PROJECT)), 3);
        repository.put(PROJECT, "run", List.of(hit("run", 11, PROJECT)), 1);

        SearchService.Response response = service.search(PROJECT, "login", "current", "case,run", 2);

        assertThat(response.items()).extracting(SearchService.Item::type)
                .containsExactly("case", "case", "run");
        assertThat(response.counts()).containsEntry("case", 3).containsEntry("run", 1);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    @DisplayName("scope=all：命中当前用户可见的全部项目，并带项目名")
    void 跨项目搜索() {
        when(access.visibleProjects()).thenReturn(List.of(
                new Project(1L, "P1", "项目1", null, true, 1L, "private"),
                new Project(2L, "P2", "项目2", null, true, 1L, "private")));
        repository.put(1L, "case", List.of(hit("case", 1, 1L)), 1);
        repository.put(2L, "case", List.of(hit("case", 2, 2L)), 1);

        SearchService.Response response = service.search(PROJECT, "登录", "all", "case", 5);

        assertThat(response.scope()).isEqualTo("all");
        assertThat(response.items()).extracting(SearchService.Item::projectId).containsExactly(1L, 2L);
        assertThat(response.items()).extracting(SearchService.Item::projectName)
                .containsExactly("项目1", "项目2");
        assertThat(response.counts()).containsEntry("case", 2);
    }

    @Test
    @DisplayName("scope=all 但可见项目为空：退化为当前项目，避免空结果")
    void 可见项目为空退化() {
        when(access.visibleProjects()).thenReturn(List.of());
        repository.put(PROJECT, "asset", List.of(hit("asset", 9, PROJECT)), 1);

        SearchService.Response response = service.search(PROJECT, "cred", "all", "asset", 5);

        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.projectId()).isEqualTo(PROJECT));
    }

    private static SearchRepository.Hit hit(String type, long id, long projectId) {
        return new SearchRepository.Hit(type, id, type + "-" + id, "subtitle", projectId, UPDATED,
                "/" + type + "?id=" + id, null);
    }

    /** 只按「项目 + 类型」准备数据，limit<=0 只回 COUNT 不回行（与真实仓储同口径）。 */
    private static final class FakeSearchRepository implements SearchRepository {

        private final Map<String, Map<Long, TypeResult>> data = new HashMap<>();

        void put(long projectId, String type, List<Hit> hits, long total) {
            data.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                    .put(projectId, new TypeResult(hits, total));
        }

        @Override
        public TypeResult search(long projectId, String query, String type, int limit) {
            TypeResult full = data.getOrDefault(type, Map.of())
                    .getOrDefault(projectId, new TypeResult(List.of(), 0));
            List<Hit> hits = limit <= 0 ? List.of()
                    : full.hits().stream().limit(limit).toList();
            return new TypeResult(hits, full.total());
        }
    }
}
