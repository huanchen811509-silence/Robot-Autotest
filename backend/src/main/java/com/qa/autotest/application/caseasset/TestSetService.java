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
import java.util.List;
import java.util.Set;
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
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return sets.page(projectId, keyword, page, size);
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
        if (setCode == null || setCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集编号不能为空");
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

    // ---------------------------------------------------------------- N9 平台用例集库

    /** 平台库一行：集信息 + 版本读数 + 引用方（只读，不含任何写字段）。 */
    public record PlatformSetRow(long id, String setCode, String name, String description,
                                 int itemCount, int assetVersion, String contentHash,
                                 boolean hasNewVersion, int referenceCount,
                                 List<AssetVersionService.ReferenceView> references) {
    }

    /**
     * 平台用例集列表（N9）：跨项目可见的只读视图，仅管理员可看。
     * 不 bindProject —— 平台集不属于任何项目，绑定会把「跨项目」变成「越权」。
     */
    public PageResult<PlatformSetRow> platformPage(String keyword, int page, int size) {
        requirePlatformAdmin();
        PageResult<TestSet> pageSets = sets.platformPage(keyword, page, size);
        List<PlatformSetRow> rows = new ArrayList<>();
        for (TestSet set : pageSets.items()) {
            AssetVersionService.VersionView version = assetVersions.platformVersion(set.id());
            List<AssetVersionService.ReferenceView> refs =
                    assetVersions.platformReferences(set.id());
            rows.add(new PlatformSetRow(set.id(), set.setCode(), set.name(), set.description(),
                    set.itemCount(), version.assetVersion(), version.contentHash(),
                    version.hasNewVersion(), refs.size(), refs));
        }
        return new PageResult<>(rows, pageSets.total(), pageSets.page(), pageSets.size());
    }

    /** 平台用例集成员（只读）。空集是合法状态，但集合行必须存在。 */
    public List<TestCase> platformItems(long setId) {
        requirePlatformAdmin();
        sets.findPlatform(setId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "平台用例集不存在: " + setId));
        return sets.platformItems(setId);
    }

    private void requirePlatformAdmin() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台用例集库只对管理员开放");
        }
    }
}
