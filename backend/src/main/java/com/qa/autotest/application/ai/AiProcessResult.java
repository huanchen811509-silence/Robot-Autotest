package com.qa.autotest.application.ai;

/**
 * 一次 CLI 子进程调用的结构化结果（FR-AI-01 / FR-AI-08）。
 *
 * <p>{@code handle} 只用于把真实进程句柄交给 terminate，不参与序列化、不落库。
 * {@code executed=false} 表示「显式降级、没有起子进程」，不是失败。
 */
public record AiProcessResult(
        boolean executed,
        boolean timedOut,
        String cli,
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        ProcessHandle handle,
        String reason) {

    public static AiProcessResult notExecuted(String cli, String reason) {
        return new AiProcessResult(false, false, cli == null ? "claude" : cli,
                "", "", -1, 0L, null, reason);
    }
}
