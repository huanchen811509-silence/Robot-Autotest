package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.Audited;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import com.qa.autotest.domain.caseasset.Priority;
import com.qa.autotest.domain.caseasset.ReviewSnapshot;
import com.qa.autotest.domain.caseasset.ReviewStatus;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestCaseStep;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 用例台账（CAS-02）：台账可独立于脚本存在（ADR-0005）。 */
@Service
public class TestCaseService {

    /** 步骤表单。 */
    public record StepCommand(int stepNo, String action, String expectation) {
    }

    /** 用例写入表单。safetyLevel 留空即 LX（未判定，ADR-0013）。 */
    public record CaseCommand(String caseCode, String title, String category, String priority,
                              String precondition, String expected, String domainTag,
                              String capability, String description, List<StepCommand> steps,
                              String safetyLevel) {
    }

    /** 用例详情（台账 + 步骤）。 */
    public record CaseDetail(TestCase testCase, List<TestCaseStep> steps) {
    }

    /** 批量动作（UX-02，FR-v0.2-37；setSafetyLevel 为 G2-3 / ADR-0013 的人工补判入口）。 */
    public static final Set<String> BATCH_ACTIONS = Set.of("submit", "approve", "reject",
            "setPriority", "setDomainTag", "setSafetyLevel", "delete");

    /** 可撤销的批量动作：delete 记录已不存在，无法恢复，明确排除（FR-3.1-04）。 */
    public static final Set<String> UNDOABLE_ACTIONS = Set.of("submit", "approve", "reject",
            "setPriority", "setDomainTag", "setSafetyLevel");

    /** 单次批量上限（FR-v0.2-39）。 */
    public static final int BATCH_MAX = 200;

    /**
     * 批量请求：reject 必须带 comment，setPriority 必须带 priority，
     * setDomainTag 必须带 domainTag，setSafetyLevel 必须带 safetyLevel。
     */
    public record BatchRequest(String action, List<Long> ids, String comment,
                               String priority, String domainTag, String safetyLevel) {
    }

    /** 逐条结果：失败带稳定错误码名（NOT_FOUND / CONFLICT / INTERNAL_ERROR）。 */
    public record BatchItemResult(long id, boolean ok, String code, String message) {
    }

    /**
     * 批量结果：部分成功语义（FR-v0.2-38）——逐条独立事务，失败不影响其他。
     *
     * <p>undoToken 仅对可逆动作且确有成功条目时给出；delete 明确不可撤销，恒为 null。
     */
    public record BatchResult(int total, int succeeded, int failed, List<BatchItemResult> items,
                              String undoToken) {
    }

    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final BatchUndoRecorder undoRecorder;

    /** 能力索引缓存；测试装配入口不注入时为 null（行为等价于无缓存）。 */
    private CapabilityCache capabilityCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCapabilityCache(CapabilityCache capabilityCache) {
        this.capabilityCache = capabilityCache;
    }

    /** Spring 装配入口：批量走逐条独立事务，成功后写撤销凭据。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TestCaseService(TestCaseRepository cases, AccessControlService access, AuditService audit,
                           PlatformTransactionManager txManager, BatchUndoRecorder undoRecorder) {
        this(cases, access, audit, new TransactionTemplate(txManager), undoRecorder);
    }

    /** 测试装配入口：不起事务代理、无撤销记录器（与既有测试一致）。 */
    public TestCaseService(TestCaseRepository cases, AccessControlService access, AuditService audit) {
        this(cases, access, audit, (TransactionTemplate) null, null);
    }

    /** 测试装配入口：有事务代理、无撤销记录器（既有 CaseBatchIT 使用）。 */
    public TestCaseService(TestCaseRepository cases, AccessControlService access, AuditService audit,
                           PlatformTransactionManager txManager) {
        this(cases, access, audit, new TransactionTemplate(txManager), null);
    }

