package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 命令清单只读预览（W57-19）：聚合指定内核的生成器，产出清单、跳过原因与摘要。
 *
 * <p><b>只读</b>：构造器只有 providers + 权限服务，不持有任何写仓储 / run 服务，
 * 因此不可能写 run、不可能落确认、不可能启动进程。
 */
@Service
public class CommandPreviewService {

    /** 预览结果：digest12 为摘要前 12 位，items 为可执行的命令清单。 */
    public record PreviewResult(String kernel,
                                int requested,
                                int executable,
                                List<String> skipReasons,
                                String digest,
                                String digest12,
                                List<CommandPlan> items) {
    }

    private final List<CommandPlanProvider> providers;
    private final AccessControlService access;

    public CommandPreviewService(List<CommandPlanProvider> providers, AccessControlService access) {
        this.providers = providers;
        this.access = access;
    }

    public PreviewResult preview(long projectId, String kernel, List<Long> caseIds) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (kernel == null || kernel.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 kernel");
        }
        CommandPlanProvider provider = providers.stream()
                .filter(p -> kernel.equalsIgnoreCase(p.kernel()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "未知执行内核：" + kernel));
        List<Long> ids = caseIds == null ? List.of() : caseIds;
        List<CommandPlan> plans = provider.plans(projectId, ids);
        String digest = CommandDigest.digest(plans);

        List<String> skipReasons = new ArrayList<>();
        int missing = ids.size() - plans.size();
        if (missing > 0) {
            skipReasons.add(missing + " 条用例未生成命令：case_script_binding 未命中或绑定字段不全，"
                    + "按口径不编造命令，已跳过");
        }
        for (CommandPlan plan : plans) {
            if (plan.argv().isEmpty()) {
                skipReasons.add("用例 " + plan.caseCode() + " 生成了空 argv，已跳过");
            }
        }
        return new PreviewResult(kernel, ids.size(), plans.size(),
                List.copyOf(skipReasons), digest, digest.substring(0, 12), List.copyOf(plans));
    }
}
