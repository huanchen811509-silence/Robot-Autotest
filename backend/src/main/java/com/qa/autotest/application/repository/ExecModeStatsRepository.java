package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.List;

/**
 * 按执行方式（自动/手工）聚合用例结果（FR-v5.4-05 / W54-05）。
 *
 * <p>为什么单独开一个取数口而不是塞进趋势或报告服务：<b>四条链路都要用它</b>
 * ——报告第 1/3 章、趋势图两条线、对外导出默认只报自动、页面顶部合计。
 * 各写一份 SQL 的结果是「四个页面的通过率不一样」，而这种不一致永远说不清是谁错了。
 */
public interface ExecModeStatsRepository {

    /**
     * 一种执行方式的计数。
     *
     * @param execMode {@code auto} / {@code manual}；未知值原样返回（不假装是 auto）
     * @param total    参与判定的用例结果条数（不含 PLANNED / PENDING）
     * @param passed   verdict=PASS 的条数
     */
    record ModeCount(String execMode, int total, int passed) {

        public int failed() {
            return Math.max(0, total - passed);
        }
    }

    /**
     * 按执行方式聚合。
     *
     * @param projectId 项目
     * @param since     起始时间
     * @param runId     只统计某次 run；null 表示整个项目
     */
    List<ModeCount> byExecMode(long projectId, Instant since, String runId);
}
