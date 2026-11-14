package com.qa.autotest.application.execution;

import java.util.List;

/**
 * 一条待确认的可执行命令（W57-18）。只读预览与确认摘要的最小单位。
 *
 * <p>{@code cwd} 由生成器保证为绝对路径；{@code dut} 是被测设备标识（可空）。
 * argv 在构造时做防御性拷贝，避免调用方后续改动影响摘要。
 */
public record CommandPlan(
        String caseCode,
        List<String> argv,
        String cwd,
        int timeoutSeconds,
        String dut) {

    public CommandPlan {
        argv = argv == null ? List.of() : List.copyOf(argv);
    }
}
