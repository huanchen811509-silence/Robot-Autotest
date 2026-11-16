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

    /**
     * 删除前影响面（v5.11.0 / W61-15 / FR-v5.11-07）。
     *
     * <p>三个数字在**确认弹窗里先给用户看**，再决定删不删；它们与删除的拦截条件是两回事：
     * 拦截仍只看脚本绑定（{@code caseReferences}），这里多出来的「用例集引用」删除时会
     * 按既有策略级联移出（{@code TestCaseRepositoryJdbc.delete} 先删 test_set_item）。
     */
    public record CaseImpact(long setRefs, long scriptBindings, long runResults) {
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
    /**
     * 条件选择规格（v5.8.0 / FR-v5.8-02）。
     *
     * <p><b>为什么需要它</b>：界面上的「全选所有命中」只是视觉状态 —— 下发时只能带当前页的 ids，
     * 于是「筛出 500 条批量删除」实际只删了当前页。这里让调用方把<b>筛选条件本身</b>交上来，
     * 由服务端按同一套查询解析成真实 id 列表。
     *
     * <p><b>字段与列表查询一一对应</b>（{@code cases.page} 的 6 个维度）。故意不写成通用 Map：
     * 传了不存在的键会被严格反序列化直接 400，而不是「以为筛了其实没筛」。
     * {@code excludeIds} 用于「全选后手动取消勾选几条」。
     *
     * <p>{@code mode} 只支持 {@code filter}：ids 模式直接用 {@code ids}，不需要本对象。
     */
    public record CaseSelectionSpec(String mode, String keyword, String domain, String priority,
                                    String reviewStatus, String capability, String safetyLevel,
                                    List<Long> excludeIds) {
    }

    public record BatchRequest(String action, List<Long> ids, String comment,
                               String priority, String domainTag, String safetyLevel,
                               CaseSelectionSpec selection) {

        /** 兼容 6 参写法（只有 ids 的老调用方）。 */
        public BatchRequest(String action, List<Long> ids, String comment,
                            String priority, String domainTag, String safetyLevel) {
            this(action, ids, comment, priority, domainTag, safetyLevel, null);
        }
    }

    /** 逐条结果：失败带稳定错误码名（NOT_FOUND / CONFLICT / INTERNAL_ERROR）。 */
    public record BatchItemResult(long id, boolean ok, String code, String message) {
    }

    /**
     * 批量结果：部分成功语义（FR-v0.2-38）——逐条独立事务，失败不影响其他。
     *
     * <p>undoToken 仅对可逆动作且确有成功条目时给出；delete 明确不可撤销，恒为 null。
     */
    /**
     * 批量结果。{@code resolvedCount} 是「条件解析出的 id 数」，用于前端比对
     * 「界面上显示命中 N 条」与「服务端这次真的处理了 M 条」——两者不一致说明期间数据变了，
     * 前端据此提示刷新，而不是假装成功。
     */
    public record BatchResult(int total, int succeeded, int failed, List<BatchItemResult> items,
                              String undoToken, int resolvedCount) {

        /** 兼容 5 参写法：非条件模式下解析数即条目数。 */
        public BatchResult(int total, int succeeded, int failed, List<BatchItemResult> items,
                           String undoToken) {
            this(total, succeeded, failed, items, undoToken, total);
        }
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
            // v5.21.0 / FR-v5.21-03：软删行仍占着 uk_test_case_project_code。
            // 「删了再建同编号」不该报「已存在」——用户按编号查不到它，等于死锁在唯一键上。
            // 这里把软删行**复活**并覆盖内容，语义是"重新登记这条用例"。
            var deletedId = cases.findSoftDeletedId(projectId, code);
            if (deletedId.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "用例编号已存在：" + code);
            }
            long restoredId = deletedId.get();
            cases.restore(projectId, restoredId, TenantContext.userId());
            cases.update(toCase(restoredId, projectId, command, ReviewStatus.DRAFT,
                    resolveSafetyLevel(command.safetyLevel(), null)), TenantContext.userId());
            saveSteps(restoredId, command.steps());
            audit.record("case.restore", "test_case", String.valueOf(restoredId), "code=" + code);
            invalidateCapabilities(projectId);
            return get(projectId, restoredId);
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

    /**
     * 删除前的影响面预览（FR-v5.11-07）。
     *
     * <p><b>只读，不加 {@code @Audited}</b>：这个端点只数引用，不改任何数据。
     * 曾经因为插入位置不当，把 {@code case:delete} 的注解顶到了本方法头上——
     * 结果是「查一次影响面」被记成一次删除审计，而真正的删除反而没留痕。
     */
    public CaseImpact impact(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        var counts = cases.impactOf(projectId, id);
        return new CaseImpact(counts.setRefs(), counts.scriptBindings(), counts.runResults());
    }

    @Audited(action = "case:delete", resource = "test_case")
    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.DELETE);
        TestCase existing = cases.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        // v5.9.0 / W59-20：被脚本绑定引用时不许删，并**点名**占着它的去向与条数。
        // 只说「删除失败」会让用户挨个页面找，删之前先告诉他被谁引用、几条。
        java.util.Map<String, Long> refs = cases.caseReferences(projectId, id);
        if (refs != null && !refs.isEmpty()) {
            StringBuilder who = new StringBuilder();
            refs.forEach((label, count) -> {
                if (who.length() > 0) {
                    who.append("、");
                }
                who.append(label).append(' ').append(count).append(" 条");
            });
            throw new BusinessException(ErrorCode.CONFLICT,
                    "用例被以下数据引用，不能删除：" + who + "；请先解除引用");
        }
        cases.delete(projectId, id, TenantContext.userId());
        // 审计口径不变：删除只记前值（`deleted=true`），不留 after。
        // 软删这件事写进前值里，读审计的人能分清"这次没有物理删"。
        audit.recordChange("case:delete", "test_case", String.valueOf(id),
                "code=" + existing.caseCode() + "; title=" + existing.title() + "; mode=soft",
                null);
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
        List<Long> ids = resolveIds(projectId, request);
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
        return new BatchResult(items.size(), succeeded, items.size() - succeeded, items,
                undoToken, ids.size());
    }

    /**
     * 把「条件选择」解析成真实 id 列表（FR-v5.8-02）。
     *
     * <p>三条硬规则：
     * <ul>
     *   <li>{@code ids} 与 {@code selection} <b>互斥</b>：同时给会 400 并点名两个字段，
     *       不做「以谁为准」的猜测 —— 猜错就是删错数据；</li>
     *   <li>条件命中超过批量上限 <b>直接 400</b> 并说明实际命中数，不做静默截断；</li>
     *   <li>解析结果为空（命中 0 条或全被 {@code excludeIds} 排除）也 400，
     *       让「点了没反应」变成一句能读的错。</li>
     * </ul>
     *
     * <p>只取一页（上限 + 1）即可判定超限，不把整表读进内存。
     */
    private List<Long> resolveIds(long projectId, BatchRequest request) {
        boolean hasIds = request.ids() != null && !request.ids().isEmpty();
        boolean hasSelection = request.selection() != null;
        if (hasIds && hasSelection) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "ids 与 selection 不能同时提供，请二选一");
        }
        if (!hasSelection) {
            return request.ids();
        }
        CaseSelectionSpec sel = request.selection();
        String mode = sel.mode() == null ? "" : sel.mode().trim();
        if (!"filter".equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "selection.mode 仅支持 filter（ids 模式请直接用 ids）");
        }
        PageResult<TestCase> hit = cases.page(projectId, sel.keyword(), sel.domain(), sel.priority(),
                sel.reviewStatus(), sel.capability(), sel.safetyLevel(),
                PageQuery.of(1, BATCH_MAX + 1));
        if (hit.total() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "条件命中 " + hit.total() + " 条，超过单次批量上限 " + BATCH_MAX
                            + " 条，请缩小筛选范围后重试");
        }
        List<Long> exclude = sel.excludeIds() == null ? List.of() : sel.excludeIds();
        List<Long> ids = new ArrayList<>(hit.items().size());
        for (TestCase one : hit.items()) {
            if (one.id() != null && !exclude.contains(one.id())) {
                ids.add(one.id());
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "条件命中 " + hit.total() + " 条，但可执行 0 条（可能全被排除）");
        }
        return ids;
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
                    cases.delete(projectId, id, TenantContext.userId());
                    detail = "code=" + existing.caseCode() + "; title=" + existing.title()
                            + "; mode=soft";
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
