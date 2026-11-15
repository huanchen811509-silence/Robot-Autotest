package com.qa.autotest.application.repository;

import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用例 ↔ 脚本绑定仓储（V11_47 / FR-v5.1-04）。
 *
 * <p>写入方只有同步服务（{@code PytestBindingSyncService}）与人工覆盖接口；
 * 其它路径一律只读（风险 E-2：绑定表不得成为第二个真源）。
 */
public interface CaseScriptBindingRepository {

    Optional<CaseScriptBinding> findByCase(long projectId, long caseId, String kernel);

    /** 按基础 nodeid 精确匹配（参数化实例先去后缀，见 {@code PytestResultNormalizer}）。 */
    Optional<CaseScriptBinding> findByNodeId(long projectId, String kernel, String nodeId);

    List<CaseScriptBinding> listByProject(long projectId, String kernel);

    /** 按脚本路径反查（重命名跟随：路径变了但 sha256 未变时用来认领旧绑定）。 */
    Optional<CaseScriptBinding> findByPath(long projectId, String kernel, String scriptPath);

    /** 按内容哈希反查（H4 重命名跟随）。 */
    List<CaseScriptBinding> findBySha(long projectId, String kernel, String scriptSha256);

    /** 全量同步前清空该内核的绑定；返回删除行数。 */
    int deleteByProjectAndKernel(long projectId, String kernel);

    long insert(CaseScriptBinding binding, Long operatorId);

    void update(CaseScriptBinding binding, Long operatorId);

    void deleteById(long projectId, long id);

    long countByProject(long projectId, String kernel);

    /**
     * 批量取一页用例的绑定（v5.11.0 / W61-11 / FR-v5.11-05）。
     *
     * <p>为什么给 default 空实现：本方法只服务「列表行显示脚本」这一个读场景，
     * 既有测试桩（5 处）不关心脚本，不该为了一个展示字段全部改一遍。
     * 生产只有一个实现（{@code CaseScriptBindingRepositoryJdbc}），已显式覆盖。
     *
     * @param caseIds 为空时返回空列表，调用方不必特判
     */
    default List<CaseScriptBinding> listByCaseIds(long projectId, Collection<Long> caseIds) {
        return List.of();
    }
}
