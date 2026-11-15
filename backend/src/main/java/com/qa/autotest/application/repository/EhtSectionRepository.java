package com.qa.autotest.application.repository;

import com.qa.autotest.application.report.EhtSections;

/**
 * v5.14.0 / W64-14：三段式报告的只读装配入口。
 *
 * <p>与 {@code ReportReadRepository} 分开的理由：它是**展示聚合**（四态 / 未定项 / 证据 /
 * 差异），不是 run 的领域查询；混进原接口会让执行报告链路被迫依赖 EHT 数据结构。
 */
public interface EhtSectionRepository {

    /** 查不到 run / 装配失败时返回可解释的空值，不抛异常、不返回 null。 */
    EhtSections load(long projectId, String runId);
}
