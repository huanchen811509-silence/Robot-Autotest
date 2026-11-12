package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.CaseDurationBaselineRepository;
import com.qa.autotest.domain.execution.CaseDurationBaseline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 用例时长基线与软超时判定（FR-v5.3-03/04 / 验收 C7–C12 / W53-03+04）。
 *
 * <p>三件事：<b>重算</b>（近 30 天窗口）、<b>判定</b>（慢 / 异常快 / 正常）、
 * <b>给出建议超时</b>（给运维与前端用）。
 *
 * <p><b>为什么分位数在 Java 算</b>：一是 SQL 里写窗口函数既不可测也不可改，
 * 二是「剔除 top 1%」这种规则必须和「不足 5 个样本不参与判定」放在一起看，
 * 拆到 SQL 与应用两处，规则就会各走各的。
 *
 * <p><b>为什么软超时不中断</b>（D10）：基线是统计量，不是契约。按 P90 判定慢会
 * 必然误报一部分正常波动（P90 的定义就是 10% 的样本超过它）。中断执行需要的是
 * 硬超时（{@code execution.test-timeout-seconds}），软超时只标记、只进报告。
 */
@Service
public class DurationBaselineService {

    /** 样本下限：少于这个数就标 insufficient，且不参与慢判定。 */
    public static final int MIN_SAMPLES = 5;
    /** 离群剔除比例：去掉耗时最长的 1%（至少 1 条，前提是剔完仍够 MIN_SAMPLES）。 */
    public static final double OUTLIER_TOP_RATIO = 0.01;
    /** 软超时阈值 = P90 × 该系数。 */
    public static final double SLOW_FACTOR = 1.5;
    /** 异常快阈值 = P50 × 该系数（低于它且 PASS 视为可疑）。 */
    public static final double SUSPICIOUS_FAST_FACTOR = 0.2;
    /** 统计窗口：默认近 30 天。 */
    public static final int WINDOW_DAYS = 30;

    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_SLOW = "SLOW";
    public static final String LEVEL_SUSPICIOUS_FAST = "SUSPICIOUS_FAST";
    public static final String LEVEL_UNKNOWN = "UNKNOWN";

    /**
     * 环境失败的粗筛关键字。
     *
     * <p><b>为什么是粗筛</b>：环境失败本该由归因分类判定，而分类结果目前只落在
     * pytest 的步骤证据里（{@code pytest_case_step}），RF 侧没有等价物。所以在
     * 归因结论落库之前，只能对 failure_msg 做关键字匹配——这会漏掉一部分环境失败
     * （表现为基线略微偏高），但不会把产品缺陷误当环境问题剔除（漏判比误判安全）。
     */
    private static final List<String> ENV_HINTS = List.of(
            "connection refused", "no route to host", "connection timed out",
            "timed out", "ssh", "permission denied", "host is unreachable",
            "network is unreachable", "connect failed", "断链", "连接超时", "无法连接");

    private final CaseDurationBaselineRepository baseline;

    public DurationBaselineService(CaseDurationBaselineRepository baseline) {
        this.baseline = baseline;
    }

    /** 重算结果：给页面与日志一句可核对的话。 */
    public record RecalcResult(int groups, int computed, int insufficient, int samples,
                               int excluded, int skippedGroups, int anomalousGroups,
                               Instant windowStart, Instant windowEnd) {
    }

    /**
     * 慢/快判定。
     *
     * @param level     {@code NORMAL} / {@code SLOW} / {@code SUSPICIOUS_FAST} / {@code UNKNOWN}
     * @param thresholdMs 实际使用的软超时阈值（P90×1.5）；无基线时为 0
     */
    public record DurationVerdict(String level, String reason, long thresholdMs,
                                  long p50Ms, long p90Ms, boolean insufficient) {

        public boolean slow() {
            return LEVEL_SLOW.equals(level);
        }

        public boolean suspiciousFast() {
            return LEVEL_SUSPICIOUS_FAST.equals(level);
        }
    }

    /**
     * 全量重算某项目的基线。
     *
     * <p>重算即整体替换（先删后插）：增量更新会留下「用例已删但基线还在」的孤儿行，
     * 而那些行恰好在页面上看起来最正常。
     */
    public RecalcResult recompute(long projectId) {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(WINDOW_DAYS));
        List<CaseDurationBaselineRepository.DurationSample> samples = baseline.samples(projectId, start);

