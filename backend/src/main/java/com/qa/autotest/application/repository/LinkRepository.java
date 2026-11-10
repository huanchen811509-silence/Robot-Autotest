package com.qa.autotest.application.repository;

import com.qa.autotest.domain.asset.Link;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import java.util.Optional;

/** 链路仓储。 */
public interface LinkRepository {

    PageResult<Link> page(long projectId, String keyword, int page, int size);

    /** 本项目可见（私有 + 已引用平台）的全部链路（不分页、不截断），v4.3.0 派生用。 */
    List<Link> listAll(long projectId);

    Optional<Link> find(long projectId, long id);

    long insert(Link link, Long operatorId);

    void update(Link link, Long operatorId);

    void delete(long projectId, long id);
}
