package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.ReviewStatus;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 勾选台账用例直接执行（EXEC-02，v0.4 候选提前实现）。
 *
 * <p>与「按套件路径 / 按用例集」触发（EXEC-01）的区别：本包按<b>台账用例</b>解析绑定，
 * 并把命中测试名作为 {@code --test} 透传给 pabot，因此执行粒度是<b>测试</b>而不是整个套件。
 * {@code --test} 的实跑效果按 ADR-0013 不在开发期验证，留给验收人现场确认（TC-EXEC-08 同类）。
 *
 * <p>绑定规则见 {@link CaseScriptResolver}（ADR-0005 §3 的 {@code case:<case_code>} 标签，
 * 兼容测试名等于编号的兜底）。任何一条所选用例未绑定，整单 400 并列出编号，不静默跳过。
 */
@Service
public class CaseRunService {

    /** 与批量操作上限一致，挡住误全选。 */
    static final int MAX_CASES = 200;

    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final CaseScriptResolver resolver;
    private final QuickRunService quickRuns;

    public CaseRunService(TestCaseRepository cases, AccessControlService access,
                          CaseScriptResolver resolver, QuickRunService quickRuns) {
        this.cases = cases;
        this.access = access;
        this.resolver = resolver;
        this.quickRuns = quickRuns;
    }

    /**
     * 未定级用例的执行口径（v5.31.0 / FR-v5.31-07 / ADR-0013 §4）。
     *
     * <p>{@code block} = 原行为（LX 按 L2 对待，整单 403）；{@code confirm} = 允许携带
     * 「已知悉」凭据执行。默认 block，ADR 修订未生效前不会改变任何现网行为。
     */
    private String unratedMode = "block";

    /**
     * 评审态是否进执行门禁（v5.31.0 / FR-v5.31-08）。默认 {@code false}：只软提示不阻断。
     * 置 {@code true} 时未批准（draft/pending/rejected）用例被拦，供将来收紧。
     */
    private boolean requireApproved = false;

    private AuditService audit;

    @org.springframework.beans.factory.annotation.Value("${execution.unrated-mode:block}")
    void setUnratedMode(String mode) {
        this.unratedMode = (mode == null || mode.isBlank()) ? "block" : mode.trim().toLowerCase();
    }

