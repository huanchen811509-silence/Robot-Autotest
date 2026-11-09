package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.List;

/**
 * 全局搜索只读仓储（FR-3.1-02）。
 *
 * <p>每种实体一条 SQL，不用 UNION 拼大查询：各表可见列与排序口径不同，
 * 拆开后 LIMIT 与 COUNT 都能走索引，也便于单测按类型打桩。
 */
public interface SearchRepository {

    /** 单条命中；path 是前端可直接跳转的相对路径。 */
    record Hit(String type, long id, String title, String subtitle, long projectId,
               Instant updatedAt, String path, String badge) {
    }

    /**
     * @param limit 单项目单类型最多取多少条；{@code <= 0} 时只做 COUNT 不取行
     * @return total 为该类型在项目内的全部匹配数（不受 limit 影响）
     */
    record TypeResult(List<Hit> hits, long total) {
    }

    TypeResult search(long projectId, String query, String type, int limit);
}
