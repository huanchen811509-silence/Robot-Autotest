package com.qa.autotest.application.repository;

import com.qa.autotest.application.view.SavedView;

import java.util.List;
import java.util.Optional;

/** 保存视图仓储（FR-3.1-03）；所有 SQL 以 project_id + user_id 双重隔离。 */
public interface SavedViewRepository {

    List<SavedView> list(long projectId, long userId, String scope);

    Optional<SavedView> find(long projectId, long id, long userId);

    long insert(SavedView view);

    void update(SavedView view);

    void delete(long projectId, long id, long userId);

    /** 同名同作用域冲突预检（不含自身 id）。 */
    boolean nameExists(long projectId, long userId, String scope, String name, Long excludeId);

    /** 把同用户同作用域的其他视图默认位清掉。 */
    void clearDefault(long projectId, long userId, String scope, long keepId);
}
