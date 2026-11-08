package com.qa.autotest.application.report;

import com.qa.autotest.domain.kernel.CaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.7 TC-05：内核六态 → 报告域映射锁死。
 * 通过率口径与 {@link CaseStatus#isFailure()} 对齐：XFAIL 计非失败、XPASS/ERROR 计失败。
 */
class KernelStatusMappingTest {

    @Test
    void sixStatesMapExactly() {
        assertEquals("PASS", KernelStatusMapper.toReportStatus(CaseStatus.PASS));
        assertEquals("FAIL", KernelStatusMapper.toReportStatus(CaseStatus.FAIL));
        assertEquals("SKIP", KernelStatusMapper.toReportStatus(CaseStatus.SKIP));
        // 预期失败且失败发生：pytest XFAIL ≡ RF Run Keyword And Expect Error（PASS），均计非失败
        assertEquals("SKIP", KernelStatusMapper.toReportStatus(CaseStatus.XFAIL));
        // 预期失败未发生：pytest XPASS ≡ RF FAIL，均计失败
        assertEquals("FAIL", KernelStatusMapper.toReportStatus(CaseStatus.XPASS));
        assertEquals("FAIL", KernelStatusMapper.toReportStatus(CaseStatus.ERROR));
    }

    @Test
    void reportDomainNeverGetsUnknownFromPytest() {
        for (CaseStatus status : CaseStatus.values()) {
            assertTrue(!"UNKNOWN".equals(KernelStatusMapper.toReportStatus(status)),
                    "pytest 六态显式映射，报告域不得出现 UNKNOWN: " + status);
        }
    }

    @Test
    void failureAccountingMatchesCaseStatusIsFailure() {
        assertTrue(KernelStatusMapper.countsAsFailure(CaseStatus.FAIL));
        assertTrue(KernelStatusMapper.countsAsFailure(CaseStatus.XPASS));
        assertTrue(KernelStatusMapper.countsAsFailure(CaseStatus.ERROR));
        assertTrue(!KernelStatusMapper.countsAsFailure(CaseStatus.PASS));
        assertTrue(!KernelStatusMapper.countsAsFailure(CaseStatus.SKIP));
        assertTrue(!KernelStatusMapper.countsAsFailure(CaseStatus.XFAIL));
    }
}
