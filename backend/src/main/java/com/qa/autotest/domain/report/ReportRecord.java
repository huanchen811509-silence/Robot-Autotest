package com.qa.autotest.domain.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 运行报告视图（GET /api/reports 与详情接口共用）。
 *
 * @param projectId       项目
 * @param runId           RUN_ID 字符串（run.run_id）
 * @param state           运行状态（run.run_status）
 * @param exportable      是否可对外交付
 * @param metadataMissing 缺失键名列表（空列表表示不缺失）
 * @param metadata        完整 metadata 十项键值
 * @param passCount       PASS 数
 * @param failCount       FAIL 数
 * @param skipCount       SKIP 数
 * @param startedAt       起始时间（metadata.STARTED_AT，缺失时取 run.started_at）
 * @param finishedAt      结束时间
 */
public record ReportRecord(long projectId,
                           String runId,
                           String state,
                           boolean exportable,
                           List<String> metadataMissing,
                           Map<String, String> metadata,
                           int passCount,
                           int failCount,
                           int skipCount,
                           Instant startedAt,
                           Instant finishedAt,
                           /**
                            * 判据汇总结论（v5.7.0 / FR-v5.7-13，复核 W57-50）：
                            * {@code PASS} / {@code FAIL} / {@code UNDETERMINED}（全部未声明或缺失）。
                            * 由 {@code test_result.criteria_verdict} 聚合而来，**不是** failCount 的翻译。
                            */
                           String criteriaConclusion,
                           /** 参与判据评估的用例数（criteria_verdict 非空）。 */
                           int criteriaEvaluated,
                           /** 判据缺失（MISSING）的用例数。 */
                           int criteriaMissingCount) {

    public ReportRecord {
        metadataMissing = metadataMissing == null ? List.of() : List.copyOf(metadataMissing);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 兼容 v5.7.0 之前的 11 参写法（判据汇总未知）：结论按 {@code UNDETERMINED} 处理，
     * 不回落成「failCount==0 就 PASS」——那正是本次要消除的口径。
     */
    public ReportRecord(long projectId, String runId, String state, boolean exportable,
                        List<String> metadataMissing, Map<String, String> metadata,
                        int passCount, int failCount, int skipCount,
                        Instant startedAt, Instant finishedAt) {
        this(projectId, runId, state, exportable, metadataMissing, metadata,
                passCount, failCount, skipCount, startedAt, finishedAt, null, 0, 0);
    }

    /** 有判据评估记录时，结论只认判据；否则显式 UNDETERMINED。 */
    public String effectiveConclusion() {
        return criteriaConclusion == null || criteriaConclusion.isBlank()
                ? "UNDETERMINED" : criteriaConclusion;
    }
}
