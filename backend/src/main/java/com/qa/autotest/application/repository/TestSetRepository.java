package com.qa.autotest.application.repository;

import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import java.util.Optional;

/** 用例集仓储。 */
public interface TestSetRepository {

    PageResult<TestSet> page(long projectId, String keyword, int page, int size);

    /** 平台用例集（test_set.project_id IS NULL）分页：平台库只读视图用。 */
    PageResult<TestSet> platformPage(String keyword, int page, int size);

    /** 平台用例集按 id 查（project_id IS NULL）。 */
    Optional<TestSet> findPlatform(long id);

    Optional<TestSet> find(long projectId, long id);

    long insert(TestSet testSet, Long operatorId);

    /** 三态创建（SCEN-02）：显式写入 kind / design_id，其余 NOT NULL 列照旧。 */
    long insertWithKind(TestSet testSet, Long operatorId, String kind, Long designId);

    void update(long projectId, long id, String name, String description, Long operatorId);

    void delete(long projectId, long id);

    List<TestCase> items(long projectId, long testSetId);

    /** 平台用例集成员（只读视图用；平台集的 project_id 为 NULL）。 */
    List<TestCase> platformItems(long testSetId);

    void addItem(long projectId, long testSetId, long testCaseId, int seqNo, Long operatorId);

    void removeItem(long projectId, long testSetId, long testCaseId);

    void reorder(long projectId, long testSetId, List<Long> caseIds, Long operatorId);

    boolean codeExists(long projectId, String setCode, Long excludeId);
}
