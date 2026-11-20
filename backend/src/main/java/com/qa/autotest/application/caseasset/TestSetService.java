package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.Audited;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例集（CAS-02）：一条用例可进多个集合，集合内顺序可调。 */
@Service
public class TestSetService {

    /** 用例集三态（SCEN-02 / V11_10）：只允许这三个值，非法值在应用层收口。 */
    public static final String KIND_CASE_SET = "case_set";
    public static final String KIND_SCENARIO = "scenario";
    public static final String KIND_EXECUTION_SELECTION = "execution_selection";
    public static final Set<String> ALLOWED_KINDS =
            Set.of(KIND_CASE_SET, KIND_SCENARIO, KIND_EXECUTION_SELECTION);

    /** FR-49-28：批量加入结果（新增条数 = 筛选结果条数）。 */
    public record CategoryFilterResult(int matched, int added, String itemSource) {
    }

    /**
     * FR-READY-05：按用例筛选条件批量加入（含 bindingState）。
     * 字段与前端 CaseSelectionSpec 对齐；bindingState 走 BindingStateFilter 四态白名单。
     */
    public record BindingSelectionFilter(String keyword, String domain, String priority,
                                         String reviewStatus, String safetyLevel,
                                         String bindingState, List<Long> excludeIds) {
    }

    /** 批量加入通道；既有测试用 5 参构造器直接 new，未注入时报可读错误。 */
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final TestSetRepository sets;
    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final AuditService audit;
    private final AssetVersionService assetVersions;

    public TestSetService(TestSetRepository sets, TestCaseRepository cases,
                          AccessControlService access, AuditService audit,
                          AssetVersionService assetVersions) {
        this.sets = sets;
        this.cases = cases;
        this.access = access;
        this.audit = audit;
        this.assetVersions = assetVersions;
    }

    public PageResult<TestSet> page(long projectId, String keyword, int page, int size) {
        return page(projectId, keyword, null, null, page, size);
    }

