package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * pytest 命令清单生成器（W57-18）：只认 {@code case_script_binding.kernel='pytest'} 的
 * {@code node_id}（基础 nodeid，不含 [param]）。
 *
 * <p><b>降级口径</b>：绑定不存在、node_id 为空、或用例台账查不到编号时，该用例跳过，
 * 不编造 nodeid（ADR-51-02 明确禁止从 nodeid 反解编号，反过来也不允许凭 id 猜 nodeid）。
 * {@code pytest_case_step} 是运行期按 run_id 落库的证据表，命令生成阶段还没有 run，
 * 因此不参与此处解析——命令的唯一可信来源是 {@code case_script_binding}。
 */
@Component
public class PytestCommandPlanProvider implements CommandPlanProvider {

    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final ExecutionProperties props;

    public PytestCommandPlanProvider(CaseScriptBindingRepository bindings,
                                     TestCaseRepository cases,
                                     ExecutionProperties props) {
        this.bindings = bindings;
        this.cases = cases;
        this.props = props;
    }

    @Override
    public String kernel() {
        return "pytest";
    }

    @Override
    public List<CommandPlan> plans(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        Path cwd = ExecutionPaths.resolve(props.getWorkspaceRoot()).toAbsolutePath().normalize();
        Path report = ExecutionPaths.resolve(props.getArtifactsRoot()).toAbsolutePath()
                .normalize().resolve("junit.xml");
        int timeout = props.getRunTimeoutSeconds();
        List<CommandPlan> plans = new ArrayList<>();
        for (Long caseId : caseIds) {
            if (caseId == null) {
                continue;
            }
            Optional<CaseScriptBinding> binding =
                    bindings.findByCase(projectId, caseId, kernel());
            if (binding.isEmpty() || binding.get().nodeId() == null
                    || binding.get().nodeId().isBlank()) {
                continue;
            }
            Optional<TestCase> testCase = cases.find(projectId, caseId);
            if (testCase.isEmpty() || testCase.get().caseCode() == null
                    || testCase.get().caseCode().isBlank()) {
                continue;
            }
            List<String> argv = List.of(
                    "pytest", "-q",
                    "--junitxml", report.toString(),
                    binding.get().nodeId().trim());
            plans.add(new CommandPlan(testCase.get().caseCode().trim(), argv,
                    cwd.toString(), timeout, null));
        }
        return List.copyOf(plans);
    }
}
