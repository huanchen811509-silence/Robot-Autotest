package com.qa.autotest.application.execution;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 执行窗口（FR-v5.2-03 / 验收 B7 / W52-04）。
 *
 * <p>台架是共享资源：白天有人做手工调试、跑别的项目，自动化只能在夜间窗口里跑。
 * 窗口不是「建议」，是准入条件——窗口外发起必须被拒绝并说清下一次可用的时间点，
 * 而不是静默排队到天亮。
 *
 * <p><b>跨零点必须支持</b>：真实窗口基本都是 {@code 23:00 ~ 06:00} 这种形态。
 * 把它当成「开始 &lt; 结束」的普通区间会得到「永远关闭」，然后在第一个夜班就被发现。
 *
 * <p>{@code exclusive=true} 表示窗口内该项目独占台架：同一时刻只允许一个 run。
 * 与执行队列的并发控制是两件事——队列管「同时跑几个」，独占管「能不能有第二个」。
 */
public record ExecutionWindow(LocalTime start, LocalTime end, boolean exclusive) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 无窗口限制（默认）：任何时刻都可发起。 */
    public static final ExecutionWindow ALWAYS = new ExecutionWindow(null, null, false);

    /**
     * 解析配置值。
     *
     * @param start        {@code HH:mm}，可空
     * @param end          {@code HH:mm}，可空
     * @param exclusiveRaw 字符串形式的布尔值（KV 表里都是文本）
     * @throws IllegalArgumentException 格式不对。配置错了必须显式报错——静默回落到
     *                                  「全天可跑」会让夜班窗口形同虚设，而且没人会去看。
     */
    public static ExecutionWindow of(String start, String end, String exclusiveRaw) {
        boolean exclusive = parseBoolean(exclusiveRaw);
        LocalTime startTime = parseTime(start, "window_start");
        LocalTime endTime = parseTime(end, "window_end");
        if (startTime == null && endTime == null) {
            return new ExecutionWindow(null, null, exclusive);
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException(
                    "执行窗口起止时间必须同时配置（window_start / window_end，只有一端等于没有窗口）："
                            + "start=" + start + ", end=" + end);
        }
        if (startTime.equals(endTime)) {
            throw new IllegalArgumentException(
                    "执行窗口起止时间相同（" + format(startTime) + "）：按 0 长度窗口处理会永远禁止执行，"
                            + "若想全天可跑请把两端都留空");
        }
        return new ExecutionWindow(startTime, endTime, exclusive);
    }

    /** 是否配置了时间窗（两端都空 = 不限）。 */
    public boolean timeBounded() {
        return start != null && end != null;
    }

    /**
     * 指定时刻是否在窗口内。
     *
     * <p>区间左闭右开：{@code 23:00} 在内、{@code 06:00} 不在内。右端闭会让
     * 「06:00 整发起」和「05:59:59 发起」同时成立，两个 run 争同一台机器。
     */
    public boolean openAt(LocalTime now) {
        if (!timeBounded()) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨零点：23:00~06:00 = [23:00, 24:00) ∪ [00:00, 06:00)
        return !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * 下一次窗口开启的相对描述，用于拒绝时的提示。
     *
     * <p>只算时间不算日期：窗口按天循环，跨零点后「今天 23:00」与「明天 23:00」
     * 对用户的操作没有区别，给出时刻比给出日期更不容易误读。
     */
    public String nextOpening() {
        if (!timeBounded()) {
            return "随时可执行（未配置执行窗口）";
        }
        return "下一次窗口开启：" + format(start) + "（窗口 " + describe() + "）";
    }

    /** 一行说明，直接进页面与错误消息。 */
    public String describe() {
        if (!timeBounded()) {
            return "不限时间";
        }
        return format(start) + "–" + format(end) + (start.isAfter(end) ? "（跨零点）" : "")
                + (exclusive ? " · 独占" : "");
    }

    private static String format(LocalTime time) {
        return time.format(HHMM);
    }

    private static LocalTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalTime.parse(value, HHMM);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 必须是 HH:mm 形态（如 23:00），当前为 " + raw);
        }
    }

    /**
     * 布尔解析放宽到 {@code 1/0}、{@code yes/no}、{@code on/off}：
     * KV 表里的值可能来自界面、curl 或导入脚本，形态不统一。
     * 无法识别时按 {@code false}——独占是更强约束，猜错方向会让本该并行的批次互相阻塞。
     */
    private static boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            default -> false;
        };
    }
}
