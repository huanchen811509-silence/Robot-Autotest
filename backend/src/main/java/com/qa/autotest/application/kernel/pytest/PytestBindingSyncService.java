package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例 ↔ 脚本绑定同步（FR-v5.1-04 / 验收 A8–A12，v5.1.2 的覆盖率看板数据源）。
 *
 * <p>只做「扫盘 → 建绑定 → 出四类计数」，不做修复、不写用例库源码（D2 方案 A）。
 *
 * <p><b>计数口径（D12 拍板：待评审进分母但单列）</b>：
 * <ul>
 *   <li>{@code totalCases}：项目内全部用例数，**含**待评审 —— 这是 469 的口径；</li>
 *   <li>{@code bound + unbound = totalCases} 是硬恒等式，机检直接断言；</li>
 *   <li>{@code orphanScripts}：脚本存在但库中无此编号（脚本侧问题，不占用例分母）；</li>
 *   <li>{@code duplicateBindings}：一个编号命中多个脚本（取 DOCSTRING 优先者生效，
 *       其余计入重复，用于推动清理，**不影响** bound 计数）。</li>
 * </ul>
 *
 * <p>组织方按「排除待评审」口径算覆盖率时，分母取非「待评审」优先级的子集，
 * 页脚必须同时列出两个数（13.0% / 10.3%），不得只报一个。计算统一走
 * {@link PytestCoverage}，本类不自己算百分比。
 *
 * <p><b>重命名跟随</b>（H4 / v5.1.2）：全量重扫的代价是「文件名一改，旧绑定变成
 * 一条 unbound + 一条 orphan」。可文件名本来就是编号来源之一（`test_im_net_131.py`），
 * 改名是常规操作而不是错误。因此重扫时先用 `script_sha256` 对齐上一轮绑定：
 * 内容没变、路径变了 → 认定是重命名，把绑定跟到新路径，既不报 orphan 也不报 unbound。
 */
@Service
public class PytestBindingSyncService {

    /** 内核名常量，避免对 {@code PytestKernel.NAME} 产生反向依赖。 */
    private static final String KERNEL = "pytest";

    private final ProjectSettingRepository settings;
    private final TestCaseRepository cases;
    private final CaseScriptBindingRepository bindings;

    public PytestBindingSyncService(ProjectSettingRepository settings,
                                    TestCaseRepository cases,
                                    CaseScriptBindingRepository bindings) {
        this.settings = settings;
        this.cases = cases;
        this.bindings = bindings;
    }

    /** 同步结果。 */
    public record SyncReport(long totalCases,
                             long bound,
                             long unbound,
                             long pendingCases,
                             long totalExcludingPending,
                             long boundExcludingPending,
                             long orphanScripts,
                             long duplicateBindings,
                             long renamedScripts,
                             long scriptsScanned,
                             List<String> orphanCodes,
                             List<String> duplicateCodes,
                             List<String> unboundCodes,
                             List<String> renamedPairs) {

        public boolean balanced() {
            return bound + unbound == totalCases;
        }

        /** 覆盖率（%），分母含待评审（D12 口径 A）。 */
        public double coveragePercent() {
            return totalCases == 0 ? 0.0 : Math.round(bound * 1000.0 / totalCases) / 10.0;
        }

        /** 覆盖率（%），排除待评审（D12 口径 B）——注意这个数**更低**，不是更高。 */
        public double coverageExcludingPendingPercent() {
            return totalExcludingPending == 0 ? 0.0
                    : Math.round(boundExcludingPending * 1000.0 / totalExcludingPending) / 10.0;
        }
    }

