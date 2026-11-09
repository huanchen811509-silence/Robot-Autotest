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

    Optional<TestCase> find(long projectId, long id);

    Optional<TestCase> findByCode(long projectId, String caseCode);

    long insert(TestCase testCase, Long operatorId);

    void update(TestCase testCase, Long operatorId);

    void delete(long projectId, long id);

    List<TestCaseStep> steps(long testCaseId);

    /** 全量替换步骤（导入与编辑共用）。 */
    void replaceSteps(long testCaseId, List<TestCaseStep> steps, Long operatorId);

    /** EDIM-01/02：commit 插入用例后，写 ED202 扩展字段（六列全空时不发 SQL）。 */
    void writeImportMetadata(long testCaseId, ImportMetadata meta, Long operatorId);

    /** EDIM-03：commit 插入用例后，把「测试步骤」拆出的步骤写进 test_case_step。 */
    void insertImportedSteps(long testCaseId, List<ImportedStep> steps, int sourceRow,
                             Long operatorId);

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

    List<CapabilityStat> capabilities(long projectId);
}
