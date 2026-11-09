package com.qa.autotest.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 安全响应头（FR-3.4-03 / EW34-03）。
 *
 * <p>只加浏览器侧防护头，不改鉴权语义：
 * <ul>
 *   <li>API 响应禁止被当页面嵌入（{@code X-Frame-Options: DENY}）；</li>
 *   <li>{@code nosniff} 阻止 MIME 嗅探；</li>
 *   <li>CSP 默认只允许同源脚本/样式，SSE 与 fetch 不受影响；</li>
 *   <li>HSTS 只在 HTTPS 请求下发送，本地 HTTP 调试不会被锁死。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; script-src 'self'; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; "
            + "form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        apply(response, request.isSecure());
        chain.doFilter(request, response);
    }

    /** 纯函数：便于单测断言头名与值，不需要启动容器。 */
    static void apply(HttpServletResponse response, boolean secure) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", CSP);
        if (secure) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");
        }
    }
}
