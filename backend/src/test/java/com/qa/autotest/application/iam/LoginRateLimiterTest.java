package com.qa.autotest.application.iam;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 登录限流：窗口内失败达上限即 429，成功清零（FR-3.4-03）。 */
class LoginRateLimiterTest {

    /** 可推进的时钟，验证窗口过期而不是靠 sleep。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void blocksAfterMaxFailuresAndResetsOnSuccess() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, 300);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("10.0.0.1", "Admin");
        }
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> limiter.requireAllowed("10.0.0.1", "admin"));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, blocked.errorCode());

        limiter.reset("10.0.0.1", "ADMIN");
        assertDoesNotThrow(() -> limiter.requireAllowed("10.0.0.1", "admin"));
    }

    @Test
    void allowsAgainAfterWindowExpires() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 3, 60);
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("10.0.0.2", "u");
        }
        assertThrows(BusinessException.class, () -> limiter.requireAllowed("10.0.0.2", "u"));

        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> limiter.requireAllowed("10.0.0.2", "u"));
    }

    @Test
    void keysByIpAndUsernameIndependently() {
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.systemUTC(), 2, 300);
        limiter.recordFailure("10.0.0.3", "a");
        limiter.recordFailure("10.0.0.3", "a");
        assertDoesNotThrow(() -> limiter.requireAllowed("10.0.0.3", "b"));
        assertThrows(BusinessException.class, () -> limiter.requireAllowed("10.0.0.3", "A"));
    }
}
