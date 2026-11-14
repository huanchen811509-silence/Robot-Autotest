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
 * RF 命令清单生成器（W57-18）：只认 {@code case_script_binding.kernel='robot'} 的可信绑定。
 *
 * <p><b>降级口径</b>：绑定不存在、script_path 为空、或用例台账查不到编号时，该用例直接跳过
 * （不进结果清单），由 {@link CommandPreviewService} 标注跳过原因，绝不编造 argv / 测试名。
 *
 * <p><b>语法预检口径</b>：RF 的 {@code --dryrun} 语法错时退出码仍为 0，因此判断是否语法错
 * 必须检查输出是否含 {@code [ ERROR ]}（见 {@link #hasDryrunSyntaxError(String)}），
 * 绝不能把退出码当成功标志。本类只提供该判定，不实际拉起 dryrun 进程（预览是纯只读）。
 */
@Component
public class RobotCommandPlanProvider implements CommandPlanProvider {

    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final ExecutionProperties props;

    public RobotCommandPlanProvider(CaseScriptBindingRepository bindings,
                                    TestCaseRepository cases,
                                    ExecutionProperties props) {
        this.bindings = bindings;
        this.cases = cases;
        this.props = props;
    }

    @Override
    public String kernel() {
        return "robot";
    }

    /** RF dryrun 语法错退出码仍为 0，只能靠输出里的 [ ERROR ] 判定。 */
    public static boolean hasDryrunSyntaxError(String dryrunOutput) {
        return dryrunOutput != null && dryrunOutput.contains("[ ERROR ]");
    }

    @Override
    public List<CommandPlan> plans(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        Path cwd = ExecutionPaths.resolve(props.getWorkspaceRoot()).toAbsolutePath().normalize();
        Path outDir = ExecutionPaths.resolve(props.getArtifactsRoot()).toAbsolutePath().normalize();
        int timeout = props.getRunTimeoutSeconds();
        List<CommandPlan> plans = new ArrayList<>();
        for (Long caseId : caseIds) {
            if (caseId == null) {
                continue;
            }
            Optional<CaseScriptBinding> binding =
                    bindings.findByCase(projectId, caseId, kernel());
            if (binding.isEmpty() || binding.get().scriptPath() == null
                    || binding.get().scriptPath().isBlank()) {
                continue;
            }
            Optional<TestCase> testCase = cases.find(projectId, caseId);
            if (testCase.isEmpty() || testCase.get().caseCode() == null
                    || testCase.get().caseCode().isBlank()) {
                continue;
            }
            List<String> argv = List.of(
                    "robot",
                    "--outputdir", outDir.toString(),
                    binding.get().scriptPath().trim());
            plans.add(new CommandPlan(testCase.get().caseCode().trim(), argv,
                    cwd.toString(), timeout, null));
        }
        return List.copyOf(plans);
    }
}
