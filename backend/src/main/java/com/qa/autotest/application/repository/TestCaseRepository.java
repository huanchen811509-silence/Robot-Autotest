package com.qa.autotest.application.repository;

import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import com.qa.autotest.domain.caseasset.ReviewSnapshot;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestCaseStep;
import com.qa.autotest.domain.common.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 用例台账仓储。 */
public interface TestCaseRepository {

    /** EDIM-01/02：导入行的扩展字段（一次 UPDATE 写入 test_case 新增列）。 */
    record ImportMetadata(String categoryL2, String sourcePriority, String caseClass,
                          String testCategory, String ed202Coverage, String sourceSheet) {
    }

    /** EDIM-03：导入步骤（stepNo/action/expectation；sourceRow 为该用例源表行号，整批步骤共用）。 */
    record ImportedStep(int stepNo, String action, String expectation) {
    }

    /**
     * 删除影响面（v5.11.0 / W61-15 / FR-v5.11-07）：三个数字**只用于删除前预览**，
     * 不参与删除判定（判定仍走 {@link #caseReferences}，口径不同不能混用）。
     */
    record ImpactCounts(long setRefs, long scriptBindings, long runResults) {
    }

    /** 默认零值：既有测试桩不关心影响面，不必为一个预览数字全部实现。 */
    default ImpactCounts impactOf(long projectId, long caseId) {
        return new ImpactCounts(0L, 0L, 0L);
    }

    PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                              String reviewStatus, String capability, String safetyLevel,
                              int page, int size);

    /** 新入口：未指定排序时统一按 id DESC（FR-3.2-02）。 */
    default PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                                      String reviewStatus, String capability, String safetyLevel,
                                      PageQuery paging) {
        return page(projectId, keyword, domain, priority, reviewStatus, capability, safetyLevel,
                paging.page(), paging.size());
    }

    /**
     * FR-READY-01：按脚本绑定状态在分页前过滤。
     *
     * <p>default 委托给旧方法（忽略 bindingState），这样既有测试桩无需改动；
     * 生产实现 {@code TestCaseRepositoryJdbc} 显式覆盖，做真实 EXISTS 过滤。
     *
     * @param bindingState {@link com.qa.autotest.application.caseasset.BindingStateFilter} 四态之一，null 不过滤
     */
    default PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                                      String reviewStatus, String capability, String safetyLevel,
                                      PageQuery paging, String bindingState) {
        return page(projectId, keyword, domain, priority, reviewStatus, capability, safetyLevel, paging);
    }

    Optional<TestCase> find(long projectId, long id);

    Optional<TestCase> findByCode(long projectId, String caseCode);

    long insert(TestCase testCase, Long operatorId);

    void update(TestCase testCase, Long operatorId);

    /**
     * 删除（v5.21.0 / FR-v5.21-03：**软删除**，置 `deleted_at`，不物理删）。
     *
     * @param operatorId 操作人，落 `deleted_by`；可为 null（系统任务）
     */
    void delete(long projectId, long id, Long operatorId);

    /** 同编号的已软删行 id；用于「删了又想建回来」时复活原行（唯一键仍被它占着）。 */
    Optional<Long> findSoftDeletedId(long projectId, String caseCode);

    /** 复活一行软删用例（清 `deleted_at`/`deleted_by`）。 */
    void restore(long projectId, long id, Long operatorId);

    List<TestCaseStep> steps(long testCaseId);

    /** 全量替换步骤（导入与编辑共用）。 */
    void replaceSteps(long testCaseId, List<TestCaseStep> steps, Long operatorId);

    /** EDIM-01/02：commit 插入用例后，写 ED202 扩展字段（六列全空时不发 SQL）。 */
    /**
     * 导入补录的元数据（v5.11.0 / W61-16 / FR-v5.11-09）。
     *
     * <p>带 {@code projectId} 是契约修补：只按用例 id 更新，等于把「跨项目改别人数据」
     * 的安全性押在「调用点恰好刚插入同事务 id」这一实现细节上。现在 SQL 自带项目条件。
     */
    void writeImportMetadata(long projectId, long testCaseId, ImportMetadata meta,
                             Long operatorId);

    /** EDIM-03：commit 插入用例后，把「测试步骤」拆出的步骤写进 test_case_step。 */
    /**
     * 导入的测试步骤（v5.11.0 / W61-16）。
     *
     * <p>{@code test_case_step} 本身没有 {@code project_id} 列，所以这里不是「加 project_id 条件」，
     * 而是用 {@code WHERE EXISTS(父用例属于本项目)} 把写入钉在父用例的租户上 —— 效果相同，
     * 但符合实际表结构（PRD 里写成「两处 UPDATE 加条件」是笔误，已在 05-TEST-REPORT §8 登记）。
     */
    void insertImportedSteps(long projectId, long testCaseId, List<ImportedStep> steps,
                             int sourceRow, Long operatorId);

    void updateReview(long projectId, long id, String reviewStatus, String comment,
                      Long reviewedBy, Instant reviewedAt);

    /** 批量改优先级（UX-02，FR-v0.2-37）。 */
    void updatePriority(long projectId, long id, String priority, Long operatorId);

    /** 批量改技术域标签（UX-02，FR-v0.2-37）。 */
    void updateDomainTag(long projectId, long id, String domainTag, Long operatorId);

    /** 批量改执行安全级（G2-3 / ADR-0013）：人工补判 LX 的唯一入口。 */
    void updateSafetyLevel(long projectId, long id, String safetyLevel, Long operatorId);

    /**
     * 批量撤销（FR-3.1-04）：读取操作前的前像。
     *
     * <p>默认空实现保证既有测试的假仓储无需跟着改；真实实现见 JDBC 仓储。
     */
    default Optional<ReviewSnapshot> reviewSnapshot(long projectId, long id) {
        return Optional.empty();
    }

    boolean codeExists(long projectId, String caseCode, Long excludeId);

    long countByProject(long projectId);

    /**
     * 项目内全部用例编号（v5.1 · FR-v5.1-04 绑定同步用）。
     *
     * <p>默认空实现保证既有假仓储不必跟着改；真实实现见 JDBC 仓储。
     * 绑定同步只关心编号集合，不拉整行——469 条用例整行拉取会给同步带来无意义开销。
     */
    default List<String> listCaseCodes(long projectId) {
        return List.of();
    }

    /**
     * 项目内 id → 编号（v5.1 · FR-v5.1-08 结果归一回填库编号用）。
     *
     * <p>一次查询拿全表映射，避免「每条结果查一次用例」的 N+1。
     * 默认空实现保证既有假仓储无需改动。
     */
    default java.util.Map<Long, String> idToCode(long projectId) {
        return java.util.Map.of();
    }

    /**
     * 项目内 编号 → 优先级（v5.1.2 · FR-v5.1-05 覆盖率双口径用）。
     *
     * <p>「待评审」是**优先级**取值（不是评审状态），178 条的口径来自优先级统计；
     * 覆盖率双口径（13.0% / 10.3%）就靠这张表区分分母。
     * 默认空实现保证既有假仓储无需改动。
     */
    default java.util.Map<String, String> casePriorities(long projectId) {
        return java.util.Map.of();
    }

    /**
     * 用例被引用的去向与条数（v5.9.0 / W59-20，「删不掉要说清被谁占着」）。
     *
     * <p>只返回**会真正挡住删除**的引用：当前是 {@code case_script_binding}（脚本绑定）。
     * key 是给人看的中文简称，值是该去向的条数。默认空实现保证既有假仓储无需改动；
     * 空 Map 与「没有引用」同义，删除照旧放行（保持旧行为，不因新增检查误伤）。
     */
    default java.util.Map<String, Long> caseReferences(long projectId, long caseId) {
        return java.util.Map.of();
    }

    List<CapabilityStat> capabilities(long projectId);
}