    @org.springframework.beans.factory.annotation.Value("${execution.require-approved:false}")
    void setRequireApproved(boolean require) {
        this.requireApproved = require;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAudit(AuditService audit) {
        this.audit = audit;
    }

    public RunRecord run(CaseRunCommand cmd) {
        if (cmd == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "请求为空");
        }
        List<Long> ids = cmd.caseIds();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "至少选择一条用例");
        }
        if (ids.size() > MAX_CASES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单次最多执行 " + MAX_CASES + " 条用例，请分批提交");
        }
        access.bindProject(cmd.projectId());
        access.require(PermissionResource.EXECUTION, PermissionAction.EXECUTE);

        Selection selection = collect(cmd.projectId(), ids);
        // G2-3 / ADR-0013：L2 一律阻断；LX 由 execution.unrated-mode 决定阻断或知情确认
        if (!selection.blocked().isEmpty()) {
            throw new BusinessException(ErrorCode.SAFETY_LEVEL_BLOCKED,
                    "以下用例安全级禁止自动执行（L2 高危）："
                            + blockedSummary(selection.blocked()));
        }
        if (!selection.unrated().isEmpty()) {
            if (!confirmMode()) {
                throw new BusinessException(ErrorCode.SAFETY_LEVEL_BLOCKED,
                        "以下用例未定级（LX），默认按 L2 对待，禁止自动执行："
                                + blockedSummary(selection.unrated())
                                + "。可先批量定级，或开启知情确认后重试");
            }
            if (!Boolean.TRUE.equals(cmd.confirmUnrated())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "本次包含未定级用例 " + selection.unrated().size()
                                + " 条，需显式确认后才能执行");
            }
            if (audit != null) {
                audit.record("case.run.unrated.confirm", "test_case",
                        String.valueOf(cmd.projectId()),
                        "count=" + selection.unrated().size()
                                + ";codes=" + codesSummary(selection.unrated()));
            }
        }
        // FR-v5.31-08：默认只提示；开关打开时才拦，配置改了行为立刻变，不留「显示不生效」。
        if (requireApproved && selection.unreviewed() > 0) {
            throw new BusinessException(ErrorCode.SAFETY_LEVEL_BLOCKED,
                    "已开启「仅允许已批准用例执行」，本次有 " + selection.unreviewed()
                            + " 条用例未通过评审，请先在用例评审中处理");
        }
        Set<String> codes = selection.codes();

        Map<String, List<CaseScriptResolver.Binding>> bindings = resolver.resolve(cmd.projectId(), codes);
        List<String> unbound = new ArrayList<>();
        Set<String> suites = new LinkedHashSet<>();
        Set<String> testNames = new LinkedHashSet<>();
        for (String code : codes) {
            List<CaseScriptResolver.Binding> hit = bindings.get(code);
            if (hit == null || hit.isEmpty()) {
                unbound.add(code);
                continue;
            }
            for (CaseScriptResolver.Binding binding : hit) {
                suites.add(binding.suitePath());
                testNames.add(binding.testName());
            }
        }
        if (!unbound.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "存在未绑定脚本的用例（" + unbound.size() + " 条）："
                            + String.join(", ", unbound)
                            + "。请在脚本测试的 [Tags] 中写入 case:<用例编号>，"
                            + "或让测试名与用例编号一致后再执行");
        }

        return quickRuns.trigger(new QuickRunService.QuickRunCommand(
                cmd.projectId(), null, new ArrayList<>(suites),
                cmd.environmentId(), cmd.deviceKeys(), cmd.concurrency(),
                cmd.timeoutSeconds(), cmd.testTimeoutSeconds(), cmd.includeTag(), cmd.runRequestId(),
                new ArrayList<>(testNames), "cases"));
    }

    /**
     * 安全级预检（EXEC-15 残余 / ADR-0013 / G2-3）。
     *
     * <p>前端勾选是**跨页**的，当前页之外那些用例的安全级前端根本看不到，
     * 只能在点「执行所选」时被后端整单 403 兜底——体验上等于「点了才知道不行」。
     * 这里按 id 一次性判级，把被拦的用例连同编号、安全级返回，前端可提前禁用并写明原因。
     *
     * <p>与 {@link #run} 用同一段判级逻辑（{@link #collect}），不会出现「预检放行、执行被拒」的错位。
     */
    public SafetyPrecheck precheck(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "至少选择一条用例");
        }
        if (caseIds.size() > MAX_CASES) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单次最多执行 " + MAX_CASES + " 条用例，请分批提交");
        }
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        Selection selection = collect(projectId, caseIds);
        boolean allowed = selection.blocked().isEmpty()
                && (selection.unrated().isEmpty() || confirmMode())
                && !(requireApproved && selection.unreviewed() > 0);
        return new SafetyPrecheck(selection.codes().size(), allowed,
                selection.blocked(), selection.unrated(), selection.unreviewed(),
                requireApproved);
    }

    private boolean confirmMode() {
        return "confirm".equals(unratedMode);
    }

    /** 逐条取用例并判级：执行与预检共用，保证两条路径的结论一致。 */
    private Selection collect(long projectId, List<Long> ids) {
        Set<String> codes = new LinkedHashSet<>();
        List<BlockedCase> blocked = new ArrayList<>();
        List<BlockedCase> unrated = new ArrayList<>();
        int unreviewed = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            TestCase testCase = cases.find(projectId, id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "用例不存在: " + id));
            if (testCase.caseCode() == null || testCase.caseCode().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "用例缺少编号，无法解析脚本: " + id);
            }
            SafetyLevel level = SafetyLevel.from(testCase.safetyLevel());
            // FR-v5.31-08：评审态默认只做软提示，不改判定；开关打开时才计入拦截。
            if (ReviewStatus.from(testCase.reviewStatus()) != ReviewStatus.APPROVED) {
                unreviewed++;
            }
            if (level == SafetyLevel.LX) {
                unrated.add(new BlockedCase(testCase.id(), testCase.caseCode().trim(), level.name()));
            } else if (level.blocksExecution()) {
                blocked.add(new BlockedCase(testCase.id(), testCase.caseCode().trim(), level.name()));
            }
            codes.add(testCase.caseCode().trim());
        }
        if (codes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "至少选择一条用例");
        }
        return new Selection(codes, blocked, unrated, unreviewed);
    }

    private static String codesSummary(List<BlockedCase> items) {
        int limit = 10;
        String head = items.subList(0, Math.min(limit, items.size())).stream()
                .map(BlockedCase::caseCode)
                .collect(Collectors.joining(","));
        return items.size() <= limit ? head : head + ",...(" + items.size() + ")";
    }

    /** 拒绝文案：超过 10 条只列前 10 条，避免长列表撑爆界面。 */
    private static String blockedSummary(List<BlockedCase> blocked) {
        int limit = 10;
        String head = blocked.subList(0, Math.min(limit, blocked.size())).stream()
                .map(item -> item.caseCode() + "(" + item.safetyLevel() + ")")
                .collect(Collectors.joining(", "));
        return blocked.size() <= limit ? head : head + " 等 " + blocked.size() + " 条";
    }

    private record Selection(Set<String> codes, List<BlockedCase> blocked,
                             List<BlockedCase> unrated, int unreviewed) {
    }

    /** 被安全级拦下的用例：编号给用户看，id 给前端勾选状态用。 */
    public record BlockedCase(long caseId, String caseCode, String safetyLevel) {
    }

    /**
     * 预检结果：{@code allowed = false} 表示这一单不能执行，{@code blocked} 逐条给出原因。
     * {@code unrated} 单列未定级用例（v5.31.0）：confirm 模式下它们不阻断，但要展示并确认。
     */
    public record SafetyPrecheck(int total, boolean allowed, List<BlockedCase> blocked,
                                 List<BlockedCase> unrated, int unreviewed,
                                 boolean requireApproved) {

        /** 兼容构造器：旧调用方只关心 blocked。 */
        public SafetyPrecheck(int total, boolean allowed, List<BlockedCase> blocked) {
            this(total, allowed, blocked, List.of(), 0, false);
        }
    }

    public record CaseRunCommand(long projectId,
                                 List<Long> caseIds,
                                 Long environmentId,
                                 List<String> deviceKeys,
                                 Integer concurrency,
                                 Integer timeoutSeconds,
                                 Integer testTimeoutSeconds,
                                 String includeTag,
                                 String runRequestId,
                                 Boolean confirmUnrated) {

        /** 兼容构造器：九参旧签名（confirmUnrated 缺省为未确认）。 */
        public CaseRunCommand(long projectId, List<Long> caseIds, Long environmentId,
                              List<String> deviceKeys, Integer concurrency, Integer timeoutSeconds,
                              Integer testTimeoutSeconds, String includeTag, String runRequestId) {
            this(projectId, caseIds, environmentId, deviceKeys, concurrency, timeoutSeconds,
                    testTimeoutSeconds, includeTag, runRequestId, null);
        }
    }
}
