package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.List;

/**
 * 双内核统一视图取数（FR-v5.4-06 / W54-06）。
 *
 * <p>RF 与 pytest 的 run 在库里本来就是同构的（同一张 {@code run} 表、同一个 {@code kernel} 列），
 * 差别只在用例结果的表达。因此「统一视图」不是一个新表，而是**一条按内核分组的聚合查询**
 * 加上一份对齐口径——新表会让两边数据有两条真源，早晚对不上。
 */
public interface KernelViewRepository {

    /**
     * 一个内核的统计。
     *
     * @param kernel         {@code robot} / {@code pytest}
     * @param runs           run 数
     * @param finished       其中真正跑完的（FINISHED）
     * @param failed         其中失败的（FAILED）
     * @param cases          用例级结果条数（PASS/FAIL）
     * @param passedCases    其中通过
     * @param avgSeconds     平均单 run 耗时（秒），无数据为 0
     */
    record KernelLane(String kernel, int runs, int finished, int failed, int cases,
                      int passedCases, long avgSeconds) {

        public double passRatePercent() {
            return cases <= 0 ? 0.0 : Math.round(passedCases * 1000.0 / cases) / 10.0;
        }
    }

    /** 按内核分组的统计（不含手工内核；手工统计走 {@code ExecModeStatsRepository}）。 */
    List<KernelLane> lanes(long projectId, Instant since);
}
