package com.qa.autotest.domain.report;

import java.util.Locale;

/**
 * 报告数据来源（FR-v3.10-03）。
 *
 * <p>两种来源在执行层没有区别：最终都落成「一条 run + 它的用例事实」。区别只在
 * **用户怎么表达意图**——是按「那次执行」建报告，还是按「这个用例集最近一次怎么跑」建报告。
 * 因此 {@link #CASE_SET} 创建时也要先解析出唯一一条 run，后续链路完全复用。
 *
 * <p>{@link #RUN} 是默认值：v3.10.0 之前的报告全部属于这一类，回填时不留空。
 */
public enum ReportSourceType {
    /** 按执行记录（旧路径，默认）。 */
    RUN,
    /** 按用例集：取该集最近一次执行，或用户指定的一次。 */
    CASE_SET;

    /** 入参解析：空值回落 {@link #RUN}（旧客户端不发这个字段），未知值直接报错。 */
    public static ReportSourceType parse(String value) {
        if (value == null || value.isBlank()) {
            return RUN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "run" -> RUN;
            case "case_set" -> CASE_SET;
            default -> throw new IllegalArgumentException("未知的报告来源类型: " + value);
        };
    }
}
