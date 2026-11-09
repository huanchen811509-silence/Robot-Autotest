package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.PrecheckRepository;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.node.ExecutionNode;
import com.qa.autotest.domain.plan.PlanCycleException;
import com.qa.autotest.domain.plan.PlanDependencyGraph;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行预检分级（FR-3.1-06）。
 *
 * <p>把「点了才报错」的检查前移成一次调用：每个检查项给出 BLOCK/WARN/OK，
 * 前端按最高级别决定是否允许提交，并能提前展示原因与修复提示。
 *
 * <p>预计耗时用「最近样本平均单条耗时 × 条数 / 分片数」；没有历史样本时返回 0，
 * sampleSize 也为 0，前端据此显示「暂无估算」而不是伪造一个数字。
 */
@Service
public class RunPrecheckService {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";
    public static final String OK = "OK";

    private static final int MAX_SAMPLE = 100;

    public record Request(long projectId, Long testSetId, Long planId, List<Long> caseIds,
                          Integer shards, Long nodeId) {
    }

    public record Check(String key, String level, String title, String detail, String hint) {
    }

    public record Result(String verdict, List<Check> checks, int estimatedTotal,
                         long estimatedSeconds, int sampleSize) {
    }

    private final PrecheckRepository precheck;
    private final CredentialRepository credentials;
    private final ExecutionNodeRepository nodes;
    private final PlanRepository plans;
    private final AccessControlService access;

    public RunPrecheckService(PrecheckRepository precheck, CredentialRepository credentials,
                              ExecutionNodeRepository nodes, PlanRepository plans,
                              AccessControlService access) {
        this.precheck = precheck;
        this.credentials = credentials;
        this.nodes = nodes;
        this.plans = plans;
        this.access = access;
    }

    public Result run(Request request) {
        long projectId = request.projectId();
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.EXECUTE);

        List<Check> checks = new ArrayList<>();
        int total = estimatedTotal(request);
        int risky = riskyCount(request);
        int shards = request.shards() == null || request.shards() <= 0 ? 1 : request.shards();

        checks.add(total <= 0
                ? new Check("EMPTY_CASES", BLOCK, "没有可执行用例",
                        "未指定用例、用例集或计划，或目标内没有用例", "先选择用例/用例集，或检查计划绑定")
                : new Check("EMPTY_CASES", OK, "用例数量正常",
                        "预计执行 " + total + " 条", null));

        checks.add(risky > 0
                ? new Check("SAFETY_LEVEL", BLOCK, "包含高危用例",
                        risky + " 条用例安全级为 L2/LX，需要审批后执行", "先在用例台账完成审批，或移除高危用例")
                : new Check("SAFETY_LEVEL", OK, "安全级通过", "没有 L2/LX 用例", null));

        if (request.nodeId() != null) {
            ExecutionNode node = nodes.find(request.nodeId()).orElse(null);
            checks.add(node == null
                    ? new Check("NODE_OFFLINE", BLOCK, "执行节点不存在",
                            "节点 " + request.nodeId() + " 未注册", "选择已注册的在线节点")
                    : node.status().equals(ExecutionNode.ONLINE)
                            ? new Check("NODE_OFFLINE", OK, "节点在线", node.name(), null)
                            : new Check("NODE_OFFLINE", BLOCK, "执行节点离线",
                                    node.name() + " 当前状态为 " + node.status(), "等待节点心跳恢复或改用其他节点"));
        }

        checks.add(credentials.list(projectId).isEmpty()
                ? new Check("CREDENTIAL_MISSING", WARN, "缺少凭据",
                        "项目内没有可用凭据，涉及登录的用例可能失败", "在资产池补充凭据")
                : new Check("CREDENTIAL_MISSING", OK, "凭据已配置", "项目内已有凭据", null));

        if (request.planId() != null) {
            checks.add(planCycleCheck(projectId, request.planId()));
        }

        int sampleSize = 0;
        long estimatedSeconds = 0;
        PrecheckRepository.DurationSample sample = precheck.durationSample(projectId, MAX_SAMPLE);
        if (sample.count() > 0 && sample.avgMillis() > 0 && total > 0) {
            sampleSize = sample.count();
            double seconds = sample.avgMillis() / 1000.0 * total / shards;
            estimatedSeconds = (long) Math.ceil(seconds);
        }

        String verdict = checks.stream().anyMatch(check -> BLOCK.equals(check.level()))
                ? BLOCK
                : checks.stream().anyMatch(check -> WARN.equals(check.level())) ? WARN : OK;
        return new Result(verdict, checks, total, estimatedSeconds, sampleSize);
    }

    private int estimatedTotal(Request request) {
        List<Long> caseIds = request.caseIds();
        if (caseIds != null && !caseIds.isEmpty()) {
            return precheck.countCases(request.projectId(), caseIds);
        }
        if (request.testSetId() != null) {
            return precheck.countTestSetCases(request.projectId(), request.testSetId());
        }
        if (request.planId() != null) {
            return precheck.countPlanCases(request.projectId(), request.planId());
        }
        return 0;
    }

    private int riskyCount(Request request) {
        List<Long> caseIds = request.caseIds();
        if (caseIds != null && !caseIds.isEmpty()) {
            return precheck.countRiskyCases(request.projectId(), caseIds);
        }
        if (request.testSetId() != null) {
            return precheck.countRiskyTestSetCases(request.projectId(), request.testSetId());
        }
        if (request.planId() != null) {
            return precheck.countRiskyPlanCases(request.projectId(), request.planId());
        }
        return 0;
    }

    private Check planCycleCheck(long projectId, long planId) {
        if (plans.find(projectId, planId).isEmpty()) {
            return new Check("PLAN_CYCLE", BLOCK, "计划不存在", "计划 " + planId + " 不在当前项目",
                    "选择当前项目内的计划");
        }
        Map<Long, List<Long>> dependencies = plans.allDependencies(projectId);
        try {
            PlanDependencyGraph.of(dependencies).topologicalOrder();
            return new Check("PLAN_CYCLE", OK, "依赖无环", "计划依赖图可拓扑排序", null);
        } catch (PlanCycleException e) {
            return new Check("PLAN_CYCLE", BLOCK, "计划依赖成环", e.getMessage(),
                    "在计划页解除循环依赖");
        }
    }
}
