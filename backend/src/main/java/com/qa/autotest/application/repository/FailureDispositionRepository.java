package com.qa.autotest.application.repository;

import com.qa.autotest.domain.execution.FailureDisposition;
import java.util.List;
import java.util.Optional;

/** 失败处置仓储（FR-v5.3-06 / V11_52）。只增不删：reopen 改状态，不删历史。 */
public interface FailureDispositionRepository {

    Optional<FailureDisposition> find(long projectId, String fingerprint, long caseId);

    Optional<FailureDisposition> findById(long projectId, long id);

    long insert(FailureDisposition row, Long operatorId);

    void update(FailureDisposition row, Long operatorId);

    /** 项目内处置列表；{@code status} 为空表示全部。 */
    List<FailureDisposition> list(long projectId, String status);

    /** 某用例的全部处置（页面在用例详情里展示）。 */
    List<FailureDisposition> listByCase(long projectId, long caseId);

    /** 已知问题豁免候选：{@code decision=known_issue} 且已定论。 */
    List<FailureDisposition> listExempted(long projectId);

    /** 按状态计数（台账用）。 */
    long countByStatus(long projectId, String status);
}
