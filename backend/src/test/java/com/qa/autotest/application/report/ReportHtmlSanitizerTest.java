package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** v5.14.0 / W64-05 / NFR-06：清洗规则逐条可判，不许只靠 iframe sandbox 兜底。 */
class ReportHtmlSanitizerTest {

    @Test
    void emptyStaysEmpty() {
        assertEquals("", ReportHtmlSanitizer.sanitize(null));
        assertEquals("", ReportHtmlSanitizer.sanitize("   "));
    }

    @Test
    void removesScriptBlocksAndInlineHandlers() {
        String dirty = "<dl><dt>a</dt><dd>1</dd></dl>"
                + "<script>fetch('http://evil')</script>"
                + "<p onclick=\"alert(1)\" onload='x()'>hello</p>";
        String clean = ReportHtmlSanitizer.sanitize(dirty);
        assertFalse(clean.contains("<script"));
        assertFalse(clean.contains("fetch("));
        assertFalse(clean.toLowerCase().contains("onclick"));
        assertFalse(clean.toLowerCase().contains("onload"));
        assertTrue(clean.contains("<dl><dt>a</dt><dd>1</dd></dl>"));
        assertTrue(clean.contains("hello"));
    }

    @Test
    void blocksDangerousProtocols() {
        String dirty = "<a href=\"javascript:alert(1)\">x</a>"
                + "<img src=\"data:text/html;base64,AAA\" onerror=\"y()\">";
        String clean = ReportHtmlSanitizer.sanitize(dirty);
        assertFalse(clean.toLowerCase().contains("javascript:"));
        assertFalse(clean.toLowerCase().contains("data:text/html"));
        assertTrue(clean.contains("blocked:"));
    }

    @Test
    void removesEmbedsButKeepsReportMarkup() {
        String dirty = "<h1>Test Report</h1><iframe src=\"https://evil\"></iframe>"
                + "<style>body{display:none}</style><table><tr><td>ok</td></tr></table>";
        String clean = ReportHtmlSanitizer.sanitize(dirty);
        assertFalse(clean.contains("<iframe"));
        assertFalse(clean.contains("<style"));
        assertTrue(clean.contains("<h1>Test Report</h1>"));
        assertTrue(clean.contains("<table><tr><td>ok</td></tr></table>"));
    }
}
