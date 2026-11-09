package com.qa.autotest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LIKE 参数转义：用户输入的 % / _ 必须按字面匹配，不能变成通配符。 */
class SearchRepositoryJdbcTest {

    @Test
    @DisplayName("% 与 _ 转义，且转义符自身被双写")
    void 通配符转义() {
        assertThat(SearchRepositoryJdbc.like("100%_ok")).isEqualTo("%100!%!_ok%");
        assertThat(SearchRepositoryJdbc.like("a!b")).isEqualTo("%a!!b%");
    }
}
