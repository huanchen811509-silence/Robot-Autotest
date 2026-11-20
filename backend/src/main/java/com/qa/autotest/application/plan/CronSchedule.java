package com.qa.autotest.application.plan;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.support.CronExpression;

/**
 * cron 解析与时间推进（PLN-01）。用 Spring 自带 {@link CronExpression}，不引入 Quartz。
 *
 * <p>三点硬约定（DEV-PLAN §2.3）：
 * <ul>
 *   <li>只支持 6 段（秒 分 时 日 月 周），<b>不支持年字段</b>；</li>
 *   <li>时区按计划自身 timezone 计算，默认 {@code Asia/Shanghai}，<b>不读 systemDefault</b>；</li>
 *   <li>非法表达式在保存时拒绝，错误信息带中文前缀。</li>
 * </ul>
 */
public final class CronSchedule {

    private final CronExpression expression;
    private final ZoneId zone;

    private CronSchedule(CronExpression expression, ZoneId zone) {
        this.expression = expression;
        this.zone = zone;
    }

    public static CronSchedule parse(String cron, String timezone) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("时区非法: " + timezone);
        }
        try {
            return new CronSchedule(CronExpression.parse(cron.trim()), zone);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cron 表达式非法（需 6 段：秒 分 时 日 月 周，不支持年字段）: "
                    + cron + "；" + ex.getMessage());
        }
    }

    public static boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            CronExpression.parse(cron.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** 两次相邻触发点的最小间隔（秒）。低于它等价于「配置成每秒跑」，属于事故级配置。 */
    public static final int MIN_INTERVAL_SECONDS = 60;

    /**
     * 判定 cron 的配置问题（v5.33.0 / FR-v5.33-04）。
     *
     * @return {@code null} = 可用；否则是给用户看的中文原因
     */
    public static String issue(String cron, String timezone) {
        return issue(cron, timezone, Instant.now());
    }

    /**
     * 同上，但以 {@code from} 为起点判定间隔 —— 只有这样才能在单测里稳定复现，
     * 不必依赖「跑测试那一刻」的时间。
     */
    public static String issue(String cron, String timezone, Instant from) {
        if (cron == null || cron.isBlank()) {
            return "cron 不能为空";
        }
        CronSchedule schedule;
        try {
            schedule = parse(cron, timezone);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        Instant cursor = from == null ? Instant.now() : from;
        Instant first = schedule.nextAfter(cursor);
        if (first == null) {
            // 永不触发（如 2 月 30 日）：Spring 不报错，但计划等于死配置
            return "cron 永不触发：" + cron;
        }
        Instant second = schedule.nextAfter(first);
        if (second == null) {
            return "cron 只触发一次，不能作为周期计划：" + cron;
        }
        long gap = java.time.Duration.between(first, second).getSeconds();
        if (gap < MIN_INTERVAL_SECONDS) {
            return "cron 触发间隔 " + gap + " 秒，低于最小值 " + MIN_INTERVAL_SECONDS
                    + " 秒（形如 `0 * * * * *` 是每分钟，`* * * * * *` 才是每秒）";
        }
        return null;
    }

    public ZoneId zone() {
        return zone;
    }

    /** 严格晚于 {@code from} 的下一个触发点。 */
    public Instant nextAfter(Instant from) {
        ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(from, zone));
        return next == null ? null : next.toInstant();
    }

    /**
     * 从 {@code from} 起（不含）返回 {@code count} 个触发点。
     * 表达式永不触发（如 2 月 30 日）时返回已得到的部分，不抛异常。
     */
    public List<Instant> nextTimes(Instant from, int count) {
        List<Instant> times = new ArrayList<>(Math.max(count, 0));
        Instant cursor = from;
        for (int i = 0; i < count; i++) {
            Instant next = nextAfter(cursor);
            if (next == null) {
                break;
            }
            times.add(next);
            cursor = next;
        }
        return List.copyOf(times);
    }

    /**
     * 统计 {@code (fromExclusive, toInclusive]} 区间内的触发点个数，最多数到 {@code cap}。
     *
     * <p>用于判定应用停机错过了几个触发点；{@code cap} 防止停机时间过长时循环过久，
     * 判定「错过」只需要知道 ≥2，不需要精确总数。
     */
    public int countFirePoints(Instant fromExclusive, Instant toInclusive, int cap) {
        if (fromExclusive == null || toInclusive == null || cap <= 0) {
            return 0;
        }
        int count = 0;
        Instant cursor = fromExclusive;
        while (count < cap) {
            Instant next = nextAfter(cursor);
            if (next == null || next.isAfter(toInclusive)) {
                break;
            }
            count++;
            cursor = next;
        }
        return count;
    }
}
