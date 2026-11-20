package com.qa.autotest.application.execution;

import com.qa.autotest.application.caseasset.CaseScriptView;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.iam.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 执行前统一脚本预检（FR-READY-07/10/11，大师裁定「软校验 + 确认跳过」）。
 *
 * <p>三入口（快照 confirm / 老 run / pytest 专用）共用本服务，产出同一份结构化清单，
 * 解决「预览与实跑不同源」（`RunLaunchController:41-44` 的历史偏差）与「61 条静默跳过」
 * （v5.24 UX 评审 P0-2）。
 *
 * <p><b>只读</b>：不写 run、不拦执行；只回答「哪些能跑、哪些要跳过、为什么」。
 * 是否放行由调用方（确认面板/安全闸）决定，real 的 G-1/ADR-0013 闸不受本类影响。
 *
 * <p><b>内核语义</b>：只有 pytest 内核有绑定；robot（含 platform 公共库）全量归
 * {@code notApplicableCount}，不进 unbound、不拦截。
 */
@Service
public class ExecutionScriptPrecheck {

    public static final String REASON_UNBOUND = "UNBOUND";
    public static final String REASON_NODEID_EMPTY = "NODEID_EMPTY";
    public static final String REASON_BINDING_STALE = "BINDING_STALE";

    /** 一条未绑定用例及其原因（供确认面板展示「如何补齐」）。 */
    public record Unbound(long caseId, String caseCode, String reason) {
    }

    /** 预检清单。 */
    public record ScriptPrecheck(int boundCount, List<Unbound> unbound, int notApplicableCount) {
    }

    private final TestCaseRepository cases;
    private final CaseScriptBindingRepository bindings;
    private final ProjectSettingRepository settings;
    private final ProjectRepository projects;

    public ExecutionScriptPrecheck(TestCaseRepository cases,
                                   CaseScriptBindingRepository bindings,
                                   ProjectSettingRepository settings,
                                   ProjectRepository projects) {
        this.cases = cases;
        this.bindings = bindings;
        this.settings = settings;
        this.projects = projects;
    }

    /**
     * 对所选 caseIds 生成脚本预检清单。
     *
     * @param projectId 项目 id
     * @param caseIds   所选用例 id（可空）
     */
    public ScriptPrecheck check(long projectId, List<Long> caseIds) {
        List<Long> ids = caseIds == null ? List.of() : caseIds;
        Project project = projects.findById(projectId).orElse(null);
        String kernel = project == null ? "robot" : project.testKernel();
        if (!CaseScriptView.supportsScript(kernel)) {
            return new ScriptPrecheck(0, List.of(), ids.size());
        }

        Path repoRoot = repoRoot(projectId);
        int bound = 0;
        int notApplicable = 0;
        List<Unbound> unbound = new ArrayList<>();
        for (long caseId : ids) {
            TestCase testCase = cases.find(projectId, caseId).orElse(null);
            String code = testCase == null ? "#" + caseId : testCase.caseCode();
            CaseScriptBinding binding = bindings.findByCase(projectId, caseId, kernel).orElse(null);
            if (binding == null) {
                unbound.add(new Unbound(caseId, code, REASON_UNBOUND));
                continue;
            }
            if (binding.nodeId() == null || binding.nodeId().isBlank()) {
                unbound.add(new Unbound(caseId, code, REASON_NODEID_EMPTY));
                continue;
            }
            if (isStale(repoRoot, binding.scriptPath())) {
                unbound.add(new Unbound(caseId, code, REASON_BINDING_STALE));
                continue;
            }
            bound++;
        }
        return new ScriptPrecheck(bound, List.copyOf(unbound), notApplicable);
    }

    /** 绑定行存在但脚本文件缺失 → STALE（FR-READY-10）。repo_path 未配置时不判 STALE。 */
    private boolean isStale(Path repoRoot, String scriptPath) {
        if (repoRoot == null || scriptPath == null || scriptPath.isBlank()) {
            return false;
        }
        try {
            return !Files.exists(repoRoot.resolve(scriptPath));
        } catch (RuntimeException e) {
            // 非法路径（绝对/越界）按 STALE 处理，不下发
            return true;
        }
    }

    /**
     * 直接读 project_setting 的 pytest. 前缀配置取 repo_path，不依赖适配包
     * （kernel_decoupling 门禁：application/execution 不得引用 application/kernel/pytest 适配包）。
     * 键去掉前缀后为 {@code repo_path}。
     */
    private Path repoRoot(long projectId) {
        try {
            Map<String, String> config = settings.findAll(projectId, "pytest.");
            String raw = config == null ? null : config.get("repo_path");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return Path.of(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
