package com.qa.autotest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** 安全响应头：恒定头始终发送，HSTS 只在 HTTPS 下发送（FR-3.4-03）。 */
class SecurityHeadersFilterTest {

    @Test
    void appliesBaselineHeadersOnHttp() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityHeadersFilter.apply(response, false);

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals(SecurityHeadersFilter.CSP, response.getHeader("Content-Security-Policy"));
        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void addsHstsOnlyOnHttps() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityHeadersFilter.apply(response, true);
        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
    }
}
