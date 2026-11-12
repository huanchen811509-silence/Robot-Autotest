package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.CaseDurationBaselineRepository;
import com.qa.autotest.domain.execution.CaseDurationBaseline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时长基线与软超时（FR-v5.3-03/04 / 验收 C7–C12 / W53-03+04）。
 *
 * <p>这组用例重点盯四件事，每一条都对应一次真实误判：
 * <ol>
 *   <li><b>最近秩分位数</b>：插值会算出不存在的耗时，阈值最终要落到秒上；</li>
 *   <li><b>剔除离群不能把样本剔到不足</b>：5 条剔 1 条就变 4 条，基线反而不可用；</li>
 *   <li><b>环境失败不进样本</b>：连不上设备的 0.5 秒会把 P50 拉到毫秒级；</li>
 *   <li><b>样本不足时不判慢</b>：2 条样本的 P90 就是最大值，按它判定必然误报。</li>
 * </ol>
 */
class DurationBaselineServiceTest {

    private static final long PROJECT = 3L;

    private FakeRepository repo;
    private DurationBaselineService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepository();
        service = new DurationBaselineService(repo);
    }

    @Test
    void 最近秩分位数取真实观测值() {
        List<Long> sorted = List.of(100L, 200L, 300L, 400L, 500L,
                600L, 700L, 800L, 900L, 1000L);
        assertEquals(500L, DurationBaselineService.percentile(sorted, 50));
        assertEquals(900L, DurationBaselineService.percentile(sorted, 90));
        assertEquals(100L, DurationBaselineService.percentile(sorted, 1));
        assertEquals(1000L, DurationBaselineService.percentile(sorted, 100));
        // 取到的必须是列表里真实存在的值（不是插值出来的小数）
        assertTrue(sorted.contains(DurationBaselineService.percentile(sorted, 75)));
    }

    @Test
    void 剔除离群后不得少于最小样本数() {
        // 5 条：剔 1 条只剩 4 条，低于 MIN_SAMPLES → 应当不剔
        List<Long> five = List.of(100L, 200L, 300L, 400L, 99999L);
        assertEquals(5, DurationBaselineService.dropTopOutliers(five).size());

        // 10 条：剔 1 条剩 9 条，可以剔
        List<Long> ten = List.of(100L, 200L, 300L, 400L, 500L,
                600L, 700L, 800L, 900L, 99999L);
        List<Long> kept = DurationBaselineService.dropTopOutliers(ten);
        assertEquals(9, kept.size());
        assertFalse(kept.contains(99999L), "最长的 1 条应当被剔除");
    }

    @Test
    void 环境失败不进样本且不产生零阈值基线() {
        // 5 条正常 + 2 条连不上设备
        for (int i = 0; i < 5; i++) {
            repo.samples.add(sample(11L, "robot", 60_000, "PASS", null));
        }
        repo.samples.add(sample(11L, "robot", 500, "FAIL", "ssh connection refused"));
        repo.samples.add(sample(11L, "robot", 600, "FAIL", "connect timed out"));

        DurationBaselineService.RecalcResult result = service.recompute(PROJECT);
        assertEquals(1, result.computed());
        assertEquals(2, result.excluded(), "两条环境失败要被计数剔除");
        CaseDurationBaseline row = repo.stored.get("11|robot");
        assertEquals(5, row.sampleCount());
        assertEquals(60_000L, row.p50Ms());
        assertFalse(row.insufficient());
    }

    @Test
    void 全是环境失败的用例不写基线行() {
        repo.samples.add(sample(12L, "robot", 400, "FAIL", "ssh permission denied"));
        repo.samples.add(sample(12L, "robot", 300, "FAIL", "no route to host"));

        DurationBaselineService.RecalcResult result = service.recompute(PROJECT);
        assertEquals(1, result.skippedGroups());
        assertEquals(0, result.computed());
        assertTrue(repo.stored.isEmpty(),
                "写一行 p50=0 会让「阈值 0 秒」变成人人超时");
    }

    @Test
    void 样本不足标记_insufficient_且不判慢() {
        repo.stored.put("13|pytest", baseline(13L, "pytest", 2, 1000, 1000, 1000, true));
        DurationBaselineService.DurationVerdict verdict =
                service.judge(PROJECT, 13L, "pytest", 100_000, "PASS");
        assertEquals(DurationBaselineService.LEVEL_UNKNOWN, verdict.level());
        assertTrue(verdict.insufficient());
        assertFalse(verdict.slow(), "2 条样本的 P90 就是最大值，按它判慢必然误报");
    }

    @Test
    void 超过_P90_乘_1点5_判慢但不中断() {
        repo.stored.put("14|robot", baseline(14L, "robot", 20, 60_000, 90_000, 120_000, false));
        // 阈值 = 90000 × 1.5 = 135000ms；取 140s 必须判慢
        DurationBaselineService.DurationVerdict verdict =
                service.judge(PROJECT, 14L, "robot", 140_000, "PASS");
        assertEquals(DurationBaselineService.LEVEL_SLOW, verdict.level());
        assertEquals(135_000, verdict.thresholdMs());
        assertTrue(verdict.reason().contains("不中断"), verdict.reason());
    }

    @Test
    void 通过但快得离谱判可疑() {
        repo.stored.put("15|pytest", baseline(15L, "pytest", 30, 40_000, 60_000, 80_000, false));
        // P50×0.2 = 8000ms；取 3s 且 PASS → 可疑
        DurationBaselineService.DurationVerdict verdict =
                service.judge(PROJECT, 15L, "pytest", 3_000, "PASS");
        assertEquals(DurationBaselineService.LEVEL_SUSPICIOUS_FAST, verdict.level());
        assertTrue(verdict.reason().contains("人工抽查"), verdict.reason());
    }

    @Test
    void 失败用例跑得快不算可疑() {
        repo.stored.put("16|pytest", baseline(16L, "pytest", 30, 40_000, 60_000, 80_000, false));
        DurationBaselineService.DurationVerdict verdict =
                service.judge(PROJECT, 16L, "pytest", 3_000, "FAIL");
        assertEquals(DurationBaselineService.LEVEL_NORMAL, verdict.level(),
                "失败的用例本来就该提前退出，判可疑只会产生噪音");
    }

    @Test
    void 没有基线时给出_UNKNOWN_而不是正常() {
        DurationBaselineService.DurationVerdict verdict =
                service.judge(PROJECT, 99L, "robot", 1000, "PASS");
        assertEquals(DurationBaselineService.LEVEL_UNKNOWN, verdict.level());
        assertTrue(verdict.reason().contains("还没有时长基线"), verdict.reason());
    }

    @Test
    void 建议超时向上取整到五秒且不低于三十秒() {
        repo.stored.put("17|pytest", baseline(17L, "pytest", 30, 1000, 2000, 3000, false));
        // 2000 × 1.5 = 3000ms = 3s → 取整 5s → 但下限 30s
        assertEquals(30, service.suggestedTimeoutSeconds(PROJECT, 17L, "pytest"));

        repo.stored.put("18|pytest", baseline(18L, "pytest", 30, 60_000, 100_000, 120_000, false));
        // 100000 × 1.5 = 150000ms = 150s → 取整 150s
        assertEquals(150, service.suggestedTimeoutSeconds(PROJECT, 18L, "pytest"));
    }

    @Test
    void 内核是分组维度_RF_与_pytest_基线互不干扰() {
        for (int i = 0; i < 6; i++) {
            repo.samples.add(sample(20L, "robot", 10_000, "PASS", null));
            repo.samples.add(sample(20L, "pytest", 3_000, "PASS", null));
        }
        service.recompute(PROJECT);
        assertEquals(10_000L, repo.stored.get("20|robot").p50Ms());
        assertEquals(3_000L, repo.stored.get("20|pytest").p50Ms());
    }

    // ---------------------------------------------------------------- fakes

    private static CaseDurationBaselineRepository.DurationSample sample(long caseId, String kernel,
                                                                      long durationMs, String verdict,
                                                                      String failureMsg) {
        return new CaseDurationBaselineRepository.DurationSample(caseId, kernel, durationMs,
                verdict, failureMsg, Instant.now());
    }

    private static CaseDurationBaseline baseline(long caseId, String kernel, int samples,
                                                 long p50, long p90, long max,
                                                 boolean insufficient) {
        // P99 取 P90 与 max 之间的值，保持分位单调
        long p99 = p90 + (max - p90) / 2;
        return new CaseDurationBaseline(1L, PROJECT, caseId, kernel, samples, p50, p90, p99, max,
                0, insufficient, Instant.now().minusSeconds(86400), Instant.now(), Instant.now());
    }

    private static final class FakeRepository implements CaseDurationBaselineRepository {

        private final List<DurationSample> samples = new ArrayList<>();
        private final Map<String, CaseDurationBaseline> stored = new LinkedHashMap<>();

        @Override
        public List<DurationSample> samples(long projectId, Instant since) {
            return List.copyOf(samples);
        }

        @Override
        public void replaceAll(long projectId, List<CaseDurationBaseline> rows) {
            stored.clear();
            for (CaseDurationBaseline row : rows) {
                stored.put(row.caseId() + "|" + row.kernel(), row);
            }
        }

        @Override
        public List<CaseDurationBaseline> list(long projectId) {
            return List.copyOf(stored.values());
        }

        @Override
        public Optional<CaseDurationBaseline> find(long projectId, long caseId, String kernel) {
            return Optional.ofNullable(stored.get(caseId + "|" + kernel));
        }

        @Override
        public long countByProject(long projectId) {
            return stored.size();
        }

        /**
         * 定时任务用；本 fake 不测调度，返回空即可。补这个方法是因为接口新增抽象方法后
         * 所有实现（含 fake）都必须同步，否则整模块编译失败。
         */
        @Override
        public List<Long> projectsWithRecentRuns(Instant since) {
            return List.of();
        }

        /** 长跑估算用；本 fake 没有 caseCode 映射，全部走兜底值。 */
        @Override
        public java.util.Map<String, Long> p90ByCaseCode(long projectId, String kernel) {
            return java.util.Map.of();
        }
    }
}
