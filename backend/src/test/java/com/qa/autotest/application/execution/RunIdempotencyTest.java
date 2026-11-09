package com.qa.autotest.application.execution;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-3.2-06：幂等键解析（Header 优先、缺省指纹、窗口与开关）。 */
class RunIdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-11-08T08:00:00Z");

    @Test
    @DisplayName("Header 优先于请求体 runRequestId")
    void header优先() {
        assertEquals("IDEM-1", RunIdempotency.resolve("IDEM-1", "BODY-1", "quick-run",
                NOW, true, 120, 7L, List.of("a.robot")));
    }

    @Test
    @DisplayName("无 Header 时使用请求体 runRequestId")
    void body兜底() {
        assertEquals("BODY-1", RunIdempotency.resolve("  ", " BODY-1 ", "quick-run",
                NOW, true, 120, 7L, List.of("a.robot")));
    }

    @Test
    @DisplayName("无显式键时按端点与参数生成 64 位指纹；同窗口相同参数一致")
    void 指纹同窗口稳定() {
        String first = RunIdempotency.resolve(null, null, "quick-run", NOW,
                true, 120, 7L, 3L, List.of("a.robot"), 2, List.of("dev-1"));
        String second = RunIdempotency.resolve(null, null, "quick-run",
                NOW.plusSeconds(10), true, 120, 7L, 3L, List.of("a.robot"), 2, List.of("dev-1"));
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("参数不同、端点不同或跨窗口都不去重")
    void 指纹区分请求() {
        String base = RunIdempotency.resolve(null, null, "quick-run", NOW,
                true, 120, 7L, List.of("a.robot"));
        assertNotEquals(base, RunIdempotency.resolve(null, null, "quick-run", NOW,
                true, 120, 7L, List.of("b.robot")));
        assertNotEquals(base, RunIdempotency.resolve(null, null, "cases/run", NOW,
                true, 120, 7L, List.of("a.robot")));
        assertNotEquals(base, RunIdempotency.resolve(null, null, "quick-run",
                NOW.plusSeconds(121), true, 120, 7L, List.of("a.robot")));
    }

    @Test
    @DisplayName("开关关闭且无显式键时返回 null（回退随机请求号）")
    void 关闭指纹() {
        assertNull(RunIdempotency.resolve(null, null, "quick-run", NOW,
                false, 120, 7L, List.of("a.robot")));
    }

    @Test
    @DisplayName("超过 64 字符或含非法字符的键返回 400 错误码")
    void 非法键拒绝() {
        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> RunIdempotency.resolve("K".repeat(65), null, "quick-run",
                        NOW, true, 120, 7L));
        assertEquals(ErrorCode.INVALID_ARGUMENT, tooLong.errorCode());

        BusinessException illegal = assertThrows(BusinessException.class,
                () -> RunIdempotency.resolve("bad key!", null, "quick-run",
                        NOW, true, 120, 7L));
        assertEquals(ErrorCode.INVALID_ARGUMENT, illegal.errorCode());
    }

    @Test
    @DisplayName("空白 body key 视为未提供")
    void 空白body视为未提供() {
        String fingerprint = RunIdempotency.resolve(null, "   ", "quick-run", NOW,
                true, 120, 7L);
        assertEquals(64, fingerprint.length());
    }
}
