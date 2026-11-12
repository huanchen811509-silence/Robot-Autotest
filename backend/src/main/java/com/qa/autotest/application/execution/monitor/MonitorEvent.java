package com.qa.autotest.application.execution.monitor;

import java.time.Instant;

/**
 * 结构化事件（FR-v5.3-01 / R2 的第二类输出）。
 *
 * <p>与日志切片的区别：日志切片是「给人看的原文」，事件是「给程序看的离散事实」。
 * 两者同源（同一行输入），但事件带类型、编号与时间，能被页面按类型折叠、
 * 能被 ETA / 断言校验等下游直接消费，不必再解析文本。
 *
 * <p>schema 只有这一种，RF 与 pytest 都必须产出它（R3）。差别只在适配器怎么读行。
 */
public record MonitorEvent(long seq, Instant at, String kernel, Kind kind,
                           String caseCode, String name, String detail) {

    public enum Kind {
        /** 阶段切换：preparing / running / reporting / done。 */
        PHASE,
        /** 用例开始。 */
        CASE_START,
        /** 用例结束（detail 里带 PASS/FAIL/SKIP）。 */
        CASE_END,
        /** 判定出的一条命令（与 {@link CommandRecord} 配对，这边只记事实）。 */
        COMMAND,
        /** 警告：不影响判定，但值得看。 */
        WARNING,
        /** 错误：失败原因的首行。 */
        ERROR,
        /** 监视器自身降级（自身异常/超限），不是被测对象的错。 */
        DEGRADED
    }

    /** 用例结束事件是否 PASS。 */
    public boolean passed() {
        return kind == Kind.CASE_END && detail != null
                && detail.toUpperCase(java.util.Locale.ROOT).startsWith("PASS");
    }
}
