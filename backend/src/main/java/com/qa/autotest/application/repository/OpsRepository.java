package com.qa.autotest.application.repository;

import com.qa.autotest.domain.ops.AlertChannel;
import com.qa.autotest.domain.ops.AlertEvent;
import com.qa.autotest.domain.ops.AlertRule;
import com.qa.autotest.domain.ops.ReleaseRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 运维闭环持久化契约（FR-3.3-09..11）。 */
public interface OpsRepository {

    record SloDay(LocalDate day, double availabilityPct, int p95Ms, int samples,
                  int errorBudgetMinutes) {
    }

    List<AlertChannel> channels(long projectId);

    AlertChannel saveChannel(long projectId, String kind, String target, String secret,
                             boolean enabled, Long actorId);

    boolean deleteChannel(long projectId, long id);

    /** 只取通道密钥用于签名；响应体与日志都不得携带该值。 */
    Optional<String> channelSecret(long projectId, long id);

    List<AlertRule> rules(long projectId);

    AlertRule saveRule(long projectId, String eventType, double threshold, int windowMinutes,
                       int cooldownMinutes, String severity, boolean enabled, Long actorId);

    boolean deleteRule(long projectId, long id);

    Optional<AlertEvent> findOpenEvent(long projectId, String dedupKey);

    /** 同 dedup_key 的最近一条事件（含已恢复），用于冷却期去重。 */
    Optional<AlertEvent> latestEvent(long projectId, String dedupKey);

    /** 开了告警规则的项目列表，供定时巡检遍历。 */
    List<Long> projectsWithRules();

    long insertEvent(long projectId, Long ruleId, String eventType, String dedupKey,
                     String severity, String summary, Instant detectedAt, Instant firedAt);

    boolean markDelivery(long id, String status, int attempts, String lastError);

    boolean resolveEvent(long id);

    List<AlertEvent> events(long projectId, int limit);

    void upsertSloDay(long projectId, LocalDate day, double availabilityPct, int p95Ms,
                      int samples, int errorBudgetMinutes);

    List<SloDay> sloDays(long projectId, LocalDate fromDay);

    /** 最近 N 天运行成败：{总数, 失败数}，用于告警规则与 SLO 兜底。 */
    long[] runOutcome(long projectId, Instant since);

    long offlineNodeCount(long projectId);

    long insertRelease(String version, String status, String note);

    List<ReleaseRecord> releases(int limit);

    Optional<ReleaseRecord> latestRelease();
}
