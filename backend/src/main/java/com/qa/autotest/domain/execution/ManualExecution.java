package com.qa.autotest.domain.execution;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 手工执行记录（FR-v5.4-04 / V11_55 / W54-04）。
 *
 * <p>为什么手工执行也要有一条 {@code run}：报告、台账、趋势、证据索引全都按 run 组织。
 * 手工执行若另立一套，「这个月一共跑了多少」就永远要 union 两张表，
 * 而两份统计迟早会不一致。因此手工 run 与自动 run 共用 run 表，
 * 只在 {@code trigger_type=manual} / {@code kernel=manual} 上区分。
 *
 * <p><b>与自动执行的三处不同</b>（PRD R6）：不占资源租约、不进执行队列、
 * 不触发自动报告。这三条不是「懒得做」，而是手工执行本质上是人在现场操作设备，
 * 平台只是记录本——把记录本塞进调度系统的互斥里，只会让「我想现在手动验一下」
 * 变成「等我排到队」。
 *
 * <p>{@code executorName} 是**快照**：账号改名或离职后，历史记录仍要能看懂是谁做的。
 * 只存 id 会让三个月前的记录变成一串数字。
 */
public record ManualExecution(Long id,
                              long projectId,
                              long runId,
                              long caseId,
                              Long executorId,
                              String executorName,
                              Instant startedAt,
                              Instant finishedAt,
                              String envNote,
                              String verdict,
                              String note,
                              List<StepResult> steps,
                              Instant createdAt,
                              Instant updatedAt) {

    /** 允许的结论。{@code BLOCKED} 是手工执行特有的：设备被占用、缺夹具，跑不了但不是用例的错。 */
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";
    public static final String BLOCKED = "BLOCKED";
    public static final Set<String> VERDICTS = Set.of(PASS, FAIL, SKIP, BLOCKED);

    /**
     * 一步的结果。
     *
     * @param verdict      单步结论；null 表示未确认
     * @param attachments  附件清单（相对证据目录的路径），图/日志
     * @param confirmedAt  确认时间；null 表示未确认
     */
    public record StepResult(int stepNo, String verdict, String note, List<String> attachments,
                             Instant confirmedAt) {

        public boolean confirmed() {
            return confirmedAt != null && verdict != null && !verdict.isBlank();
        }
    }

    /** 未确认的步骤号（升序）。提交结论前必须为空。 */
    public List<Integer> unconfirmedSteps() {
        return steps == null ? List.of()
                : steps.stream().filter(step -> !step.confirmed())
                        .map(StepResult::stepNo).sorted().toList();
    }

    /** 是否已提交结论。 */
    public boolean submitted() {
        return verdict != null && !verdict.isBlank();
    }

    /** 结论归一：空 → 抛错由服务层处理；未知值原样返回由服务层判错。 */
    public static String normalizeVerdict(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }
}
