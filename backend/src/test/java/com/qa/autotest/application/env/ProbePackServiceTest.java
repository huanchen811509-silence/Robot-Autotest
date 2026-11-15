package com.qa.autotest.application.env;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** W9 回归：MariaDB JDBC 把 TINYINT(1) 读成 Boolean，不能只按 Number 处理。 */
class ProbePackServiceTest {

    @Test
    void booleanFormsFromMariaDbAreAccepted() {
        assertTrue(ProbePackService.requiredOf(Boolean.TRUE));
        assertFalse(ProbePackService.requiredOf(Boolean.FALSE));
    }

    @Test
    void numericAndStringFormsStayCompatible() {
        assertTrue(ProbePackService.requiredOf(1));
        assertFalse(ProbePackService.requiredOf(0));
        assertTrue(ProbePackService.requiredOf("1"));
        assertTrue(ProbePackService.requiredOf("true"));
        assertFalse(ProbePackService.requiredOf("0"));
        assertFalse(ProbePackService.requiredOf(null));
    }
}
