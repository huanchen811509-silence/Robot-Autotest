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

    /**
     * 该用例集**最近一次执行**的 runId（FR-v3.10-03）。
     *
     * <p>排序键 `COALESCE(finished_at, started_at, enqueued_at)` 与前端 `RunPicker` 一致：
     * 运行中的 run 还没有结束时间，但用户心里的「最近一次」是它，不是上一条跑完的。
     * 用例集从未执行过时返回 {@link Optional#empty()}，由调用方给可读提示。
     */
    Optional<String> latestRunId(long projectId, long testSetId);

    /**
     * 指定 run 是否属于该用例集（FR-v3.10-03 判据②）。
     *
     * <p>用于「用户指定一次执行」这条路径：不校验就会出现「按 A 集建报告、实际取到 B 集的 run」，
     * 报告标题写着 A 集、内容是 B 集，事后无法从数据上发现。
     */
    boolean containsRun(long projectId, long testSetId, String runId);
}
