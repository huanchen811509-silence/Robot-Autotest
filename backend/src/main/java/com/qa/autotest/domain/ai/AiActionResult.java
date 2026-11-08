package com.qa.autotest.domain.ai;

/**
 * AI 动作结果（AIX-04 / AIX-05）。DEGRADED 是正常降级态，不是失败。
 *
 * <p>FR-AI-01 起，模型 stdout / stderr / exitCode / 耗时随响应体返回；
 * 五参构造保留给既有调用方（AiPanelTest）与降级场景。
 */
public record AiActionResult(String action, String level, boolean degraded,
                             String summary, boolean audited,
                             String stdout, String stderr, int exitCode,
                             long durationMs, String cli, boolean timedOut) {

    /** 兼容既有 5 参构造：子进程未执行时 stdout 为空、exitCode=-1。 */
    public AiActionResult(String action, String level, boolean degraded,
                           String summary, boolean audited) {
        this(action, level, degraded, summary, audited, "", "", -1, 0L, null, false);
    }
}
