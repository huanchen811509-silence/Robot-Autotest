package com.qa.autotest.application.execution;

/**
 * 单条冻结判据（W57-22）：指标、比较操作、阈值、实测值、是否达标。
 *
 * <p>{@code actual} 为 null 表示该指标没采到，判据整体应判 MISSING 而不是默认 PASS。
 */
public record CaseCriteria(
        String metric,
        String op,
        String threshold,
        String actual,
        Boolean passed) {
}
