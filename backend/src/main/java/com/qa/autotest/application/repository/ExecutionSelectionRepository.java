package com.qa.autotest.application.repository;

import com.qa.autotest.domain.caseasset.FrozenSelection;
import java.util.Optional;

/** 执行选择冻结快照仓储（SCEN-02）：只提供写入与只读查询，不暴露任何改写入口。 */
public interface ExecutionSelectionRepository {

    /** 只发 INSERT，返回自增主键。 */
    long insert(FrozenSelection selection);

    /** 只读查询。 */
    Optional<FrozenSelection> find(long projectId, long id);
}