        Map<String, List<CaseDurationBaselineRepository.DurationSample>> grouped =
                new LinkedHashMap<>();
        for (CaseDurationBaselineRepository.DurationSample sample : samples) {
            String key = sample.caseId() + "|" + normalizeKernel(sample.kernel());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);
        }

        List<CaseDurationBaseline> rows = new ArrayList<>();
        int excludedTotal = 0;
        int insufficientGroups = 0;
        int skipped = 0;
        int anomalous = 0;
        for (List<CaseDurationBaselineRepository.DurationSample> group : grouped.values()) {
            CaseDurationBaselineRepository.DurationSample head = group.get(0);
            List<Long> durations = new ArrayList<>();
            int excluded = 0;
            for (CaseDurationBaselineRepository.DurationSample sample : group) {
                if (looksLikeEnvFailure(sample.failureMsg())) {
                    excluded++;
                    continue;
                }
                durations.add(sample.durationMs());
            }
            int beforeOutlier = durations.size();
            List<Long> kept = dropTopOutliers(durations);
            excluded += beforeOutlier - kept.size();
            excludedTotal += excluded;

            if (kept.isEmpty()) {
                // 全是环境失败：不写行。写一行 p50=0 会让「阈值 0 秒」变成人人超时。
                skipped++;
                continue;
            }
            kept.sort(Comparator.naturalOrder());
            boolean insufficient = kept.size() < MIN_SAMPLES;
            if (insufficient) {
                insufficientGroups++;
            }
            rows.add(new CaseDurationBaseline(null, projectId, head.caseId(),
                    normalizeKernel(head.kernel()), kept.size(),
                    percentile(kept, 50), percentile(kept, 90), percentile(kept, 99),
                    kept.get(kept.size() - 1), excluded, insufficient,
                    start, end, end));
            if (!rows.get(rows.size() - 1).monotonic()) {
                // 分位算法出问题时，宁可这组没有基线（ETA 走兜底），
                // 也不要把一个 P90 > P99 的值写进去——软超时阈值会照它算，
                // 结果是一条用例永远被标 slow，而且没人看得出来是基线错了。
                rows.remove(rows.size() - 1);
                anomalous++;
            }
        }
        baseline.replaceAll(projectId, rows);
        return new RecalcResult(grouped.size(), rows.size(), insufficientGroups, samples.size(),
                excludedTotal, skipped, anomalous, start, end);
    }

    /**
     * 判定单次执行的耗时。
     *
     * @param verdict 本次结论；只有 PASS 才判「异常快」——失败的用例本来就该短（提前退出），
     *                把它判成 suspicious 会产生大量噪音
     */
    public DurationVerdict judge(long projectId, long caseId, String kernel, long durationMs,
                                 String verdict) {
        String normalized = normalizeKernel(kernel);
        var row = baseline.find(projectId, caseId, normalized);
        if (row.isEmpty()) {
            return new DurationVerdict(LEVEL_UNKNOWN,
                    "该用例在 " + normalized + " 内核下还没有时长基线（样本为空或全是环境失败）",
                    0, 0, 0, true);
        }
        CaseDurationBaseline base = row.get();
        long p50 = base.p50Ms() == null ? 0 : base.p50Ms();
        long p90 = base.p90Ms() == null ? 0 : base.p90Ms();
        long threshold = Math.round(p90 * SLOW_FACTOR);

        if (base.insufficient()) {
            return new DurationVerdict(LEVEL_UNKNOWN,
                    "基线样本仅 " + base.sampleCount() + " 条（少于 " + MIN_SAMPLES
                            + "），不足以判定快慢；参考值 P50=" + seconds(p50) + "s",
                    threshold, p50, p90, true);
        }
        if (p90 > 0 && durationMs > threshold) {
            return new DurationVerdict(LEVEL_SLOW,
                    "耗时 " + seconds(durationMs) + "s 超过软超时阈值 " + seconds(threshold)
                            + "s（P90 " + seconds(p90) + "s × " + SLOW_FACTOR
                            + "）；不中断执行，仅标记并进报告",
                    threshold, p50, p90, false);
        }
        if (p50 > 0 && durationMs < Math.round(p50 * SUSPICIOUS_FAST_FACTOR)
                && "PASS".equalsIgnoreCase(verdict)) {
            return new DurationVerdict(LEVEL_SUSPICIOUS_FAST,
                    "耗时 " + seconds(durationMs) + "s 不足 P50（" + seconds(p50)
                            + "s）的 20% 却判通过——疑似空跑或断言未生效，建议人工抽查证据",
                    threshold, p50, p90, false);
        }
        return new DurationVerdict(LEVEL_NORMAL,
                "耗时 " + seconds(durationMs) + "s 在基线范围内（P50 " + seconds(p50)
                        + "s / P90 " + seconds(p90) + "s）",
                threshold, p50, p90, false);
    }

    /** 建议超时（秒）：软超时阈值向上取整到 5 秒，并至少 30 秒。 */
    public long suggestedTimeoutSeconds(long projectId, long caseId, String kernel) {
        return baseline.find(projectId, caseId, normalizeKernel(kernel))
                .map(row -> {
                    long threshold = Math.round
                            ((row.p90Ms() == null ? 0 : row.p90Ms()) * SLOW_FACTOR / 1000.0);
                    long rounded = ((threshold + 4) / 5) * 5;
                    return Math.max(30, rounded);
                })
                .orElse(0L);
    }

    public List<CaseDurationBaseline> list(long projectId) {
        return baseline.list(projectId);
    }

    /** 无基线用例的兜底估算：默认 3 分钟一条。 */
    public static final long FALLBACK_SECONDS = 180;

    /**
     * 一批用例的预计总耗时（W53-08 长跑识别用）。
     *
     * @param covered  有可用基线（非 {@code insufficient}）的用例数
     * @param fallback 按兜底值估算的用例数
     * @param seconds  预计总秒数
     */
    public record Estimate(int total, int covered, int fallback, long seconds) {

        /** 基线覆盖率（百分比）：低说明这次估算基本靠兜底，长跑判定不可靠。 */
        public double coveragePercent() {
            return total <= 0 ? 0.0
                    : Math.round(covered * 1000.0 / total) / 10.0;
        }
    }

    /**
     * 估算一批用例的预计总耗时。
     *
     * <p><b>为什么用 P90 而不是 P50</b>：这个值用于「这次算不算长跑」，
     * 低估的后果是长跑被当成短跑塞进白天，占满设备与租约；高估的后果只是
     * 早一点被判成长跑。两边代价不对等，所以取偏保守的 P90。
     *
     * <p>无基线的用例按 {@link #FALLBACK_SECONDS} 计，并单独计数——
     * 估算结果要能说明「这里面有多少是靠猜的」，否则一个覆盖率 10% 的估算
     * 会被当成和覆盖率 100% 时一样可信。
     */
    public Estimate estimateSeconds(long projectId,
                                    List<RunSplitter.PlannedCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return new Estimate(0, 0, 0, 0);
        }
        // 按内核分组取映射：同一用例在 RF 与 pytest 下耗时不同，混用会把估算拉偏
        java.util.Map<String, java.util.Map<String, Long>> byKernel = new java.util.HashMap<>();
        int covered = 0;
        int fallback = 0;
        long totalMs = 0;
        for (RunSplitter.PlannedCase item : cases) {
            String kernel = normalizeKernel(item.kernel());
            java.util.Map<String, Long> p90 = byKernel.computeIfAbsent(kernel,
                    key -> baseline.p90ByCaseCode(projectId, key));
            Long ms = item.caseCode() == null ? null : p90.get(item.caseCode());
            if (ms != null && ms > 0) {
                covered++;
                totalMs += ms;
            } else {
                fallback++;
                totalMs += FALLBACK_SECONDS * 1000;
            }
        }
        return new Estimate(cases.size(), covered, fallback, (totalMs + 999) / 1000);
    }

    // ---------------------------------------------------------------- 算法

    /**
     * 剔除耗时最长的 1%（至少 1 条），但剔完不得少于 {@link #MIN_SAMPLES} 条。
     *
     * <p>为什么「剔完不得少于 MIN_SAMPLES」：5 条样本再剔 1 条就只剩 4 条，
     * 那正好落在「样本不足」线上，剔离群反而让基线变成不可用——本末倒置。
     */
    static List<Long> dropTopOutliers(List<Long> durations) {
        if (durations.isEmpty()) {
            return List.of();
        }
        List<Long> sorted = new ArrayList<>(durations);
        sorted.sort(Comparator.naturalOrder());
        int drop = (int) Math.ceil(sorted.size() * OUTLIER_TOP_RATIO);
        drop = Math.max(1, drop);
        int keep = sorted.size() - drop;
        if (keep < MIN_SAMPLES) {
            return sorted;
        }
        return new ArrayList<>(sorted.subList(0, keep));
    }

    /**
     * 最近秩分位数（nearest-rank）。
     *
     * <p>不用线性插值：插值会给出「P90 = 12345.6ms」这种不存在的耗时，
     * 而阈值最终要落到人能理解的秒数上，最近秩至少取的是一个真实观测值。
     *
     * @param sorted 已升序排列、非空
     */
    static long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
        return sorted.get(index);
    }

    private static boolean looksLikeEnvFailure(String failureMsg) {
        if (failureMsg == null || failureMsg.isBlank()) {
            return false;
        }
        String text = failureMsg.toLowerCase(Locale.ROOT);
        for (String hint : ENV_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKernel(String kernel) {
        if (kernel == null || kernel.isBlank()) {
            return "robot";
        }
        return kernel.trim().toLowerCase(Locale.ROOT);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
