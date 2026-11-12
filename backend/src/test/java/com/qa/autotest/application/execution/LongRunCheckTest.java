package com.qa.autotest.application.execution;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长跑识别与窗口约束（FR-v5.3-07 / W53-08）。
 *
 * <p>只看两件事：<b>什么算长跑</b>、<b>长跑在不同窗口配置下被怎么处理</b>。
 * 后者最容易写错：没配窗口时「拒绝长跑」看起来严格，实际是用一条用户没开过的
 * 规则挡住他；正确做法是放行 + 提示。
 */
class LongRunCheckTest {

    /** 只有阈值配置的假仓库：本类不测拆单与台账。 */
    private static ProjectRunCoordinator coordinator(String threshold) {
        return new ProjectRunCoordinator(null, null, new FakeSettings(
                threshold == null ? java.util.Map.of()
                        : java.util.Map.of("long_run_threshold_s", threshold)), null, null);
    }

    @Test
    void 阈值默认三十分钟() {
        assertEquals(1800, coordinator(null).thresholdOf(1L));
    }

    @Test
    void 阈值配错回落到默认值() {
        assertEquals(1800, coordinator("abc").thresholdOf(1L), "配错不该抛，回默认");
        assertEquals(1800, coordinator("0").thresholdOf(1L), "0 秒没有业务意义，回默认");
        assertEquals(1800, coordinator("-5").thresholdOf(1L));
        assertEquals(600, coordinator("600").thresholdOf(1L));
    }

    @Test
    void 没有基线服务时不判长跑() {
        ProjectRunCoordinator.LongRunCheck check = coordinator(null).longRunOf(1L,
                List.of(new RunSplitter.PlannedCase("C-1", "robot", "a.robot", 0)));
        assertEquals(false, check.longRun(), "没有基线就如实说「判不了」，不能假装是短跑");
        assertEquals(0, check.estimatedSeconds());
    }

    @Test
    void 空用例列表不判长跑() {
        ProjectRunCoordinator.LongRunCheck check = coordinator(null).longRunOf(1L, List.of());
        assertEquals(false, check.longRun());
        assertEquals(0, check.estimatedSeconds());
    }

    @Test
    void 时长格式化给人看() {
        assertEquals("未知", ProjectRunCoordinator.duration(0));
        assertEquals("45s", ProjectRunCoordinator.duration(45));
        assertEquals("2m", ProjectRunCoordinator.duration(120));
        assertEquals("1h 2m", ProjectRunCoordinator.duration(3720));
        assertEquals("2h", ProjectRunCoordinator.duration(7200));
    }

    @Test
    void 长跑与短跑的判定只比阈值() {
        // 用真实的 DurationBaselineService + 空基线仓库，验证「无样本走兜底 180s/条」
        DurationBaselineService baselines = new DurationBaselineService(
                new EmptyBaselineRepository());
        ProjectRunCoordinator real = new ProjectRunCoordinator(null, null,
                new FakeSettings(java.util.Map.of()), null, baselines);

        // 3 条用例 × 180s 兜底 = 540s，阈值 900 → 不是长跑
        ProjectRunCoordinator.LongRunCheck shortRun = real.longRunOf(1L, cases(3));
        assertEquals(540, shortRun.estimatedSeconds());
        assertEquals(false, shortRun.longRun());
        assertEquals(0, shortRun.covered(), "全是兜底值，覆盖率应为 0");
        assertEquals(3, shortRun.fallback());

        // 阈值降到 300 → 同批量变长跑
        ProjectRunCoordinator low = new ProjectRunCoordinator(null, null,
                new FakeSettings(java.util.Map.of("long_run_threshold_s", "300")),
                null, baselines);
        assertTrue(low.longRunOf(1L, cases(3)).longRun());
    }

    @Test
    void 空基线的估算把覆盖率如实算成零() {
        DurationBaselineService baselines = new DurationBaselineService(
                new EmptyBaselineRepository());
        DurationBaselineService.Estimate estimate = baselines.estimateSeconds(1L, cases(10));
        assertEquals(10, estimate.total());
        assertEquals(0, estimate.covered());
        assertEquals(10, estimate.fallback());
        assertEquals(0.0, estimate.coveragePercent(), "全靠兜底时必须承认覆盖率为 0");
        assertEquals(1800, estimate.seconds(), "10 × 180s");
    }

    @Test
    void 空列表估算为零() {
        DurationBaselineService baselines = new DurationBaselineService(
                new EmptyBaselineRepository());
        DurationBaselineService.Estimate estimate = baselines.estimateSeconds(1L, List.of());
        assertEquals(0, estimate.total());
        assertEquals(0, estimate.seconds());
    }

    private static List<RunSplitter.PlannedCase> cases(int count) {
        List<RunSplitter.PlannedCase> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new RunSplitter.PlannedCase("C-" + i, "robot", "a.robot", 0));
        }
        return out;
    }

    /** 只有配置的假仓库。 */
    private static final class FakeSettings
            implements com.qa.autotest.application.repository.ProjectSettingRepository {

        private final java.util.Map<String, String> values;

        FakeSettings(java.util.Map<String, String> values) {
            this.values = java.util.Map.copyOf(values);
        }

        @Override
        public java.util.Optional<String> find(long projectId, String key) {
            return java.util.Optional.ofNullable(values.get(key));
        }

        @Override
        public java.util.Map<String, String> findAll(long projectId, String prefix) {
            return values;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            // 只读
        }

        @Override
        public void remove(long projectId, String key) {
            // 只读
        }
    }

    /** 无基线的仓储：验证兜底路径。 */
    private static final class EmptyBaselineRepository
            implements com.qa.autotest.application.repository.CaseDurationBaselineRepository {

        @Override
        public List<DurationSample> samples(long projectId, java.time.Instant since) {
            return List.of();
        }

        @Override
        public void replaceAll(long projectId,
                               List<com.qa.autotest.domain.execution.CaseDurationBaseline> rows) {
            // 不落库
        }

        @Override
        public List<com.qa.autotest.domain.execution.CaseDurationBaseline> list(long projectId) {
            return List.of();
        }

        @Override
        public java.util.Optional<com.qa.autotest.domain.execution.CaseDurationBaseline> find(
                long projectId, long caseId, String kernel) {
            return java.util.Optional.empty();
        }

        @Override
        public long countByProject(long projectId) {
            return 0;
        }

        @Override
        public List<Long> projectsWithRecentRuns(java.time.Instant since) {
            return List.of();
        }

        @Override
        public java.util.Map<String, Long> p90ByCaseCode(long projectId, String kernel) {
            return java.util.Map.of();
        }
    }
}
