package com.qa.autotest.application.plan;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5.33.0 / FR-v5.33-04：cron 最小间隔校验。
 *
 * <p>这几条把一个流传很广的误判钉死在测试里：6 段 cron 的**第一段是秒**，
 * 所以 {@code 0 * * * * *} 是「每分钟第 0 秒」（间隔 60 秒，合法），
 * 而 {@code * * * * * *} 才是「每秒」（间隔 1 秒，非法）。
 * 评估阶段一度把前者当成「每秒」，这里用可执行的断言纠正。
 */
class PlanCronValidationTest {

    /** 固定起点：判定只看相邻触发点的间隔，起点取哪天不影响结论，但固定后才可复现。 */
    private static final Instant FROM = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    @DisplayName("每秒表达式（* * * * * *）判为配置异常")
    void everySecondRejected() {
        String issue = CronSchedule.issue("* * * * * *", "Asia/Shanghai", FROM);
        assertNotNull(issue, "每秒调度必须被拒");
        assertTrue(issue.contains("1 秒"), issue);
    }

    @Test
    @DisplayName("间隔 59 秒判为异常，60 秒通过")
    void fiftyNineRejectedSixtyAccepted() {
        // */59 在 0..59 的秒域里命中 0 和 59：相邻两点要么差 59 秒、要么差 1 秒，都不足 60
        assertNotNull(CronSchedule.issue("*/59 * * * * *", "Asia/Shanghai", FROM),
                "59 秒间隔必须被拒");
        assertNull(CronSchedule.issue("0 * * * * *", "Asia/Shanghai", FROM),
                "每分钟（间隔 60 秒）是合法配置");
        // */1 在秒域等于「每一秒」，不是「只命中 0」——步长 1 就是全选
        assertNotNull(CronSchedule.issue("*/1 * * * * *", "Asia/Shanghai", FROM),
                "*/1 等价于每秒，必须被拒");
    }

    @Test
    @DisplayName("每小时 / 每天 / 每周都以间隔判定，不看字面长度")
    void coarserSchedulesAccepted() {
        assertNull(CronSchedule.issue("0 0 * * * *", "Asia/Shanghai", FROM));
        assertNull(CronSchedule.issue("0 0 3 * * *", "Asia/Shanghai", FROM));
        assertNull(CronSchedule.issue("0 0 3 * * MON", "Asia/Shanghai", FROM));
    }

    @Test
    @DisplayName("非法表达式与空值给出可读原因，不是 null")
    void invalidReasonsAreReadable() {
        assertTrue(CronSchedule.issue("0 0 3 * *", "Asia/Shanghai", FROM).contains("6 段"),
                "5 段表达式要说清需要 6 段");
        assertEquals("cron 不能为空", CronSchedule.issue("  ", "Asia/Shanghai", FROM));
        assertNotNull(CronSchedule.issue("0 0 3 * * *", "Mars/Base", FROM),
                "非法时区要被点名");
    }

    @Test
    @DisplayName("永不触发的表达式（2 月 30 日）判为异常，而不是当作合法")
    void neverFiringRejected() {
        assertNotNull(CronSchedule.issue("0 0 0 30 2 *", "Asia/Shanghai", FROM),
                "2 月 30 日永不触发，Spring 不报错但计划等于死配置");
    }

    @Test
    @DisplayName("将来三次预览与判定同源：合法表达式能给出 3 个递增触发点")
    void previewMatchesValidation() {
        CronSchedule schedule = CronSchedule.parse("0 0 3 * * *", "Asia/Shanghai");
        var times = schedule.nextTimes(FROM, 3);
        assertEquals(3, times.size());
        assertTrue(times.get(0).isBefore(times.get(1)));
        assertTrue(times.get(1).isBefore(times.get(2)));
        assertEquals(24 * 3600, times.get(1).getEpochSecond() - times.get(0).getEpochSecond(),
                "每天一次：相邻间隔必须正好 24 小时");
    }
}
