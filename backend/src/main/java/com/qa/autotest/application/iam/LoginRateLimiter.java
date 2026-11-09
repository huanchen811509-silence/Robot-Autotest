package com.qa.autotest.application.iam;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（FR-3.4-03）。
 *
 * <p>按「IP + 用户名」滑动窗口统计<b>失败</b>次数，成功后清零；窗口内失败达到上限即
 * {@link ErrorCode#TOO_MANY_REQUESTS}（HTTP 429），避免暴力破解与撞库。
 * 单实例内存实现，多实例部署时各节点独立计数（本版单实例，接受该近似）。
 */
@Component
public class LoginRateLimiter {

    private record Window(Deque<Instant> failures) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;

    @Autowired
    public LoginRateLimiter(Clock clock,
                            @Value("${iam.login.max-failures:5}") int maxFailures,
                            @Value("${iam.login.window-seconds:300}") long windowSeconds) {
        this.clock = clock;
        this.maxFailures = Math.max(1, maxFailures);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    /** 登录前检查：超限直接 429，不再核对口令。 */
    public void requireAllowed(String ip, String username) {
        String key = key(ip, username);
        Deque<Instant> queue = queue(key);
        synchronized (queue) {
            prune(queue, clock.instant());
            if (queue.size() >= maxFailures) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "登录失败次数过多，请 " + window.toMinutes() + " 分钟后再试");
            }
        }
    }

    /** 记一次失败；只记失败，避免正常用户被成功登录挤占配额。 */
    public void recordFailure(String ip, String username) {
        String key = key(ip, username);
        Deque<Instant> queue = queue(key);
        synchronized (queue) {
            prune(queue, clock.instant());
            queue.addLast(clock.instant());
        }
    }

    /** 登录成功清零，给用户明确的「已恢复」语义。 */
    public void reset(String ip, String username) {
        windows.remove(key(ip, username));
    }

    private Deque<Instant> queue(String key) {
        return windows.computeIfAbsent(key, ignored -> new Window(new ArrayDeque<>())).failures();
    }

    private void prune(Deque<Instant> queue, Instant now) {
        Instant edge = now.minus(window);
        while (!queue.isEmpty() && !queue.peekFirst().isAfter(edge)) {
            queue.pollFirst();
        }
    }

    private static String key(String ip, String username) {
        String safeIp = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        String safeUser = username == null ? "" : username.trim().toLowerCase();
        return safeIp + "|" + safeUser;
    }
}
