package com.qa.autotest.application.ops;

import com.qa.autotest.application.metrics.MetricsService;
import com.qa.autotest.application.repository.OpsRepository;
import com.qa.autotest.domain.ops.AlertChannel;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.AlertRule;
import com.qa.autotest.domain.ops.SloSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 告警评估与投递（FR-3.3-09 / EW33-09）。
 *
 * <p>规则 -> 条件 -> 去重 -> 投递 -> 恢复 五步；同一 {@code dedup_key} 只保留一条 FIRING 事件，
 * 冷却期内重触发只记录 {@code SUPPRESSED} 不再投递；条件恢复时关闭事件并投递恢复通知。
 * 通道故障不丢事件：事件先落库，投递状态单独记录，失败可解释。
 */
@Service
public class AlertService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一次巡检结果，直接用于告警演练与门禁判定。 */
    public record Evaluation(long projectId, int scanned, List<Long> fired, List<Long> resolved,
                             int suppressed, int deliveryFailures, Instant evaluatedAt) {
    }

    private record Condition(boolean triggered, double value, String summary) {
    }

    private record DeliveryResult(String status, String error, int attempts) {
        boolean failed() {
            return "FAILED".equals(status);
        }
    }

    private final OpsRepository repo;
    private final MetricsService metrics;
    private final BackupStatusService backups;
    private final AlertNotifier notifier;

    public AlertService(OpsRepository repo, MetricsService metrics, BackupStatusService backups,
                        AlertNotifier notifier) {
        this.repo = repo;
        this.metrics = metrics;
        this.backups = backups;
        this.notifier = notifier;
    }

    public Evaluation evaluate(long projectId) {
        return evaluate(projectId, Instant.now());
    }

    /** detectedAt 允许注入：演练时用「真实发生时间」，MTTD 才有意义。 */
    public Evaluation evaluate(long projectId, Instant detectedAt) {
        List<AlertRule> rules = repo.rules(projectId).stream().filter(AlertRule::enabled).toList();
        List<Long> fired = new ArrayList<>();
        List<Long> resolved = new ArrayList<>();
        int suppressed = 0;
        int deliveryFailures = 0;
        Instant now = Instant.now();

        for (AlertRule rule : rules) {
            Condition condition = condition(rule);
            String dedupKey = rule.eventType();
            Optional<AlertEvent> open = repo.findOpenEvent(projectId, dedupKey);
            if (open.isPresent()) {
                if (!condition.triggered()) {
                    if (repo.resolveEvent(open.get().id())) {
                        resolved.add(open.get().id());
                        deliver(projectId, rule, "RESOLVED",
                                "已恢复：" + condition.summary(), detectedAt, now);
                    }
                }
                continue;
            }
            if (!condition.triggered()) {
                continue;
            }
            long id = repo.insertEvent(projectId, rule.id(), rule.eventType(), dedupKey,
                    rule.severity(), condition.summary(), detectedAt, now);
            fired.add(id);
            Optional<AlertEvent> latest = repo.latestEvent(projectId, dedupKey);
            if (latest.isPresent() && latest.get().resolvedAt() != null
                    && latest.get().firedAt() != null
                    && latest.get().firedAt().plusSeconds(rule.cooldownMinutes() * 60L)
                    .isAfter(now)) {
                suppressed++;
                repo.markDelivery(id, "SUPPRESSED", 0,
                        "冷却期内重复触发（" + rule.cooldownMinutes() + " 分钟）");
                continue;
            }
            DeliveryResult delivery = deliver(projectId, rule, "FIRING", condition.summary(),
                    detectedAt, now);
            repo.markDelivery(id, delivery.status(), delivery.attempts(), delivery.error());
            if (delivery.failed()) {
                deliveryFailures++;
            }
        }
        return new Evaluation(projectId, rules.size(), List.copyOf(fired), List.copyOf(resolved),
                suppressed, deliveryFailures, now);
    }

    /** 手动发送测试告警，验证通道配置；不写事件表。 */
    public AlertNotifier.Result testChannel(long projectId, long channelId) {
        AlertChannel channel = repo.channels(projectId).stream()
                .filter(item -> item.id() == channelId).findFirst()
                .orElseThrow(() -> new com.qa.autotest.common.BusinessException(
                        com.qa.autotest.common.ErrorCode.NOT_FOUND, "告警通道不存在"));
        String secret = repo.channelSecret(projectId, channelId).orElse(null);
        Map<String, Object> payload = payload(projectId, "TEST", "P3", "告警通道连通性测试",
                Instant.now(), Instant.now(), "TEST");
        return notifier.send(channel, secret, payload);
    }

    public List<AlertEvent> events(long projectId, int limit) {
        return repo.events(projectId, limit);
    }

    public SloSnapshot.Daily latestSloDay(long projectId) {
        List<OpsRepository.SloDay> days = repo.sloDays(projectId, LocalDate.now(ZONE));
        if (days.isEmpty()) {
            return null;
        }
        OpsRepository.SloDay day = days.get(days.size() - 1);
        return new SloSnapshot.Daily(day.day(), day.availabilityPct(), day.p95Ms(),
                day.samples(), day.errorBudgetMinutes());
    }

    private Condition condition(AlertRule rule) {
        Instant since = Instant.now().minusSeconds(rule.windowMinutes() * 60L);
        switch (rule.eventType()) {
            case "RUN_FAILURE_RATE" -> {
                long[] outcome = repo.runOutcome(rule.projectId(), since);
                double rate = outcome[0] == 0 ? 0.0 : (double) outcome[1] / outcome[0] * 100;
                return new Condition(outcome[0] > 0 && rate >= rule.threshold(), rate,
                        "近 " + rule.windowMinutes() + " 分钟失败率 " + round(rate) + "%（"
                                + outcome[1] + "/" + outcome[0] + "）");
            }
            case "P95_LATENCY" -> {
                SloSnapshot.Daily day = latestSloDay(rule.projectId());
                int p95 = day == null ? 0 : day.p95Ms();
                return new Condition(day != null && p95 >= rule.threshold(), p95,
                        "今日 p95 " + p95 + "ms 达到阈值 " + rule.threshold() + "ms");
            }
            case "QUEUE_BACKLOG" -> {
                MetricsService.MetricsView view = metrics.snapshot(rule.projectId());
                int depth = view.queue().depth();
                return new Condition(depth >= rule.threshold(), depth,
                        "执行队列深度 " + depth + "，阈值 " + rule.threshold());
            }
            case "NODE_OFFLINE" -> {
                MetricsService.MetricsView view = metrics.snapshot(rule.projectId());
                int offline = view.nodes().offline();
                return new Condition(offline > rule.threshold(), offline,
                        "离线节点 " + offline + " 个，允许上限 " + rule.threshold());
            }
            case "BACKUP_STALE" -> {
                BackupStatusService.BackupStatus status = backups.latest();
                long age = status.present() ? status.ageMinutes() : -1;
                boolean stale = status.stale((long) rule.threshold());
                return new Condition(stale, age, status.present()
                        ? "最近备份演练已过去 " + age + " 分钟，阈值 " + rule.threshold() + " 分钟"
                        : "没有备份演练证据，按未演练告警");
            }
            default -> {
                return new Condition(false, 0, "未知规则类型 " + rule.eventType());
            }
        }
    }

    private DeliveryResult deliver(long projectId, AlertRule rule, String status, String summary,
                                   Instant detectedAt, Instant firedAt) {
        List<AlertChannel> channels = repo.channels(projectId).stream()
                .filter(AlertChannel::enabled).toList();
        if (channels.isEmpty()) {
            LOG.warn("告警无可用通道 project={} type={}", projectId, rule.eventType());
            return new DeliveryResult("DEGRADED", "未配置启用的告警通道", 0);
        }
        boolean anySent = false;
        boolean anyDegraded = false;
        int attempts = 0;
        String lastError = null;
        for (AlertChannel channel : channels) {
            String secret = repo.channelSecret(projectId, channel.id()).orElse(null);
            AlertNotifier.Result result = notifier.send(channel, secret,
                    payload(projectId, rule.eventType(), rule.severity(), summary,
                            detectedAt, firedAt, status));
            attempts++;
            if ("FAILED".equals(result.status())) {
                // 只对「投递失败」重试一次；DEGRADED 是配置缺失，重试不会变好。
                result = notifier.send(channel, secret,
                        payload(projectId, rule.eventType(), rule.severity(), summary,
                                detectedAt, firedAt, status));
                attempts++;
            }
            if ("SENT".equals(result.status())) {
                anySent = true;
            } else if ("DEGRADED".equals(result.status())) {
                anyDegraded = true;
                lastError = result.error();
            } else {
                lastError = result.error();
            }
        }
        String overall = anySent ? "SENT" : (anyDegraded ? "DEGRADED" : "FAILED");
        return new DeliveryResult(overall, lastError, attempts);
    }

    private static Map<String, Object> payload(long projectId, String eventType, String severity,
                                               String summary, Instant detectedAt, Instant firedAt,
                                               String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("severity", severity);
        payload.put("status", status);
        payload.put("projectId", projectId);
        payload.put("summary", summary);
        payload.put("detectedAt", detectedAt.toString());
        payload.put("firedAt", firedAt.toString());
        payload.put("mttdSeconds", Math.max(0, firedAt.getEpochSecond() - detectedAt.getEpochSecond()));
        return payload;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
