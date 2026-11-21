package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.SafetyBlockedException;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用例集立即执行（EXEC-01 / FR-v0.2-63；EXEC-03 起测试级）。
 *
 * <p><b>v5.35 / FR-UIE-09：按内核分派。</b>此前这里只有一条 RF 口径——用
 * {@link CaseScriptResolver} 扫 {@code .robot} 的 {@code case:<编号>} 标签。结果是
 * pytest 工程的用例集即使 {@code case_script_binding} 里 nodeId 齐全，也会被判成
 * 「未绑定脚本（N 条）」，还提示用户去写 RF 的 {@code [Tags]}。现在按
 * {@code project.test_kernel} 分流：
 * <ul>
 *   <li>{@code pytest} → 读 {@code case_script_binding} 得 nodeId，交
 *       {@link PytestSetRunLauncher}（与工作台同一套执行/入库/证据链）；</li>
 *   <li>其它（robot）→ 原路径不变，逐字保留既有文案与行为。</li>
 * </ul>
 *
 * <p>安全闸不因分派放宽：L2/LX 一律整单拒绝（ADR-0013），real 仍需显式
 * {@code confirmReal=true}。
 */
@Service
public class TestSetRunService {

    private final TestSetRepository sets;
    private final AccessControlService access;
    private final CaseScriptResolver resolver;
    private final QuickRunService quickRuns;
    private final KernelRouter kernels;
    private final CaseScriptBindingRepository bindings;
    private final PytestSetRunLauncher pytestSets;

    public TestSetRunService(TestSetRepository sets, AccessControlService access,
                             CaseScriptResolver resolver, QuickRunService quickRuns,
                             KernelRouter kernels, CaseScriptBindingRepository bindings,
                             PytestSetRunLauncher pytestSets) {
        this.sets = sets;
        this.access = access;
        this.resolver = resolver;
        this.quickRuns = quickRuns;
        this.kernels = kernels;
        this.bindings = bindings;
        this.pytestSets = pytestSets;
    }

