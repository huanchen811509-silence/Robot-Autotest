package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.EnvironmentRadarRepository;
import com.qa.autotest.application.repository.EnvironmentRadarRepository.FailureSample;
import com.qa.autotest.application.execution.EnvironmentRadarService.Radar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境健康雷达（FR-v5.3-07 / W53-07）。
 *
 * <p>判据四条：占比算对、分类与执行期同源、端点能抽出来且覆盖率诚实、样本截断要标出来。
 * 最后一条尤其重要——「看起来是全景、其实是抽样」是最容易误判的形态。
 */
class EnvironmentRadarServiceTest {

    private static final long PROJECT = 9L;

    private static EnvironmentRadarService service() {
        return new EnvironmentRadarService(new EmptyRepository(),
                new FailureClassifier(FailureRuleSet.defaults()), java.time.Clock.systemUTC());
    }

    private static FailureSample sample(String msg, String at) {
        return new FailureSample(1L, 1L, "robot", msg, Instant.parse(at));
    }

    @Test
    void 环境类失败占比按分类算() {
        List<FailureSample> samples = List.of(
                sample("ssh: connect to host 10.0.0.1 port 22: Connection refused", "2026-11-10T02:00:00Z"),
                sample("timed out after 30000ms", "2026-11-10T03:00:00Z"),
                sample("AssertionError: 100 != 200", "2026-11-11T02:00:00Z"),
                sample("期望 1 实际 2", "2026-11-11T03:00:00Z"));

        Radar radar = service().summarize(samples, 7, samples.size());
        assertEquals(4, radar.totalFailures());
        assertEquals(2, radar.envFailures(), "两条是连接与超时，属环境类");
        assertEquals(50.0, radar.envRatioPercent(), "占比要一位小数、按百分比");
    }

    @Test
    void 分类结果与执行期归因同源() {
        // 同一个分类器：雷达说 ENV，就得是真的 ENV（否则两边口径会互相打脸）
        Radar radar = service().summarize(List.of(
                sample("connect: Network is unreachable", "2026-11-10T02:00:00Z")), 7, 1);
        assertEquals("ENV", radar.byCategory().get(0).category());
    }

    @Test
    void 连接类错误都归环境类() {
        // 这几条早期漏在规则之外，会把环境问题判成断言失败（不重试 + 雷达低估）
        for (String message : List.of(
                "connect: Network is unreachable",
                "Host key verification failed.",
                "Connection closed by 10.0.0.1 port 22",
                "Write failed: Broken pipe",
                "ssh: connect to host 10.0.0.1 port 22: Connection timed out")) {
            Radar radar = service().summarize(List.of(
                    sample(message, "2026-11-10T02:00:00Z")), 7, 1);
            assertEquals("ENV", radar.byCategory().get(0).category(), "应判为 ENV：" + message);
        }
    }

    @Test
    void 无失败时占比为零而不是除零异常() {
        Radar radar = service().summarize(List.of(), 7, 0);
        assertEquals(0, radar.totalFailures());
        assertEquals(0.0, radar.envRatioPercent());
        assertEquals(0.0, radar.endpointCoverage());
        assertTrue(radar.byCategory().isEmpty());
        assertFalse(radar.truncated());
    }

    @Test
    void 端点抽取优先带端口且能去重合并() {
        List<FailureSample> samples = List.of(
                sample("ssh 10.202.54.201:22 refused", "2026-11-10T02:00:00Z"),
                sample("ssh 10.202.54.201:22 refused again", "2026-11-10T03:00:00Z"),
                sample("ping 10.202.54.202 timeout", "2026-11-10T04:00:00Z"));

        Radar radar = service().summarize(samples, 7, samples.size());
        assertEquals(2, radar.topEndpoints().size());
        assertEquals("10.202.54.201:22", radar.topEndpoints().get(0).endpoint());
        assertEquals(2, radar.topEndpoints().get(0).count());
        assertEquals(100.0, radar.endpointCoverage(), "三条都带 IP，覆盖率应为 100%");
    }

    @Test
    void 抽不到端点时覆盖率如实降低() {
        Radar radar = service().summarize(List.of(
                sample("AssertionError", "2026-11-10T02:00:00Z"),
                sample("ssh 10.0.0.1 refused", "2026-11-10T03:00:00Z")), 7, 2);
        assertEquals(50.0, radar.endpointCoverage());
        assertEquals(1, radar.topEndpoints().size());
    }

    @Test
    void topN_最多十条() {
        List<FailureSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(sample("ssh 10.0.0." + i + " refused", "2026-11-10T02:00:00Z"));
        }
        Radar radar = service().summarize(samples, 7, samples.size());
        assertEquals(10, radar.topEndpoints().size(), "TopN 给 10 台，多了没人看");
    }

    @Test
    void 趋势按天分组且同一天合并() {
        List<FailureSample> samples = List.of(
                sample("ssh: connect to host 10.0.0.1 port 22: Connection refused",
                        "2026-11-10T02:00:00Z"),   // 北京 10:00
                sample("ssh: connect to host 10.0.0.1 port 22: Connection refused",
                        "2026-11-10T03:00:00Z"),
                sample("AssertionError", "2026-11-11T02:00:00Z"));

        Radar radar = service().summarize(samples, 7, samples.size());
        assertEquals(2, radar.trend().size());
        assertEquals("2026-11-10", radar.trend().get(0).day());
        assertEquals(2, radar.trend().get(0).totalFailures());
        assertEquals(100.0, radar.trend().get(0).envPercent());
        assertEquals("2026-11-11", radar.trend().get(1).day());
        assertEquals(0.0, radar.trend().get(1).envPercent());
    }

    @Test
    void 按天分组用北京时间而非_UTC() {
        // 2026-11-09T17:00:00Z = 北京 11-10 01:00，必须归到 11-10
        Radar radar = service().summarize(List.of(
                sample("Connection refused", "2026-11-09T17:00:00Z")), 7, 1);
        assertEquals("2026-11-10", radar.trend().get(0).day(),
                "按 UTC 分天会让夜间执行的失败算到前一天");
    }

    @Test
    void 样本被截断时明确标出() {
        Radar radar = service().summarize(List.of(
                sample("Connection refused", "2026-11-10T02:00:00Z")), 7, 500);
        assertTrue(radar.truncated(), "实际 500 条只拿到 1 条，必须标 truncated");
        assertEquals(1, radar.totalFailures(), "totalFailures 是本次样本数");
    }

    @Test
    void 端点正则的边界情形() {
        assertNull(EnvironmentRadarService.endpointOf(null));
        assertNull(EnvironmentRadarService.endpointOf("  "));
        assertNull(EnvironmentRadarService.endpointOf("没有 IP 的失败"));
        assertEquals("10.0.0.1", EnvironmentRadarService.endpointOf("ping 10.0.0.1 lost"));
        assertEquals("10.0.0.1:8081", EnvironmentRadarService.endpointOf("curl 10.0.0.1:8081/x"));
    }

    /** 只测聚合，不测取数。 */
    private static final class EmptyRepository implements EnvironmentRadarRepository {

        @Override
        public List<FailureSample> recentFailures(long projectId, Instant since, int limit) {
            return List.of();
        }

        @Override
        public long countFailures(long projectId, Instant since) {
            return 0;
        }
    }
}
