package com.qa.autotest.domain.execution;

import java.time.Instant;

/**
 * 用例时长基线（FR-v5.3-03 / V11_51 / W53-03）。
 *
 * <p>按 **(项目, 用例, 内核)** 三元组独立。同一用例在 RF 与 pytest 下的耗时没有可比性
 * ——RF 走 pabot 有进程编排开销，pytest 走 conftest 有收集开销——合成一条基线，
 * 软超时就会在两个内核上同时误报。
 *
 * <p>{@code insufficient} 是「算过但样本不够」的事实，不是「没有基线」：
 * 前者要在页面上提示「基线仅供参考」，后者要提示「还没算过」。两者混在一起，
 * 运维就永远不知道是该等几天攒样本，还是该去点一次重算。
 */
public record CaseDurationBaseline(
        Long id,
        long projectId,
        long caseId,
        String kernel,
        int sampleCount,
        Long p50Ms,
        Long p90Ms,
        Long p99Ms,
        Long maxMs,
        int excludedCount,
        boolean insufficient,
        Instant windowStart,
        Instant windowEnd,
        Instant computedAt) {

    /** 时长转秒（页面展示与超时阈值用秒，库内存毫秒避免取整误差累积）。 */
    public long p50Seconds() {
        return p50Ms == null ? 0 : Math.round(p50Ms / 1000.0);
    }

    public long p90Seconds() {
        return p90Ms == null ? 0 : Math.round(p90Ms / 1000.0);
    }

    public long p99Seconds() {
        return p99Ms == null ? 0 : Math.round(p99Ms / 1000.0);
    }

    /**
     * 分位是否单调（P50 ≤ P90 ≤ P99 ≤ MAX）。
     *
     * <p>这个顺序不是「应该如此」而是<b>算法正确性的必要条件</b>：最近秩法取分位时，
     * 若出现 P90 &gt; P99，说明取整位置算错了（或样本在两次计算之间变了）。
     * 与其让一个错的分位进入软超时阈值，不如在这里就判成不合法。
     * 未计算的分位（null）跳过比较。
     */
    public boolean monotonic() {
        long[] ordered = {p50Ms == null ? -1 : p50Ms, p90Ms == null ? -1 : p90Ms,
                p99Ms == null ? -1 : p99Ms, maxMs == null ? -1 : maxMs};
        long previous = -1;
        for (long value : ordered) {
            if (value < 0) {
                continue;
            }
            if (value < previous) {
                return false;
            }
            previous = value;
        }
        return true;
    }
}