    private TestCaseService(TestCaseRepository cases, AccessControlService access, AuditService audit,
                            TransactionTemplate tx, BatchUndoRecorder undoRecorder) {
        this.cases = cases;
        this.access = access;
        this.audit = audit;
        this.tx = tx;
        this.undoRecorder = undoRecorder;
    }

    public PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                                     String reviewStatus, String capability, String safetyLevel,
                                     PageQuery paging) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return cases.page(projectId, keyword, domain, priority, reviewStatus, capability,
                safetyLevel, paging);
    }

    /** 兼容入口：未指定排序时按 id DESC（FR-3.2-02）。 */
    public PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                                     String reviewStatus, String capability, String safetyLevel,
                                     int page, int size) {
        return page(projectId, keyword, domain, priority, reviewStatus, capability,
                safetyLevel, PageQuery.of(page, size));
    }

    public CaseDetail get(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        TestCase testCase = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        return new CaseDetail(testCase, cases.steps(id));
    }

    @Transactional
    public CaseDetail create(long projectId, CaseCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.CREATE);
        String code = requireCode(command.caseCode());
        if (cases.codeExists(projectId, code, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用例编号已存在：" + code);
        }
        long id = cases.insert(toCase(null, projectId, command, ReviewStatus.DRAFT,
                resolveSafetyLevel(command.safetyLevel(), null)), TenantContext.userId());
        saveSteps(id, command.steps());
        audit.record("case.create", "test_case", String.valueOf(id), "code=" + code);
        invalidateCapabilities(projectId);
        return get(projectId, id);
    }

    @Transactional
    public CaseDetail update(long projectId, long id, CaseCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        TestCase existing = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        String code = requireCode(command.caseCode());
        if (!code.equals(existing.caseCode()) && cases.codeExists(projectId, code, id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用例编号已存在：" + code);
        }
        cases.update(toCase(id, projectId, command, ReviewStatus.from(existing.reviewStatus()),
                resolveSafetyLevel(command.safetyLevel(), existing.safetyLevel())),
                TenantContext.userId());
        if (command.steps() != null) {
            saveSteps(id, command.steps());
        }
        audit.record("case.update", "test_case", String.valueOf(id), "code=" + code);
        invalidateCapabilities(projectId);
        return get(projectId, id);
    }

    @Audited(action = "case:delete", resource = "test_case")
    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.DELETE);
        TestCase existing = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        cases.delete(projectId, id);
        audit.recordChange("case:delete", "test_case", String.valueOf(id),
                "code=" + existing.caseCode() + "; title=" + existing.title(), null);
        invalidateCapabilities(projectId);
    }

    /** 提交评审：draft / rejected → pending。 */
    public CaseDetail submit(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        TestCase existing = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        ReviewStatus current = ReviewStatus.from(existing.reviewStatus());
        if (current != ReviewStatus.DRAFT && current != ReviewStatus.REJECTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不可提交评审：" + current.code());
        }
        cases.updateReview(projectId, id, ReviewStatus.PENDING.code(), null, null, null);
        audit.record("case.submit-review", "test_case", String.valueOf(id), "");
        return get(projectId, id);
    }

    /** 评审结论：pending → approved / rejected。 */
    public CaseDetail review(long projectId, long id, boolean approved, String comment) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        TestCase existing = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        if (ReviewStatus.from(existing.reviewStatus()) != ReviewStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待评审用例可给出结论");
        }
        ReviewStatus target = approved ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        cases.updateReview(projectId, id, target.code(), comment,
                TenantContext.userId(), Instant.now());
        audit.record(approved ? "case.approve" : "case.reject", "test_case",
                String.valueOf(id), comment == null ? "" : comment);
        return get(projectId, id);
    }

    public List<CapabilityStat> capabilities(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return cases.capabilities(projectId);
    }

    /**
     * 批量操作（UX-02，FR-v0.2-36~39）。
     *
     * <p>契约硬规则（DEV-PLAN §10.5，不许简化）：
     * <ul>
     *   <li>部分成功返回 200 + 逐条结果；</li>
     *   <li>逐条独立事务，单条失败不影响其他（有事务管理器时按条提交）；</li>
     *   <li>仅「ids 空 / 超 200 / action 未知 / 动作专属参数缺失」返回 400；</li>
     *   <li>每条写一次审计并带 {@code batch=<id>} 标识。</li>
     * </ul>
     */
    public BatchResult batch(long projectId, BatchRequest request) {
        access.bindProject(projectId);
        String action = request.action() == null ? "" : request.action().trim();
        if (!BATCH_ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "未知批量动作：" + request.action());
        }
        List<Long> ids = request.ids();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "ids 不能为空");
        }
        if (ids.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单次批量上限 " + BATCH_MAX + " 条，请分批提交");
        }
        switch (action) {
            case "reject" -> requireText(request.comment(), "驳回必须填写意见");
            case "setPriority" -> requirePriority(request.priority());
            case "setDomainTag" -> requireText(request.domainTag(), "技术域标签不能为空");
            case "setSafetyLevel" -> requireSafetyLevel(request.safetyLevel());
            default -> {
            }
        }
        // 权限一次判定：删除走 DELETE，其余走 UPDATE（与单条操作同口径）
        access.require(PermissionResource.CASE,
                "delete".equals(action) ? PermissionAction.DELETE : PermissionAction.UPDATE);

        // batch 标识进审计 detail（FR-v0.2-39）：不动表结构，同批次可反查
        String batchTag = "batch=" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        boolean undoable = UNDOABLE_ACTIONS.contains(action);
        List<BatchItemResult> items = new ArrayList<>(ids.size());
        List<ReviewSnapshot> snapshots = new ArrayList<>(ids.size());
        for (long id : ids) {
            // 快照必须在动作前读取；只收集成功条目，避免撤销把失败条目也“恢复”成旧值
            Optional<ReviewSnapshot> before = undoable
                    ? cases.reviewSnapshot(projectId, id) : Optional.empty();
            BatchItemResult result = tx != null
                    ? tx.execute(status -> applyOne(projectId, id, action, request, batchTag))
                    : applyOne(projectId, id, action, request, batchTag);
            items.add(result);
            if (result != null && result.ok() && before.isPresent()) {
                snapshots.add(before.get());
            }
        }
        int succeeded = (int) items.stream().filter(BatchItemResult::ok).count();
        if (succeeded > 0) {
            invalidateCapabilities(projectId);
        }
        String undoToken = undoRecorder == null || !undoable
                ? null : undoRecorder.record(projectId, action, snapshots);
        return new BatchResult(items.size(), succeeded, items.size() - succeeded, items, undoToken);
    }

    /** 用例写成功后让能力索引缓存失效：保证变更后第一次读就回源（EW32-03）。 */
    private void invalidateCapabilities(long projectId) {
        if (capabilityCache != null) {
            capabilityCache.invalidate(projectId);
        }
    }

    /** 单条处理：异常转逐条结果，绝不向上抛（部分成功语义）。 */
    private BatchItemResult applyOne(long projectId, long id, String action, BatchRequest req,
                                     String batchTag) {
        try {
            TestCase existing = cases.find(projectId, id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
            ReviewStatus current = ReviewStatus.from(existing.reviewStatus());
            String detail;
            switch (action) {
                case "submit" -> {
                    if (current != ReviewStatus.DRAFT && current != ReviewStatus.REJECTED) {
                        throw new BusinessException(ErrorCode.CONFLICT,
                                "当前状态不可提交评审：" + current.code());
                    }
                    cases.updateReview(projectId, id, ReviewStatus.PENDING.code(), null, null, null);
                    detail = "code=" + existing.caseCode();
                }
                case "approve" -> {
                    if (current != ReviewStatus.PENDING) {
                        throw new BusinessException(ErrorCode.CONFLICT, "只有待评审用例可通过");
                    }
                    cases.updateReview(projectId, id, ReviewStatus.APPROVED.code(), null,
                            TenantContext.userId(), Instant.now());
                    detail = "code=" + existing.caseCode();
                }
                case "reject" -> {
                    if (current != ReviewStatus.PENDING) {
                        throw new BusinessException(ErrorCode.CONFLICT, "只有待评审用例可驳回");
                    }
                    cases.updateReview(projectId, id, ReviewStatus.REJECTED.code(), req.comment(),
                            TenantContext.userId(), Instant.now());
                    detail = "code=" + existing.caseCode() + "; comment=" + req.comment();
                }
                case "setPriority" -> {
                    String priority = requirePriority(req.priority());
                    cases.updatePriority(projectId, id, priority, TenantContext.userId());
                    detail = "code=" + existing.caseCode() + "; priority=" + priority;
                }
                case "setDomainTag" -> {
                    String domainTag = req.domainTag().trim();
                    cases.updateDomainTag(projectId, id, domainTag, TenantContext.userId());
                    detail = "code=" + existing.caseCode() + "; domain=" + domainTag;
                }
                case "setSafetyLevel" -> {
                    String safetyLevel = requireSafetyLevel(req.safetyLevel());
                    cases.updateSafetyLevel(projectId, id, safetyLevel, TenantContext.userId());
                    detail = "code=" + existing.caseCode() + "; safetyLevel=" + safetyLevel;
                }
                case "delete" -> {
                    if (current == ReviewStatus.APPROVED) {
                        throw new BusinessException(ErrorCode.CONFLICT, "已通过用例不可批量删除，请先驳回");
                    }
                    cases.delete(projectId, id);
                    detail = "code=" + existing.caseCode() + "; title=" + existing.title();
                }
                default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "未知批量动作：" + action);
            }
            audit.record("case.batch." + action, "test_case", String.valueOf(id),
                    batchTag + "; " + detail);
            return new BatchItemResult(id, true, null, null);
        } catch (BusinessException ex) {
            return new BatchItemResult(id, false, ex.errorCode().codeName(), ex.getMessage());
        } catch (RuntimeException ex) {
            return new BatchItemResult(id, false, ErrorCode.INTERNAL_ERROR.codeName(), ex.toString());
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
        }
    }

    private static String requirePriority(String value) {
        requireText(value, "改优先级必须指定 P0/P1/P2/P3");
        try {
            return Priority.from(value).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
        }
    }

    private static String requireSafetyLevel(String value) {
        requireText(value, "改安全级必须指定 L0/L1/L2/LX");
        try {
            return SafetyLevel.from(value).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
        }
    }

    /** 安全级取值：表单留空时沿用 fallback，create 无 fallback 即 LX（未判定）。 */
    private static String resolveSafetyLevel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank()
                    ? SafetyLevel.LX.name() : SafetyLevel.from(fallback).name();
        }
        return requireSafetyLevel(value);
    }

    private void saveSteps(long caseId, List<StepCommand> steps) {
        if (steps == null) {
            return;
        }
        List<TestCaseStep> rows = new java.util.ArrayList<>();
        int position = 1;
        for (StepCommand step : steps) {
            int no = step.stepNo() > 0 ? step.stepNo() : position;
            rows.add(new TestCaseStep(null, caseId, no, step.action(), step.expectation()));
            position++;
        }
        cases.replaceSteps(caseId, rows, TenantContext.userId());
    }

    private static TestCase toCase(Long id, long projectId, CaseCommand c, ReviewStatus status,
                                   String safetyLevel) {
        Priority priority;
        try {
            priority = Priority.from(c.priority());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
        }
        return new TestCase(id, projectId, requireCode(c.caseCode()), c.title(), c.category(),
                priority.name(), c.precondition(), c.expected(), c.domainTag(), status.code(),
                "manual", c.capability(), c.description(), safetyLevel);
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例编号不能为空");
        }
        return code.trim();
    }
}
