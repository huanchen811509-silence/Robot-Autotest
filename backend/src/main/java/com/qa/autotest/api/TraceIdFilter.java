package com.qa.autotest.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * traceId 透传（EW32-07）。
 *
 * <p>优先级：请求头 {@code X-Trace-Id} → {@code X-Request-Id} → 新生成 32 位 hex。
 * 只接受 {@code [A-Za-z0-9._-]{1,64}}，避免把换行/控制字符写进日志（日志注入）。
 *
 * <p>traceId 同时写入 MDC（供日志 pattern 的 {@code %X{traceId}}）与响应头，
 * 前端报障时可直接把响应头里的 id 贴回来定位整条链路。
 * 异步任务由 {@code BoundedExecutor} 提交时复制 MDC，日志不会断链。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ALTERNATE_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolve(request.getHeader(HEADER), request.getHeader(ALTERNATE_HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 纯函数：便于单测覆盖「合法沿用 / 非法丢弃 / 缺省生成」。 */
    public static String resolve(String primary, String fallback) {
        if (isSafe(primary)) {
            return primary.trim();
        }
        if (isSafe(fallback)) {
            return fallback.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }
}
