package com.qa.autotest.application.search;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.SearchRepository;
import com.qa.autotest.domain.iam.Project;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局实体搜索（FR-3.1-02）。
 *
 * <p>两类隔离口径：
 * <ul>
 *   <li>{@code scope=current} 只搜当前项目；</li>
 *   <li>{@code scope=all} 搜当前用户可见的全部项目；可见项目为空时退化为当前项目，
 *       避免管理员/新成员拿到空结果。</li>
 * </ul>
 *
 * <p>每个类型单独查、单独 LIMIT：一个类型命中很多时不能把其他类型挤没，
 * 也不能因为总数大就拉全量。LIKE 的转义在仓储层完成（{@code %}/{@code _} 按字面匹配）。
 */
@Service
public class SearchService {

    /** 与前端下拉框顺序一致。 */
    public static final List<String> DEFAULT_TYPES = List.of("case", "run", "report", "plan", "asset");

    private static final int MAX_LIMIT = 50;

    public record Item(String type, long id, String title, String subtitle, long projectId,
                       String projectName, String path, String updatedAt, String badge) {
    }

    public record Response(String query, String scope, List<Item> items,
                           Map<String, Integer> counts, boolean truncated) {
    }

    private final SearchRepository repository;
    private final AccessControlService access;
    private final ProjectRepository projects;

    public SearchService(SearchRepository repository, AccessControlService access,
                         ProjectRepository projects) {
        this.repository = repository;
        this.access = access;
        this.projects = projects;
    }

    public Response search(long projectId, String query, String scope, String types, int limit) {
        access.bindProject(projectId);
        String normalizedScope = "all".equalsIgnoreCase(scope) ? "all" : "current";
        String keyword = query == null ? "" : query.trim();
        Set<String> requested = parseTypes(types);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : requested) {
            counts.put(type, 0);
        }
        if (keyword.isEmpty()) {
            return new Response("", normalizedScope, List.of(), counts, false);
        }
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Long> scopedProjects = resolveProjects(projectId, normalizedScope);
        Map<Long, String> projectNames = projectNames(projectId, scopedProjects);

        List<Item> items = new ArrayList<>();
        boolean truncated = false;
        for (String type : requested) {
            int remaining = capped;
            int collected = 0;
            long total = 0;
            for (long pid : scopedProjects) {
                SearchRepository.TypeResult result =
                        repository.search(pid, keyword, type, Math.max(remaining, 0));
                total += result.total();
                if (remaining > 0 && !result.hits().isEmpty()) {
                    for (SearchRepository.Hit hit : result.hits()) {
                        items.add(new Item(hit.type(), hit.id(), hit.title(), hit.subtitle(),
                                hit.projectId(), projectNames.getOrDefault(hit.projectId(), ""),
                                hit.path(), hit.updatedAt() == null ? null : hit.updatedAt().toString(),
                                hit.badge()));
                        collected++;
                        remaining--;
                        if (remaining == 0) {
                            break;
                        }
                    }
                }
            }
            counts.put(type, Math.toIntExact(Math.min(total, Integer.MAX_VALUE)));
            if (total > collected) {
                truncated = true;
            }
        }
        return new Response(keyword, normalizedScope, items, counts, truncated);
    }

    private Set<String> parseTypes(String types) {
        Set<String> parsed = new LinkedHashSet<>();
        if (types == null || types.isBlank()) {
            parsed.addAll(DEFAULT_TYPES);
            return parsed;
        }
        for (String raw : types.split(",")) {
            String type = raw.trim().toLowerCase();
            if (DEFAULT_TYPES.contains(type)) {
                parsed.add(type);
            }
        }
        if (parsed.isEmpty()) {
            parsed.addAll(DEFAULT_TYPES);
        }
        return parsed;
    }

    private List<Long> resolveProjects(long projectId, String scope) {
        if (!"all".equals(scope)) {
            return List.of(projectId);
        }
        List<Long> ids = access.visibleProjects().stream()
                .map(Project::id)
                .distinct()
                .toList();
        return ids.isEmpty() ? List.of(projectId) : ids;
    }

    private Map<Long, String> projectNames(long currentId, List<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (long id : ids) {
            projects.findById(id).ifPresent(project -> names.put(id, project.name()));
        }
        if (!names.containsKey(currentId)) {
            projects.findById(currentId)
                    .ifPresent(project -> names.put(currentId, project.name()));
        }
        return names;
    }
}
