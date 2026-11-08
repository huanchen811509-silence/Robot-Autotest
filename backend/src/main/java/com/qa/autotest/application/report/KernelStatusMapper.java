package com.qa.autotest.application.report;

import com.qa.autotest.domain.kernel.CaseStatus;

/**
 * 内核六态 → 报告域四态（PASS/FAIL/SKIP/UNKNOWN）的唯一映射（v2.7 FR-02）。
 *
 * <p>映射口径与通过率对齐 {@link CaseStatus#isFailure()}：
 * <ul>
 *   <li>PASS → PASS、FAIL → FAIL、SKIP → SKIP：三态同语义直映；</li>
 *   <li>XFAIL → SKIP：预期失败且失败发生，计非失败（RF 侧等价物
 *       {@code Run Keyword And Expect Error} 得 PASS，两者通过率口径一致）；</li>
 *   <li>XPASS → FAIL：预期失败未发生，计失败（RF 侧等价 FAIL）；</li>
 *   <li>ERROR → FAIL：pytest 侧收集/夹具异常，报告域无 ERROR 态，按失败计。</li>
 * </ul>
 *
 * <p>报告域的 UNKNOWN 在 pytest 路径恒不出现：六态显式映射，不留缺省分支。
 */
public final class KernelStatusMapper {

    private KernelStatusMapper() {
    }

    /** 报告域用例状态字符串（CaseNode.status 口径）。 */
    public static String toReportStatus(CaseStatus status) {
        return switch (status) {
            case PASS -> "PASS";
            case FAIL -> "FAIL";
            case SKIP -> "SKIP";
            case XFAIL -> "SKIP";
            case XPASS -> "FAIL";
            case ERROR -> "FAIL";
        };
    }

    /** 是否计入失败（对外通过率口径）：与 {@link CaseStatus#isFailure()} 同口径，引用它保证单一真源。 */
    public static boolean countsAsFailure(CaseStatus status) {
        return status.isFailure();
    }
}
