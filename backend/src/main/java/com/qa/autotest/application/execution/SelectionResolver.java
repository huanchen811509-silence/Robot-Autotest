package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 选择快照四模式解析（W57-27）：ALL / SET / CASE / MIXED。
 *
 * <p>所有查询都带 {@code projectId} 作用域，且入口先 {@code access.bindProject} +
 * {@code access.require}，绝不绕过租户过滤。解析结果按 id 升序去重，保证同输入同摘要。
 * MIXED = 用例集成员 ∪ 单选用例（去重）。命中数 &gt; {@link #MAX_CASES} 直接拒绝。
 */
@Service
public class SelectionResolver {

    public enum SelectionMode {
        ALL, SET, CASE, MIXED
    }

    /** 单次选择上限。 */
    public static final int MAX_CASES = 10000;

    /**
     * 项目作用域查询抽象：所有方法都必须以 {@code projectId} 为过滤条件。
     * 单测用内存 fake，不连真实 DB。
     */
    public interface CaseScopeQuery {
        List<Long> allCaseIds(long projectId, Map<String, Object> filter);

        List<Long> caseIdsOfSets(long projectId, List<Long> setIds);

        /** 只保留确属该项目的 id（两项目隔离的关键）。 */
        List<Long> retainProjectCaseIds(long projectId, List<Long> caseIds);

        Map<Long, String> caseCodes(long projectId, List<Long> ids);

        long count(long projectId, Map<String, Object> filter);
    }

    /** 解析结果：升序去重后的用例 id 清单。 */
    public record Resolution(List<Long> caseIds, int count) {
    }

    private final CaseScopeQuery scope;
    private final AccessControlService access;

    public SelectionResolver(CaseScopeQuery scope, AccessControlService access) {
        this.scope = scope;
        this.access = access;
    }

    public Resolution resolve(long projectId, SelectionMode mode, List<Long> setIds,
                              List<Long> caseIds, Map<String, Object> filter) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (mode == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 mode");
        }
        Map<String, Object> safeFilter = filter == null ? Map.of() : filter;
        List<Long> wantedSets = setIds == null ? List.of() : setIds;
        List<Long> wantedCases = caseIds == null ? List.of() : caseIds;

        List<Long> raw = switch (mode) {
            case ALL -> scope.allCaseIds(projectId, safeFilter);
            case SET -> scope.caseIdsOfSets(projectId, wantedSets);
            case CASE -> scope.retainProjectCaseIds(projectId, wantedCases);
            case MIXED -> {
                List<Long> merged = new ArrayList<>();
                merged.addAll(scope.caseIdsOfSets(projectId, wantedSets));
                merged.addAll(scope.retainProjectCaseIds(projectId, wantedCases));
                yield merged;
            }
        };
        TreeSet<Long> sorted = new TreeSet<>();
        if (raw != null) {
            for (Long id : raw) {
                if (id != null) {
                    sorted.add(id);
                }
            }
        }
        if (sorted.size() > MAX_CASES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "选择命中 " + sorted.size() + " 条，超过上限 " + MAX_CASES + " 条");
        }
        List<Long> ids = List.copyOf(sorted);
        return new Resolution(ids, ids.size());
    }

    /** ALL 模式命中数（项目作用域 + 可选筛选）。 */
    public long count(long projectId, Map<String, Object> filter) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return scope.count(projectId, filter == null ? Map.of() : filter);
    }

    /** 模式白名单解析：未知值一律 400，不静默回落 ALL。 */
    public static SelectionMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 mode");
        }
        try {
            return SelectionMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未知选择模式：" + raw);
        }
    }
}