    public RunRecord run(TestSetRunCommand cmd) {
        if (cmd == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请求为空");
        }
        access.bindProject(cmd.projectId());
        access.require(PermissionResource.EXECUTION, PermissionAction.EXECUTE);

        TestSet set = sets.find(cmd.projectId(), cmd.testSetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "用例集不存在: " + cmd.testSetId()));
        List<TestCase> cases = sets.items(cmd.projectId(), cmd.testSetId());
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集为空，无法触发执行");
        }
        List<Long> blank = cases.stream()
                .filter(testCase -> testCase.caseCode() == null || testCase.caseCode().isBlank())
                .map(TestCase::id)
                .toList();
        if (!blank.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "用例集 " + set.setCode() + " 存在 " + blank.size()
                            + " 条用例编号为空（id=" + joinIds(blank) + "），无法解析脚本绑定");
        }

        // G2-3 / ADR-0013：集合内存在 L2 高危或 LX 未判定用例即整单拒绝，不静默剔除。
        // 回执带结构化清单（FR-UIE-10）：用户要知道「哪几条、什么级别」，而不是自己去翻列表。
        List<Map<String, String>> blocked = new ArrayList<>();
        for (TestCase testCase : cases) {
            SafetyLevel level = SafetyLevel.from(testCase.safetyLevel());
            if (level.blocksExecution()) {
                Map<String, String> one = new LinkedHashMap<>();
                one.put("caseCode", testCase.caseCode().trim());
                one.put("caseId", String.valueOf(testCase.id()));
                one.put("safetyLevel", level.name());
                blocked.add(one);
            }
        }
        if (!blocked.isEmpty()) {
            throw new SafetyBlockedException(
                    "用例集 " + set.setCode() + " 含禁止自动执行的用例（L2 高危 / LX 未判定按 L2 对待）："
                            + blockedSummary(blocked), blocked);
        }

        String kernel = kernels.kernelOf(cmd.projectId());
        if (isPytest(kernel)) {
            return runPytest(cmd, set, cases);
        }

        Resolution resolution = resolve(cmd.projectId(), cases);
        if (!resolution.unbound().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "用例集 " + set.setCode() + " 存在未绑定脚本的用例（"
                            + resolution.unbound().size() + " 条）："
                            + String.join(", ", resolution.unbound())
                            + "。请在脚本测试的 [Tags] 中写入 case:<用例编号>，"
                            + "或让测试名与用例编号一致后再执行");
        }

        return quickRuns.trigger(new QuickRunService.QuickRunCommand(
                cmd.projectId(), cmd.testSetId(), resolution.suitePaths(),
                cmd.environmentId(), cmd.deviceKeys(), cmd.concurrency(),
                cmd.timeoutSeconds(), cmd.testTimeoutSeconds(), cmd.includeTag(), cmd.runRequestId(),
                resolution.testNames(), "test-set"));
    }

    /** pytest 分支：编号 → nodeId → 内核执行。 */
    private RunRecord runPytest(TestSetRunCommand cmd, TestSet set, List<TestCase> cases) {
        List<CaseScriptBinding> bound = bindings.listByCaseIds(cmd.projectId(),
                cases.stream().map(TestCase::id).toList());
        Map<Long, String> nodeByCase = new LinkedHashMap<>();
        for (CaseScriptBinding one : bound) {
            if (one.nodeId() != null && !one.nodeId().isBlank()
                    && one.kernel() != null && "pytest".equalsIgnoreCase(one.kernel().trim())) {
                nodeByCase.putIfAbsent(one.caseId(), one.nodeId().trim());
            }
        }
        List<String> nodeIds = new ArrayList<>();
        List<String> unbound = new ArrayList<>();
        for (TestCase testCase : cases) {
            String nodeId = nodeByCase.get(testCase.id());
            if (nodeId == null) {
                unbound.add(testCase.caseCode().trim());
            } else {
                nodeIds.add(nodeId);
            }
        }
        if (!unbound.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "用例集 " + set.setCode() + " 存在未绑定脚本的用例（"
                            + unbound.size() + " 条）：" + String.join(", ", unbound)
                            + "。这些用例在 pytest 用例库中还没有脚本绑定，"
                            + "请到「pytest 工作台」执行一次绑定同步或人工确认后再执行");
        }
        String mode = cmd.mode() == null || cmd.mode().isBlank()
                ? "fake" : cmd.mode().trim().toLowerCase(Locale.ROOT);
        if ("real".equals(mode) && !Boolean.TRUE.equals(cmd.confirmReal())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "real 模式需要显式确认：请求体加 confirmReal=true（真实执行会登录 DUT）");
        }
        return pytestSets.launch(new PytestSetRunLauncher.LaunchCommand(
                cmd.projectId(), cmd.testSetId(), set.setCode(), nodeIds, mode,
                cmd.deviceKeys(), Map.of(), cmd.runRequestId(), cmd.timeoutSeconds()));
    }

    private static boolean isPytest(String kernel) {
        return kernel != null && "pytest".equalsIgnoreCase(kernel.trim());
    }

    private static String joinIds(List<Long> ids) {
        List<String> values = new ArrayList<>();
        ids.forEach(id -> values.add(String.valueOf(id)));
        return String.join(", ", values);
    }

    /** 拒绝文案：超过 10 条只列前 10 条。 */
    private static String blockedSummary(List<Map<String, String>> blocked) {
        int limit = 10;
        List<String> items = new ArrayList<>();
        for (Map<String, String> one : blocked.subList(0, Math.min(limit, blocked.size()))) {
            items.add(one.get("caseCode") + "(" + one.get("safetyLevel") + ")");
        }
        String head = String.join(", ", items);
        return blocked.size() <= limit ? head : head + " 等 " + blocked.size() + " 条";
    }

    /** 用例编号 → 套件路径与测试名；未命中即为未绑定。 */
    Resolution resolve(long projectId, List<TestCase> cases) {
        Set<String> wanted = new LinkedHashSet<>();
        for (TestCase testCase : cases) {
            if (testCase.caseCode() != null && !testCase.caseCode().isBlank()) {
                wanted.add(testCase.caseCode().trim());
            }
        }
        Map<String, List<CaseScriptResolver.Binding>> bindingsByCode =
                resolver.resolve(projectId, wanted);

        List<String> unbound = new ArrayList<>();
        Set<String> suites = new LinkedHashSet<>();
        Set<String> testNames = new LinkedHashSet<>();
        for (String code : wanted) {
            List<CaseScriptResolver.Binding> hit = bindingsByCode.get(code);
            if (hit == null || hit.isEmpty()) {
                unbound.add(code);
            } else {
                hit.forEach(binding -> {
                    suites.add(binding.suitePath());
                    testNames.add(binding.testName());
                });
            }
        }
        return new Resolution(new ArrayList<>(suites), new ArrayList<>(testNames), unbound);
    }

    record Resolution(List<String> suitePaths, List<String> testNames, List<String> unbound) {
    }

    public record TestSetRunCommand(long projectId,
                                    long testSetId,
                                    Long environmentId,
                                    List<String> deviceKeys,
                                    Integer concurrency,
                                    Integer timeoutSeconds,
                                    Integer testTimeoutSeconds,
                                    String includeTag,
                                    String runRequestId,
                                    String mode,
                                    Boolean confirmReal) {
    }
}
