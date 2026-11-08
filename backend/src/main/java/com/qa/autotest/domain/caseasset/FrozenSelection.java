package com.qa.autotest.domain.caseasset;

import java.time.Instant;

/**
 * 执行选择冻结快照（SCEN-02）：不可变 record。
 * 冻结后不允许改写；仓储只发 INSERT，由唯一键 uk_execution_selection_sha 兜底幂等。
 */
public record FrozenSelection(
        Long id,
        long projectId,
        Long designId,
        String payloadJson,
        String resourceSnapshotJson,
        String sha256,
        Instant frozenAt) {
}