    /** v5.8.0 FR-v5.8-03：列级筛选（集合编号 / 名称）在服务端生效，不再只筛当前页。 */
    public PageResult<TestSet> page(long projectId, String keyword, String setCode, String name,
                                    int page, int size) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return sets.page(projectId, keyword, setCode, name, page, size);
    }

    public TestSet get(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return sets.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例集不存在"));
    }

    public List<TestCase> items(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        get(projectId, id);
        return sets.items(projectId, id);
    }

    /** 三态白名单收口（SCEN-02）：非法 kind 明确报 INVALID_ARGUMENT。 */
    public void validateKind(String kind) {
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT(40001)：非法用例集三态 kind=" + kind
                            + "；允许 case_set / scenario / execution_selection");
        }
    }

    /** 保留原 4 参创建，默认三态为 case_set、无来源设计。 */
    public TestSet create(long projectId, String setCode, String name, String description) {
        return create(projectId, setCode, name, description, KIND_CASE_SET, null);
    }

    /** 三态创建：kind 走白名单，design_id 指向来源设计，SQL 显式写入两列。 */
    public TestSet create(long projectId, String setCode, String name, String description,
                          String kind, Long designId) {
        validateKind(kind);
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.CREATE);
        // v5.23.0 收口：名称必填在这里就挡住。原先只有 setCode 有兜底（W74-05 留空自动生成
        // SET-NNN），name 直接落库——空体请求会一路走到 SQL 报 NOT NULL，对外表现为 500。
        // 平台级 createPlatform 一直有这段校验，项目级漏了；两边口径必须一致。
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集名称不能为空");
        }
        requireLength(name, 128, "名称");
        requireLength(description, 512, "描述");
        if (setCode == null || setCode.isBlank()) {
            // v5.24.0 / W74-05：留空自动生成 SET-NNN（找第一个未占用的序号）。
            // 用 codeExists 判重而非新增仓储方法；并发极端情况由 DB 唯一索引兜底。
            int seq = 1;
            String generated;
            do {
                generated = String.format("SET-%03d", seq++);
            } while (sets.codeExists(projectId, generated, null) && seq < 10000);
            setCode = generated;
        }
        if (sets.codeExists(projectId, setCode, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用例集编号已存在：" + setCode);
        }
        long id = sets.insertWithKind(new TestSet(null, projectId, setCode.trim(), name,
                description, TenantContext.requireUserId(), "draft", 0),
                TenantContext.userId(), kind, designId);
        audit.record("set.create", "test_set", String.valueOf(id), "code=" + setCode);
        return get(projectId, id);
    }

    public TestSet update(long projectId, long id, String name, String description) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, id);
        sets.update(projectId, id, name, description, TenantContext.userId());
        audit.record("set.update", "test_set", String.valueOf(id), "name=" + name);
        return get(projectId, id);
    }

    @Audited(action = "set:delete", resource = "test_set")
    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.DELETE);
        TestSet existing = get(projectId, id);
        sets.delete(projectId, id);
        audit.recordChange("set:delete", "test_set", String.valueOf(id),
                "code=" + existing.setCode() + "; name=" + existing.name(), null);
    }

    @Transactional
    /**
     * v5.24.0 / W74-01：项目级批量加入。复用 {@link #addItem} 的逐条校验
     * （项目绑定、权限、用例存在），重复成员跳过而非报错，返回 added/skipped。
     */
    public com.qa.autotest.application.caseasset.TestSetService.AddItemsResult addItems(
            long projectId, long setId, java.util.List<Long> caseIds) {
        int added = 0;
        int skipped = 0;
        java.util.Set<Long> existing = new java.util.HashSet<>(
                sets.items(projectId, setId).stream().map(r -> r.id()).toList());
        if (caseIds != null) {
            for (Long caseId : caseIds) {
                if (caseId == null || existing.contains(caseId)) { skipped++; continue; }
                try {
                    addItem(projectId, setId, caseId);
                    existing.add(caseId);
                    added++;
                } catch (com.qa.autotest.common.BusinessException ex) {
                    // 非法 caseId 不整体回滚（与 category-filter 口径一致）：能加的加，加不了的报 skipped
                    skipped++;
                }
            }
        }
        return new AddItemsResult(added, skipped);
    }

    public void addItem(long projectId, long setId, long caseId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        cases.find(projectId, caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用例不存在"));
        int seq = sets.items(projectId, setId).size() + 1;
        sets.addItem(projectId, setId, caseId, seq, TenantContext.userId());
        // PUBX-01：内容变了才 +1 版本；钩子内部自己比 hash，重复调用无副作用
        assetVersions.onChanged(projectId, setId);
        audit.record("set.add-item", "test_set_item", setId + ":" + caseId, "seq=" + seq);
    }

    public void removeItem(long projectId, long setId, long caseId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        sets.removeItem(projectId, setId, caseId);
        assetVersions.onChanged(projectId, setId);
        audit.record("set.remove-item", "test_set_item", setId + ":" + caseId, "");
    }

    /**
     * W62-05：批量移出成员。删完把剩余成员整体重排，保证 {@code seq_no} 从 1 连续
     * （单条 removeItem 不会重排，连续删 N 条会留下空洞）。
     *
     * <p>只认「确实属于本集合」的 id：传一个不在集合里的 id 不算移出，
     * 也不会让它出现在 reorder 列表里。版本只涨一次，审计只记一条。
     */
    @Transactional
    public int removeItems(long projectId, long setId, List<Long> caseIds) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "批量移出列表不能为空");
        }
        List<Long> ids = caseIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "批量移出列表不能为空");
        }
        List<TestCase> before = sets.items(projectId, setId);
        Set<Long> target = new HashSet<>(ids);
        List<Long> remaining = before.stream().map(TestCase::id)
                .filter(id -> !target.contains(id)).toList();
        int removed = before.size() - remaining.size();
        if (removed == 0) {
            return 0;
        }
        for (Long caseId : ids) {
            sets.removeItem(projectId, setId, caseId);
        }
        if (!remaining.isEmpty()) {
            sets.reorder(projectId, setId, remaining, TenantContext.userId());
        }
        assetVersions.onChanged(projectId, setId);
        audit.record("set.batch-remove-items", "test_set_item", String.valueOf(setId),
                "removed=" + removed);
        return removed;
    }

    public void reorder(long projectId, long setId, List<Long> caseIds) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "排序列表不能为空");
        }
        sets.reorder(projectId, setId, caseIds, TenantContext.userId());
        // 顺序算内容：改顺序必须涨版本，否则引用方永远收不到「平台库换序了」的提示
        assetVersions.onChanged(projectId, setId);
        audit.record("set.reorder", "test_set", String.valueOf(setId), "size=" + caseIds.size());
    }

    /**
     * FR-49-28：全选某一级 / 全选某二级 / 跨表交集三种筛选，批量加入 test_set_item。
     * 每条写 item_source='category_filter'；新增条数 = 筛选结果条数。
     * 筛选条件与 GlobalCaseService.exportCategoryCsv 共用 categoryWhere，口径一致。
     */
    @Transactional
    public CategoryFilterResult addByCategoryFilter(long projectId, long setId,
                                                    GlobalCaseService.CategoryFilter filter) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        if (jdbc == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "按分类批量加入未装配数据源");
        }
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String where = GlobalCaseService.categoryWhere(filter, args);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM test_case WHERE project_id = ?" + where + " ORDER BY id",
                Long.class, args.toArray());
        if (ids == null) {
            ids = List.of();
        }
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) FROM test_set_item WHERE test_set_id = ?",
                Integer.class, setId);
        int seq = maxSeq == null ? 0 : maxSeq;
        Long operator = TenantContext.userId();
        int added = 0;
        for (Long caseId : ids) {
            seq++;
            jdbc.update("""
                    INSERT INTO test_set_item (test_set_id, test_case_id, seq_no, item_source,
                                               created_by, updated_by)
                    VALUES (?, ?, ?, 'category_filter', ?, ?)
                    """, setId, caseId, seq, operator, operator);
            added++;
        }
        if (added > 0 && assetVersions != null) {
            assetVersions.onChanged(projectId, setId);
        }
        if (audit != null) {
            audit.record("set.add-by-category", "test_set_item", String.valueOf(setId),
                    "matched=" + ids.size() + " added=" + added + " item_source=category_filter");
        }
        return new CategoryFilterResult(ids.size(), added, "category_filter");
    }

    /**
     * FR-READY-05：按「用例筛选条件（含脚本状态）」批量加入用例集。
     *
     * <p>分页前用同一 WHERE 真值选中（与用例库列表同源），seq 从 MAX+1 连续续排；
     * 逐条 INSERT 前再按 (set, case) 去重，重复调用幂等。item_source='binding_filter'。
     */
    @Transactional
    public CategoryFilterResult addByBindingFilter(long projectId, long setId,
                                                   BindingSelectionFilter filter) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        get(projectId, setId);
        if (jdbc == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "按筛选批量加入未装配数据源");
        }
        BindingSelectionFilter f = filter == null
                ? new BindingSelectionFilter(null, null, null, null, null, null, List.of())
                : filter;
        if (!BindingStateFilter.isValid(f.bindingState())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "非法 bindingState: " + f.bindingState());
        }
        StringBuilder where = new StringBuilder(" WHERE tc.project_id = ? AND tc.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (f.keyword() != null && !f.keyword().isBlank()) {
            where.append(" AND (tc.case_code LIKE ? OR tc.title LIKE ?)");
            String like = "%" + f.keyword().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (f.domain() != null && !f.domain().isBlank()) {
            where.append(" AND tc.domain_tag = ?");
            args.add(f.domain());
        }
        if (f.priority() != null && !f.priority().isBlank()) {
            where.append(" AND tc.priority = ?");
            args.add(f.priority());
        }
        if (f.reviewStatus() != null && !f.reviewStatus().isBlank()) {
            where.append(" AND tc.review_status = ?");
            args.add(f.reviewStatus());
        }
        if (f.safetyLevel() != null && !f.safetyLevel().isBlank()) {
            where.append(" AND tc.safety_level = ?");
            args.add(f.safetyLevel().trim().toUpperCase(java.util.Locale.ROOT));
        }
        List<Long> exclude = f.excludeIds() == null ? List.of() : f.excludeIds();
        if (!exclude.isEmpty()) {
            String marks = String.join(",", java.util.Collections.nCopies(exclude.size(), "?"));
            where.append(" AND tc.id NOT IN (").append(marks).append(")");
            args.addAll(exclude);
        }
        String kernel = kernelOf(projectId);
        BindingStateFilter.apply(where, args, projectId, kernel, f.bindingState(), "tc.id");
        List<Long> ids = jdbc.queryForList(
                "SELECT tc.id FROM test_case tc" + where + " ORDER BY tc.id",
                Long.class, args.toArray());
        if (ids == null) {
            ids = List.of();
        }

        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) FROM test_set_item WHERE test_set_id = ?",
                Integer.class, setId);
        int seq = maxSeq == null ? 0 : maxSeq;
        Long operator = TenantContext.userId();
        int added = 0;
        for (Long caseId : ids) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_set_item WHERE test_set_id = ? AND test_case_id = ?",
                    Integer.class, setId, caseId);
            if (exists != null && exists > 0) {
                continue; // 幂等去重
            }
            seq++;
            jdbc.update("""
                    INSERT INTO test_set_item (test_set_id, test_case_id, seq_no, item_source,
                                               created_by, updated_by)
                    VALUES (?, ?, ?, 'binding_filter', ?, ?)
                    """, setId, caseId, seq, operator, operator);
            added++;
        }
        if (added > 0 && assetVersions != null) {
            assetVersions.onChanged(projectId, setId);
        }
        if (audit != null) {
            audit.record("set.add-by-binding-filter", "test_set_item", String.valueOf(setId),
                    "matched=" + ids.size() + " added=" + added + " bindingState=" + f.bindingState());
        }
        return new CategoryFilterResult(ids.size(), added, "binding_filter");
    }

    /** FR-READY-11：项目内核，查不到按 robot（与 CaseScriptLookup.kernelOf 同源）。 */
    private String kernelOf(long projectId) {
        List<String> rows = jdbc.queryForList(
                "SELECT test_kernel FROM project WHERE id = ?", String.class, projectId);
        String kernel = rows.isEmpty() ? null : rows.get(0);
        return kernel == null || kernel.isBlank() ? "robot" : kernel.trim();
    }

    // ---------------------------------------------------------------- N9 平台用例集库

    /** 平台库一行：集信息 + 版本读数 + 引用方（只读，不含任何写字段）。 */
    public record PlatformSetRow(long id, String setCode, String name, String description,
                                 int itemCount, int assetVersion, String contentHash,
                                 boolean hasNewVersion, int referenceCount,
                                 List<AssetVersionService.ReferenceView> references) {
    }

    /**
     * 平台用例集列表（W57-06）：跨项目可见视图，鉴权放宽到 case:read（D-57-04）。
     * 不 bindProject —— 平台集不属于任何项目，绑定会把「跨项目」变成「越权」。
     * 保留 3 参旧入口（等价 source=all），避免既有调用方与单测跟着改签名。
     */
    public PageResult<PlatformSetRow> platformPage(String keyword, int page, int size) {
        return platformPage(keyword, page, size, "all");
    }

    /** source ∈ sample / manual / all；播种由平台列表入口同步 try-catch 触发。 */
    public PageResult<PlatformSetRow> platformPage(String keyword, int page, int size,
                                                   String source) {
        requirePlatformRead();
        PageResult<TestSet> pageSets = sets.platformPage(keyword, page, size, source);
        List<PlatformSetRow> rows = new ArrayList<>();
        for (TestSet set : pageSets.items()) {
            // 平台集若尚未登记 asset_version 行（历史集 / 样例集），platformReferences 会抛 404；
            // 单行容错，不能让一条脏数据把整页列表拖成 500。
            AssetVersionService.VersionView version;
            List<AssetVersionService.ReferenceView> refs;
            try {
                version = assetVersions.platformVersion(set.id());
                refs = assetVersions.platformReferences(set.id());
            } catch (RuntimeException ex) {
                version = new AssetVersionService.VersionView(0, null, "SYNCED", null, null,
                        null, false, null, null);
                refs = List.of();
            }
            rows.add(new PlatformSetRow(set.id(), set.setCode(), set.name(), set.description(),
                    set.itemCount(), version.assetVersion(), version.contentHash(),
                    version.hasNewVersion(), refs.size(), refs));
        }
        return new PageResult<>(rows, pageSets.total(), pageSets.page(), pageSets.size());
    }

    /** 平台用例集成员（case:read）。空集是合法状态，但集合行必须存在。 */
    public List<TestCase> platformItems(long setId) {
        requirePlatformRead();
        requirePlatformSet(setId);
        return sets.platformItems(setId);
    }

    // ---------------------------------------------------------------- W57-06 平台用例集可写

    /** 批量加入结果：added=新插入，skipped=重复成员 / 用例不存在。 */
    public record AddItemsResult(int added, int skipped) {
    }

    public TestSet createPlatform(String setCode, String name, String description) {
        requirePlatformWrite();
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集名称不能为空");
        }
        requireLength(name, 128, "名称");
        requireLength(description, 512, "描述");
        String code = (setCode == null || setCode.isBlank())
                ? "PS-" + UUID.randomUUID().toString().substring(0, 8)
                : setCode.trim();
        requireLength(code, 64, "编号");
        // 唯一键是生成列 scope_project_id，服务层仍须自己判 set_code 重复并返回 409。
        if (sets.platformCodeExists(code, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用例集编号已存在：" + code);
        }
        long id = sets.insertPlatform(code, name, description, null, TenantContext.userId());
        audit.record("platform.set.create", "test_set", String.valueOf(id), "code=" + code);
        return requirePlatformSet(id);
    }

    public TestSet updatePlatform(long id, String name, String description) {
        requirePlatformWrite();
        requirePlatformSet(id);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集名称不能为空");
        }
        requireLength(name, 128, "名称");
        requireLength(description, 512, "描述");
        sets.updatePlatform(id, name, description, TenantContext.userId());
        audit.record("platform.set.update", "test_set", String.valueOf(id), "name=" + name);
        return requirePlatformSet(id);
    }

    /**
     * 列宽前置校验。
     *
     * <p>不校验的后果是 MySQL 抛 `Data too long`，一路冒到全局异常处理变成 **500 服务内部错误**，
     * 用户只看到「服务内部错误」，不知道是名字太长。这里按 DDL 的 varchar 宽度挡在入口，
     * 返回 400 并点名是哪个字段、上限多少（对应 TC-v5.7-22）。
     */
    private static void requireLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    label + "超长：最多 " + max + " 个字符，当前 " + value.length());
        }
    }

    public void deletePlatform(long id) {
        requirePlatformWrite();
        TestSet existing = requirePlatformSet(id);
        sets.deletePlatform(id);
        audit.recordChange("platform.set.delete", "test_set", String.valueOf(id),
                "code=" + existing.setCode() + "; name=" + existing.name(), null);
    }

    @Transactional
    public AddItemsResult addPlatformItems(long setId, List<Long> caseIds) {
        requirePlatformWrite();
        requirePlatformSet(setId);
        return addItemsInternal(setId, caseIds, "add-items");
    }

    /** 批量加 / 按分类加共用：seq 递增、重复跳过、写后把样例集标记为「用户已接管」。 */
    private AddItemsResult addItemsInternal(long setId, List<Long> caseIds, String action) {
        Long operator = TenantContext.userId();
        Set<Long> seen = new HashSet<>(sets.platformItemIds(setId));
        int seq = sets.nextPlatformSeqNo(setId);
        int added = 0;
        int skipped = 0;
        if (caseIds != null) {
            for (Long caseId : caseIds) {
                if (caseId == null || !seen.add(caseId)) {
                    skipped++;
                    continue;
                }
                if (sets.addPlatformItemIfAbsent(setId, caseId, ++seq, operator) > 0) {
                    added++;
                } else {
                    seen.remove(caseId);
                    skipped++;
                }
            }
        }
        if (added > 0) {
            sets.markPlatformUserModified(setId);
            audit.record("platform.set." + action, "test_set_item", String.valueOf(setId),
                    "added=" + added + " skipped=" + skipped);
        }
        return new AddItemsResult(added, skipped);
    }

    public void removePlatformItem(long setId, long caseId) {
        requirePlatformWrite();
        requirePlatformSet(setId);
        sets.removePlatformItem(setId, caseId);
        sets.markPlatformUserModified(setId);
        audit.record("platform.set.remove-item", "test_set_item", setId + ":" + caseId, "");
    }

    @Transactional
    public void reorderPlatform(long setId, List<Long> caseIds) {
        requirePlatformWrite();
        requirePlatformSet(setId);
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "排序列表不能为空");
        }
        sets.reorderPlatform(setId, caseIds, TenantContext.userId());
        sets.markPlatformUserModified(setId);
        audit.record("platform.set.reorder", "test_set", String.valueOf(setId),
                "size=" + caseIds.size());
    }

    /**
     * W57-06：按一级 / 二级分类 id 批量加，照抄项目侧 addByCategoryFilter 的
     * matched / added / item_source='category_filter' 口径（筛选维度改用 id + COALESCE 双轨）。
     */
    @Transactional
    public CategoryFilterResult addPlatformItemsByCategory(long setId, Long categoryL1Id,
                                                           Long categoryL2Id) {
        requirePlatformWrite();
        requirePlatformSet(setId);
        if (categoryL1Id == null && categoryL2Id == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "categoryL1Id / categoryL2Id 至少指定一个");
        }
        List<Long> ids = sets.platformCategoryCaseIds(categoryL1Id, categoryL2Id);
        AddItemsResult result = addItemsInternal(setId, ids, "add-by-category");
        return new CategoryFilterResult(ids.size(), result.added(), "category_filter");
    }

    /** 平台集存在性校验（project_id IS NULL AND owner_scope='PLATFORM'）。 */
    private TestSet requirePlatformSet(long setId) {
        return sets.findPlatform(setId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "平台用例集不存在: " + setId));
    }

    // ---------------------------------------------------------------- 鉴权（W57-06 / D-57-04）

    /**
     * 平台读鉴权：放宽到 case:read。矩阵里 CASE:READ 四角色全有，
     * 用 require(CASE, READ) 表达「能读用例即可读平台库」。
     */
    private void requirePlatformRead() {
        access.require(PermissionResource.CASE, PermissionAction.READ);
    }

    /**
     * 平台写鉴权：放宽到 case:write。PermissionAction 没有 WRITE 动作，用 CASE:UPDATE
     * 作为写权限代理（ADMIN / PM / ENGINEER 有，VIEWER 无），等价「非只读角色可写」。
     */
    /**
     * 平台集写权限闸：全站唯一出口。
     * 复核 P1-2 起由 controller 的播种端点直接调用，因此从 private 提为 public——
     * 与其在 controller 里复制一遍 require 语句，不如让它只有一个实现。
     */
    public void requirePlatformWrite() {
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
    }

    /** 保留原管理员校验（N9 旧口径），供其它调用点使用；平台读写入口已不再调用。 */
    private void requirePlatformAdmin() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台用例集库只对管理员开放");
        }
    }
}