    /**
     * 全量重扫并重建绑定。
     *
     * <p>先清空该项目的 pytest 绑定再重建：增量同步在「脚本被删」时无法发现，
     * 而全量重扫的成本只是遍历 62 个文件（实测 < 200ms），不值得为增量优化引入漂移风险。
     * 重命名不靠增量识别，靠重建前后的 `sha256` 对齐（见类注释）。
     */
    @Transactional
    public SyncReport sync(long projectId, Long operatorId) {
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        Path repoRoot = PytestProjectSettings.repoPath(config);
        if (repoRoot == null) {
            throw new IllegalStateException("未配置 pytest.repo_path，无法同步绑定");
        }
        List<PytestScriptScanner.ScannedScript> scripts;
        try {
            scripts = PytestScriptScanner.scan(repoRoot);
        } catch (IOException e) {
            throw new IllegalStateException("扫描仓库失败: " + repoRoot + " — " + e.getMessage(), e);
        }

        Map<String, String> priorities = cases.casePriorities(projectId);
        Set<String> caseCodes = priorities.isEmpty()
                ? new LinkedHashSet<>(cases.listCaseCodes(projectId))
                : new LinkedHashSet<>(priorities.keySet());
        Map<String, Long> codeToId = codeToId(projectId);

        Map<String, List<PytestScriptScanner.ScannedScript>> byCode = new LinkedHashMap<>();
        List<PytestScriptScanner.ScannedScript> unresolved = new ArrayList<>();
        for (PytestScriptScanner.ScannedScript script : scripts) {
            if (!caseCodes.contains(script.caseCode())) {
                unresolved.add(script);
                continue;
            }
            byCode.computeIfAbsent(script.caseCode(), key -> new ArrayList<>()).add(script);
        }

        // 重命名跟随：把「编号对不上」的脚本按内容哈希对齐上一轮绑定
        Map<String, CaseScriptBinding> previousBySha = previousBindingsBySha(projectId);
        List<String> renamedPairs = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        for (PytestScriptScanner.ScannedScript script : unresolved) {
            CaseScriptBinding previous = script.scriptSha256() == null
                    ? null : previousBySha.get(script.scriptSha256());
            String followedCode = previous == null ? null : codeOf(projectId, previous, codeToId);
            if (followedCode != null && caseCodes.contains(followedCode)
                    && !byCode.containsKey(followedCode)) {
                byCode.put(followedCode, List.of(script));
                renamedPairs.add(followedCode + ": " + previous.scriptPath()
                        + " → " + script.relativePath());
                continue;
            }
            orphans.add(script.caseCode() + " (" + script.relativePath() + ")");
        }

        bindings.deleteByProjectAndKernel(projectId, KERNEL);

        long bound = 0;
        long duplicates = 0;
        List<String> duplicateCodes = new ArrayList<>();
        List<String> unboundCodes = new ArrayList<>();
        Set<String> boundCodes = new LinkedHashSet<>();
        for (String code : caseCodes) {
            List<PytestScriptScanner.ScannedScript> hits = byCode.get(code);
            if (hits == null || hits.isEmpty()) {
                unboundCodes.add(code);
                continue;
            }
            if (hits.size() > 1) {
                duplicates++;
                duplicateCodes.add(code);
            }
            Long caseId = codeToId.get(code);
            if (caseId == null) {
                // 编号在台账里但拿不到 id（理论不可达）：不写半条绑定，直接记为未绑定
                unboundCodes.add(code);
                continue;
            }
            PytestScriptScanner.ScannedScript chosen = pick(hits);
            bindings.insert(new CaseScriptBinding(null, projectId, caseId, KERNEL,
                    chosen.relativePath(), chosen.nodeId(), chosen.scriptSha256(),
                    chosen.resolveSource(), false), operatorId);
            bound++;
            boundCodes.add(code);
        }
        PytestCoverage coverage = PytestCoverage.of(priorities, boundCodes);
        return new SyncReport(coverage.totalCases(), bound, unboundCodes.size(),
                coverage.pendingCases(), coverage.totalExcludingPending(),
                coverage.boundExcludingPending(), orphans.size(), duplicates,
                renamedPairs.size(), scripts.size(), List.copyOf(orphans),
                List.copyOf(duplicateCodes), List.copyOf(unboundCodes),
                List.copyOf(renamedPairs));
    }

    /** 上一轮绑定按内容哈希索引；哈希为空的旧记录不参与跟随（跟错了比不跟更糟）。 */
    private Map<String, CaseScriptBinding> previousBindingsBySha(long projectId) {
        Map<String, CaseScriptBinding> result = new LinkedHashMap<>();
        for (CaseScriptBinding binding : bindings.listByProject(projectId, KERNEL)) {
            String sha = binding.scriptSha256();
            if (sha != null && !sha.isBlank()) {
                result.putIfAbsent(sha, binding);
            }
        }
        return result;
    }

    private String codeOf(long projectId, CaseScriptBinding binding, Map<String, Long> codeToId) {
        for (Map.Entry<String, Long> entry : codeToId.entrySet()) {
            if (entry.getValue().equals(binding.caseId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 编号 → 用例 id 一次查全，替代逐条 {@code findByCode} 的 N+1。 */
    private Map<String, Long> codeToId(long projectId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : cases.idToCode(projectId).entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }
        return result;
    }

    /**
     * 多命中时的取舍顺序：DOCSTRING 声明最可信 → MANUAL（人工确认过）→ FILENAME。
     * 同来源内按路径字典序取第一个：固定顺序保证同一仓库两次同步结果一致（幂等）。
     */
    private static PytestScriptScanner.ScannedScript pick(
            List<PytestScriptScanner.ScannedScript> hits) {
        return hits.stream()
                .min((a, b) -> {
                    int rank = Integer.compare(rankOf(a.resolveSource()), rankOf(b.resolveSource()));
                    return rank != 0 ? rank : a.relativePath().compareTo(b.relativePath());
                })
                .orElseThrow();
    }

    private static int rankOf(String source) {
        return switch (source) {
            case CaseScriptBinding.SOURCE_DOCSTRING -> 0;
            case CaseScriptBinding.SOURCE_MANUAL -> 1;
            default -> 2;
        };
    }
}
