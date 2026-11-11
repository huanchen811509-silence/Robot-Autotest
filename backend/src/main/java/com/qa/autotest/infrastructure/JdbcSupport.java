package com.qa.autotest.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 基础设施层的小工具：时间转换与单值取值。 */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 取第一行，**允许这一行的值为 null**。
     *
     * <p>曾经写成 {@code Optional.of(rows.get(0))}：只要被查的那一列是可空列且恰好为 NULL
     * （v4.12.0 的 {@code credential.connect_options} 就是这种列），就会在
     * {@code Optional.of} 里抛 NPE —— 而且堆栈只指向 `Optional.of`，看不出是哪张表哪一列。
     * 单值查询的语义本来就是「可能没有值」，null 属于「没有值」而不是程序错误。
     */
    static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
