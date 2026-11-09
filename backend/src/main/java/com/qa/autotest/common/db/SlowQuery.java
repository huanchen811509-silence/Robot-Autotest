package com.qa.autotest.common.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仓储层慢查询埋点（FR-3.2-02 / FR-3.2-07）。
 *
 * <p>阈值与 {@code tools/perf-budget.json} 的 {@code api.slowQuery} 保持一致：
 * &gt;200ms 记 WARN，&gt;500ms 记 ERROR。日志只写 SQL **指纹**，不写参数值，避免把
 * 口令、标题、路径之类的业务数据带进日志。
 *
 * <p>刻意不做成 AOP：现有仓库都是显式 {@code jdbc.query}，在 4 个核心列表查询上
 * 用 {@link #measure(String, String, Supplier)} 包一层即可，改动可见、可回滚，也不用引入
 * spring-aop 依赖。
 */
public final class SlowQuery {

    public static final long WARN_MS = 200;
    public static final long ERROR_MS = 500;

    private static final Logger log = LoggerFactory.getLogger(SlowQuery.class);

    private static final java.util.concurrent.atomic.LongAdder MEASURED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder WARNED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder ERRORED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FAILED =
            new java.util.concurrent.atomic.LongAdder();

    private SlowQuery() {
    }

    /** 慢查询计数快照（供 /api/admin/observability/summary 使用）。 */
    public static Stats stats() {
        return new Stats(MEASURED.sum(), WARNED.sum(), ERRORED.sum(), FAILED.sum());
    }

    public record Stats(long measured, long warned, long errored, long failed) {
    }

    /**
     * 执行并计时。
     *
     * @param label 场景名（如 {@code case.page}），便于在日志里按列表聚合
     * @param sql   实际执行的 SQL（只取指纹，不落原文）
     * @param call  实际查询
     */
    public static <T> T measure(String label, String sql, Supplier<T> call) {
        long started = System.nanoTime();
        try {
            T result = call.get();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            record(label, sql, elapsedMs, rowCount(result));
            return result;
        } catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            FAILED.increment();
            log.warn("SQL_LABEL={} SQL_FP={} ELAPSED_MS={} RESULT=error EX={}",
                    label, fingerprint(sql), elapsedMs, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /** 仅供测试与日志分类使用。 */
    public static String levelFor(long elapsedMs) {
        if (elapsedMs > ERROR_MS) {
            return "ERROR";
        }
        if (elapsedMs > WARN_MS) {
            return "WARN";
        }
        return null;
    }

    static void record(String label, String sql, long elapsedMs, int rows) {
        MEASURED.increment();
        String level = levelFor(elapsedMs);
        if (level == null) {
            return;
        }
        if ("ERROR".equals(level)) {
            ERRORED.increment();
            log.error("SQL_LABEL={} SQL_FP={} ELAPSED_MS={} ROWS={} THRESHOLD_MS={}",
                    label, fingerprint(sql), elapsedMs, rows, ERROR_MS);
        } else {
            WARNED.increment();
            log.warn("SQL_LABEL={} SQL_FP={} ELAPSED_MS={} ROWS={} THRESHOLD_MS={}",
                    label, fingerprint(sql), elapsedMs, rows, WARN_MS);
        }
    }

    private static int rowCount(Object result) {
        if (result instanceof Collection<?> collection) {
            return collection.size();
        }
        if (result instanceof Map<?, ?> map) {
            return map.size();
        }
        return -1;
    }

    /**
     * SQL 指纹：小写 + 字面量替换成 ? + 压缩空白 + sha256 前 12 位。
     * 同一查询结构不同参数应得到同一指纹，便于按「哪类 SQL 慢」聚合。
     */
    public static String fingerprint(String sql) {
        if (sql == null || sql.isBlank()) {
            return "empty";
        }
        String normalized = sql.toLowerCase(Locale.ROOT)
                .replaceAll("'(?:''|[^'])*'", "?")
                .replaceAll("\\b\\d+\\b", "?")
                .replaceAll("\\s*([=<>(),])\\s*", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
