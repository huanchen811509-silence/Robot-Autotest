package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 一页用例 → 脚本展示视图的批量解析（v5.11.0 / W61-11 / FR-v5.11-05）。
 *
 * <p><b>为什么是「批量」而不是逐条 join</b>：列表页一屏 20~200 条，逐条 join 会让
 * SQL 变成 N+1；这里一次 {@code IN (...)} 取完，再在内存里按「项目内核优先」选一条。
 * 详情与用例集成员走同一个方法（只传 1 个 id），三处口径因此天然一致。
 *
 * <p><b>只读</b>：绑定表的写入口仍只有 {@code PytestBindingSyncService} 与人工覆盖接口，
 * 本类不提供任何写方法（风险 E-2：绑定表不得成为第二个真源）。
 */
@Service
public class CaseScriptLookup {

    private final CaseScriptBindingRepository bindings;
    private final JdbcTemplate jdbc;

    public CaseScriptLookup(CaseScriptBindingRepository bindings, JdbcTemplate jdbc) {
        this.bindings = bindings;
        this.jdbc = jdbc;
    }

    /** 项目的测试内核（{@code project.test_kernel}）；查不到时按 robot 处理。 */
    public String kernelOf(long projectId) {
        List<String> rows = jdbc.queryForList(
                "SELECT test_kernel FROM project WHERE id = ?", String.class, projectId);
        String kernel = rows.isEmpty() ? null : rows.get(0);
        return kernel == null || kernel.isBlank() ? "robot" : kernel.trim();
    }

    /** 批量：caseId → 视图。未出现在返回里的 id 一定命中 {@link CaseScriptView#empty}。 */
    public Map<Long, CaseScriptView> of(long projectId, Collection<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        String kernel = kernelOf(projectId);
        Map<Long, List<CaseScriptBinding>> byCase = new LinkedHashMap<>();
        for (CaseScriptBinding binding : bindings.listByCaseIds(projectId, caseIds)) {
            if (binding.caseId() == null) {
                continue;
            }
            byCase.computeIfAbsent(binding.caseId(), key -> new ArrayList<>()).add(binding);
        }
        Map<Long, CaseScriptView> out = new HashMap<>();
        for (Long caseId : caseIds) {
            List<CaseScriptBinding> own = byCase.get(caseId);
            out.put(caseId, own == null || own.isEmpty()
                    ? CaseScriptView.empty(kernel)
                    : pick(own, kernel));
        }
        return out;
    }

    /** 单条（详情 / 成员行）。 */
    public CaseScriptView one(long projectId, long caseId) {
        return of(projectId, List.of(caseId))
                .getOrDefault(caseId, CaseScriptView.empty(kernelOf(projectId)));
    }

    /**
     * 多内核时选哪条：**当前项目内核优先**，其次 id 最小的那条。
     *
     * <p>不按「最后同步时间」选：同一条用例在两个内核下各有一份脚本时，
     * 用户在看某个项目的用例，就该看到这个项目内核真正会跑的那份。
     */
    private CaseScriptView pick(List<CaseScriptBinding> own, String projectKernel) {
        CaseScriptBinding chosen = null;
        for (CaseScriptBinding binding : own) {
            if (binding.kernel() != null
                    && binding.kernel().equalsIgnoreCase(projectKernel)) {
                chosen = binding;
                break;
            }
        }
        if (chosen == null) {
            for (CaseScriptBinding binding : own) {
                if (chosen == null || compareId(binding, chosen) < 0) {
                    chosen = binding;
                }
            }
        }
        int others = Math.max(0, own.size() - 1);
        return CaseScriptView.bound(chosen.scriptPath(), chosen.verified(), chosen.kernel(),
                others);
    }

    private static int compareId(CaseScriptBinding left, CaseScriptBinding right) {
        long a = left.id() == null ? Long.MAX_VALUE : left.id();
        long b = right.id() == null ? Long.MAX_VALUE : right.id();
        return Long.compare(a, b);
    }
}
