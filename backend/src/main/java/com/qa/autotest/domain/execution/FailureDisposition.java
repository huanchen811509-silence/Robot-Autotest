package com.qa.autotest.domain.execution;

import java.time.Instant;

/**
 * 失败处置记录（FR-v5.3-06 / V11_52 / W53-06）。
 *
 * <p>状态机：{@code open → assigned → decided → closed}，任一已决状态可 {@code reopen}。
 * 迁移规则写在 {@code FailureDispositionService} 里而不是这个 record 上——
 * record 是数据，不该带流程；流程会变，数据不会。
 *
 * <p>{@code caseId = 0} 表示「按指纹批量处置」：同一类失败（比如全项目 SSH 口令错）
 * 认一次就够，不必逐条点。唯一键是 (项目, 指纹, 用例)，所以 0 与真实 id 各占一行，
 * 互不覆盖。
 */
public record FailureDisposition(
        Long id,
        long projectId,
        long caseId,
        String caseCode,
        String kernel,
        String fingerprint,
        String category,
        String location,
        String status,
        Long assigneeId,
        String decision,
        String note,
        int occurrences,
        int reopenCount,
        String reopenReason,
        String lastRunId,
        Instant decidedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ASSIGNED = "assigned";
    public static final String STATUS_DECIDED = "decided";
    public static final String STATUS_CLOSED = "closed";

    public static final String DECISION_PRODUCT_BUG = "product_bug";
    public static final String DECISION_ENV_ISSUE = "env_issue";
    public static final String DECISION_CASE_DEFECT = "case_defect";
    public static final String DECISION_KNOWN_ISSUE = "known_issue";
    public static final String DECISION_WONT_FIX = "wont_fix";

    /** 已定论的状态集合（可关闭、可作为豁免依据）。 */
    public boolean settled() {
        return STATUS_DECIDED.equals(status) || STATUS_CLOSED.equals(status);
    }

    /** 是否构成「已知问题豁免」：这是从待处置里摘出去，不是把 FAIL 改成 PASS。 */
    public boolean exempted() {
        return settled() && DECISION_KNOWN_ISSUE.equals(decision);
    }
}
