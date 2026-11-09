package com.qa.archfixture.violation.repojdbc.application.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/** 违规 fixture：仓储接口里出现 JdbcTemplate（R7 必须拦住）。 */
public interface LeakyOrderRepository {

    JdbcTemplate template();
}
