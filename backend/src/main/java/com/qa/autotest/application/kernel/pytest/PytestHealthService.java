package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * pytest 工程健康检查与绑定统计（FR-v5.1-12 / FR-v5.1-05，验收 A28/A29）。
 *
 * <p>回答界面上的四个问题：<b>能不能跑</b>（解释器 + 契约）、<b>脚本在哪</b>
 * （仓库可达性）、<b>接上了多少</b>（绑定四类计数）、<b>上次为什么失败</b>（原因码）。
 *
 * <p>不触发扫描：GET 只读，避免打开页面就把 62 个文件读一遍。
 * 需要最新绑定状态时由用户点「重新同步」（POST）。
 */
@Service
public class PytestHealthService {

    private final ProjectSettingRepository settings;
    private final PytestContractProbe probe;
    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;

    public PytestHealthService(ProjectSettingRepository settings, PytestContractProbe probe,
                               CaseScriptBindingRepository bindings, TestCaseRepository cases) {
        this.settings = settings;
        this.probe = probe;
        this.bindings = bindings;
        this.cases = cases;
    }

    /** 健康检查结果。{@code hints} 是给人看的一句话建议，不可为空数组以外的形态。 */
    public record Health(boolean healthy,
                         String pythonBin,
                         String pythonVersion,
                         String minPythonVersion,
                         String pythonVersionProblem,
                         String pytestVersion,
                         List<String> foundOptions,
                         List<String> contractMissing,
                         String contractFailure,
                         boolean repoConfigured,
                         boolean repoReachable,
                         String repoPath,
                         String modeDefault,
                         long totalCases,
                         long bound,
                         long unbound,
                         long pendingCases,
                         long totalExcludingPending,
                         long boundExcludingPending,
                         double coveragePercent,
                         double coverageExcludingPendingPercent,
                         List<String> hints) {
    }

    /** 绑定列表 + 计数（双口径并列，见 {@link PytestCoverage}）。 */
    public record BindingStats(long totalCases,
                               long bound,
                               long unbound,
                               long pendingCases,
                               long totalExcludingPending,
                               long boundExcludingPending,
                               long orphanScripts,
                               long duplicateRisk,
                               double coveragePercent,
                               double coverageExcludingPendingPercent,
                               List<CaseScriptBinding> items) {
    }

    public Health health(long projectId, boolean refreshProbe) {
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);
        Path repoPath = PytestProjectSettings.repoPath(config);
        boolean repoConfigured = repoPath != null;
        boolean repoReachable = repoConfigured && Files.isDirectory(repoPath);

        List<String> contractMissing = new ArrayList<>();
        String pytestVersion = null;
        String pythonVersion = null;
        String versionProblem = null;
        List<String> foundOptions = List.of();
        String failure = null;
        if (pythonBin == null) {
            contractMissing.addAll(List.of("--mode", "--env_config", "--junitxml"));
            failure = "未配置 pytest.python_bin";
        } else {
            PytestContractProbe.ProbeResult result = probe.probe(pythonBin, refreshProbe);
            pytestVersion = result.pytestVersion();
            pythonVersion = result.pythonVersion();
            versionProblem = result.versionProblem();
            foundOptions = result.foundOptions();
            failure = result.failureReason();
            if (result.healthy()) {
                if (!result.contract().supportsMode()) {
                    contractMissing.add("--mode");
                }
                if (!result.contract().supportsEnvConfig()) {
                    contractMissing.add("--env_config");
                }
                if (!result.contract().supportsJunitXml()) {
                    contractMissing.add("--junitxml");
                }
            } else {
                contractMissing.addAll(List.of("--mode", "--env_config", "--junitxml"));
            }
        }

        PytestCoverage coverage = coverage(projectId);

        List<String> hints = new ArrayList<>();
        if (!repoConfigured) {
            hints.add("未配置仓库路径：去「工程配置」填 pytest.repo_path");
        } else if (!repoReachable) {
            hints.add("仓库路径不可达：" + repoPath);
        }
        if (pythonBin == null) {
            hints.add("未配置解释器：去「工程配置」填 pytest.python_bin");
        } else if (versionProblem != null) {
            hints.add(versionProblem);
        } else if (!contractMissing.isEmpty()) {
            hints.add("契约不满足：缺 " + String.join(", ", contractMissing)
                    + "（检查用例库版本，或点「重新探测」）");
        }
        if (failure != null) {
            hints.add("上次探测失败原因：" + failure);
        }
        boolean healthy = repoReachable && contractMissing.isEmpty() && versionProblem == null;
        return new Health(healthy, pythonBin, pythonVersion, PytestContractProbe.MIN_PYTHON,
                versionProblem, pytestVersion, foundOptions, List.copyOf(contractMissing),
                failure, repoConfigured, repoReachable,
                repoPath == null ? null : repoPath.toString(),
                PytestProjectSettings.modeDefault(config),
                coverage.totalCases(), coverage.bound(), coverage.unbound(),
                coverage.pendingCases(), coverage.totalExcludingPending(),
                coverage.boundExcludingPending(), coverage.coveragePercent(),
                coverage.coverageExcludingPendingPercent(), List.copyOf(hints));
    }

    /**
     * 绑定统计（只读 DB）。
     *
     * <p>{@code orphanScripts} 与 {@code duplicateRisk} 需要扫盘才知道；GET 不算，
     * 保持为 0 并由页面提示「点同步获取」。这样「打开页面」永远是一次轻查询。
     */
    public BindingStats bindingStats(long projectId) {
        PytestCoverage coverage = coverage(projectId);
        return new BindingStats(coverage.totalCases(), coverage.bound(), coverage.unbound(),
                coverage.pendingCases(), coverage.totalExcludingPending(),
                coverage.boundExcludingPending(), 0, 0, coverage.coveragePercent(),
                coverage.coverageExcludingPendingPercent(),
                bindings.listByProject(projectId, "pytest"));
    }

    /**
     * 双口径覆盖率，统一走 {@link PytestCoverage}。
     *
     * <p>已绑定编号由绑定行反查（`caseId → 编号`），不额外扫盘：
     * 打开页面必须是一次轻查询，扫 62 个文件是「同步」按钮的事。
     */
    private PytestCoverage coverage(long projectId) {
        Map<String, String> priorities = cases.casePriorities(projectId);
        Map<Long, String> idToCode = cases.idToCode(projectId);
        List<String> boundCodes = new ArrayList<>();
        for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
            String code = idToCode.get(binding.caseId());
            if (code != null) {
                boundCodes.add(code);
            }
        }
        if (priorities.isEmpty()) {
            // 假仓储/旧实现没有优先级表：退回单口径，分母用总数、待评审计 0
            long total = cases.countByProject(projectId);
            long bound = boundCodes.size();
            return new PytestCoverage(total, bound, 0, bound);
        }
        return PytestCoverage.of(priorities, boundCodes);
    }
}
