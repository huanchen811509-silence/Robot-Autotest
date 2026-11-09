package com.qa.autotest.api;

import com.qa.autotest.application.metrics.RequestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 请求成功率与耗时采样（FR-3.3-10）。只统计 {@code /api/} 业务请求，静态资源不计入 SLO。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiMetricsFilter extends OncePerRequestFilter {

    private final RequestMetrics metrics;

    public ApiMetricsFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        long started = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.record(projectId(request), response.getStatus() >= 500,
                    System.currentTimeMillis() - started);
        }
    }

    private static long projectId(HttpServletRequest request) {
        String value = request.getParameter("projectId");
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
