package com.qa.autotest.api;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** EW32-07 · traceId 解析、响应头回写、MDC 生命周期。 */
class TraceIdFilterTest {

    @Test
    @DisplayName("优先沿用请求头 X-Trace-Id，并回写响应头")
    void usesPrimaryHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();
        jakarta.servlet.FilterChain chain =
                (req, res) -> duringChain.set(MDC.get(TraceIdFilter.MDC_KEY));

        new TraceIdFilter().doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("trace-123");
        assertThat(duringChain.get()).isEqualTo("trace-123");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("主头非法时回退到 X-Request-Id")
    void fallsBackToAlternate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "bad\ntrace");
        request.addHeader(TraceIdFilter.ALTERNATE_HEADER, "req-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("req-456");
    }

    @Test
    @DisplayName("都没有时生成 32 位 hex，不抛异常")
    void generatesWhenMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("纯函数：过滤换行/超长，防止日志注入")
    void rejectsUnsafeValues() {
        assertThat(TraceIdFilter.resolve("ok-1", "alt")).isEqualTo("ok-1");
        assertThat(TraceIdFilter.resolve("x".repeat(65), "alt")).isEqualTo("alt");
        assertThat(TraceIdFilter.resolve("a\rb", null)).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolve("", " ")).matches("[0-9a-f]{32}");
    }
}
