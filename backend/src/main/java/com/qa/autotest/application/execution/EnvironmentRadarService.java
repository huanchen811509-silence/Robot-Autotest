package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.EnvironmentRadarRepository;
import com.qa.autotest.application.repository.EnvironmentRadarRepository.FailureSample;
import com.qa.autotest.domain.execution.FailureClass;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 环境健康雷达（FR-v5.3-07 / W53-07）。
 *
 * <p>回答三个问题：<b>最近环境问题变多了吗？集中在哪几台设备？趋势是变好还是变坏？</b>
 *
 * <p><b>为什么不给「健康分」</b>：把失败占比、TopN、趋势合成一个 0~100 的分数看起来友好，
 * 但分数无法告诉人「该去修哪台机器」，而且一旦有人开始拿分数考核，它就立刻失去意义
 * （调整分母比修机器容易）。因此这里只给可核对的原始计数与比例，判断交给人。
 *
 * <p><b>分类口径</b>：直接用 {@link FailureClassifier} 对 {@code failure_msg} 分类，
 * 不另写一套关键字。雷达与执行期归因必须同源，否则会出现「雷达说是环境问题、
 * 执行记录说是断言失败」这种两边都对、但谁也说服不了谁的局面。
 *
 * <p><b>端点抽取是启发式的</b>：从失败原文里正则抓 {@code IP[:port]}。
 * 抓不到就返回空 TopN 并在 {@code endpointCoverage} 里说明覆盖率——
 * 让「没有端点信息」与「没有失败」在页面上长得不一样。
 */
@Service
public class EnvironmentRadarService {

    /** 样本上限：只用于看趋势，不用于查明细。 */
    static final int SAMPLE_LIMIT = 20_000;

    /** TopN 端点的 N。给 10 台：再多就没人在页面上看了。 */
    static final int TOP_ENDPOINTS = 10;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** {@code 10.202.54.201:22} / {@code 10.202.54.201}。 */
    private static final Pattern ENDPOINT = Pattern.compile(
            "\\b(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?\\b");

    private final EnvironmentRadarRepository repository;
    private final FailureClassifier classifier;
    private final Clock clock;

    public EnvironmentRadarService(EnvironmentRadarRepository repository,
                                   FailureClassifier classifier, Clock clock) {
        this.repository = repository;
        this.classifier = classifier;
        this.clock = clock;
    }

    /** 分类计数。 */
    public record CategoryCount(String category, int count, double percent) {
    }

    /** 故障端点计数。 */
    public record EndpointCount(String endpoint, int count) {
    }

    /** 按天趋势点。 */
    public record DayPoint(String day, int totalFailures, int envFailures, double envPercent) {
    }

    /**
     * 雷达结果。
     *
     * @param envRatioPercent  环境类失败占总失败的比例（百分比，保留一位小数）
     * @param endpointCoverage 能抽出端点的失败样本占比（百分比）；低说明失败原文里没带端点
     * @param truncated        样本是否被 {@value #SAMPLE_LIMIT} 截断（此时比例是「近似值」）
     */
    public record Radar(int windowDays, int totalFailures, int envFailures,
                        double envRatioPercent, List<CategoryCount> byCategory,
                        List<EndpointCount> topEndpoints, double endpointCoverage,
                        List<DayPoint> trend, boolean truncated) {
    }

    /** 近 N 天雷达。{@code days} 只接受 7 与 30（页面就这两个档，多给档位只会让人选错）。 */
    public Radar radar(long projectId, int days) {
        int window = days == 30 ? 30 : 7;
        Instant since = clock.instant().minus(Duration.ofDays(window));
        long actualTotal = repository.countFailures(projectId, since);
        List<FailureSample> samples = repository.recentFailures(projectId, since, SAMPLE_LIMIT);
        return summarize(samples, window, actualTotal);
    }

    /**
     * 聚合（纯函数，可单测）。
     *
     * @param actualTotal 真实失败总数；与 {@code samples.size()} 不等说明样本被截断
     */
    Radar summarize(List<FailureSample> samples, int windowDays, long actualTotal) {
        List<FailureSample> rows = samples == null ? List.of() : samples;
        Map<FailureClass, Integer> categories = new LinkedHashMap<>();
        Map<String, Integer> endpoints = new LinkedHashMap<>();
        Map<String, int[]> byDay = new LinkedHashMap<>();
        int envCount = 0;
        int withEndpoint = 0;

        for (FailureSample sample : rows) {
            FailureClass cls = classify(sample.failureMsg());
            categories.merge(cls, 1, Integer::sum);
            if (cls == FailureClass.ENV) {
                envCount++;
            }
            String endpoint = endpointOf(sample.failureMsg());
            if (endpoint != null) {
                withEndpoint++;
                endpoints.merge(endpoint, 1, Integer::sum);
            }
            String day = sample.at() == null ? null
                    : LocalDate.ofInstant(sample.at(), ZONE).format(DAY);
            if (day != null) {
                int[] slot = byDay.computeIfAbsent(day, ignored -> new int[2]);
                slot[0]++;
                if (cls == FailureClass.ENV) {
                    slot[1]++;
                }
            }
        }

        int total = rows.size();
        List<CategoryCount> byCategory = new ArrayList<>();
        categories.entrySet().stream()
                .sorted(Map.Entry.<FailureClass, Integer>comparingByValue().reversed())
                .forEach(entry -> byCategory.add(new CategoryCount(entry.getKey().name(),
                        entry.getValue(), percent(entry.getValue(), total))));

        List<EndpointCount> topEndpoints = endpoints.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_ENDPOINTS)
                .map(entry -> new EndpointCount(entry.getKey(), entry.getValue()))
                .toList();

        List<DayPoint> trend = byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> new DayPoint(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                        percent(entry.getValue()[1], entry.getValue()[0])))
                .toList();

        return new Radar(windowDays, total, envCount, percent(envCount, total), byCategory,
                topEndpoints, percent(withEndpoint, total), trend, actualTotal > total);
    }

    private FailureClass classify(String failureMsg) {
        try {
            return classifier.classify(new FailureClassifier.FailureInput(
                    false, failureMsg, null, null, null, null, true));
        } catch (RuntimeException e) {
            // 分类器出问题不能把整个雷达打挂；记 UNKNOWN 走 ASSERT 是错的，所以单列
            return FailureClass.ASSERT;
        }
    }

    /** 从失败原文抽端点；优先带端口的形态。 */
    static String endpointOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ENDPOINT.matcher(text);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group(2) == null
                    ? matcher.group(1) : matcher.group(1) + ":" + matcher.group(2);
            if (matcher.group(2) != null) {
                return candidate;   // 带端口的更具体，直接采用
            }
            if (best == null) {
                best = candidate;
            }
        }
        return best;
    }

    private static double percent(int part, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 1000.0 / total) / 10.0;
    }
}
