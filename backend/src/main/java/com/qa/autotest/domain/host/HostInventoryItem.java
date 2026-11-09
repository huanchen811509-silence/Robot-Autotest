package com.qa.autotest.domain.host;

import java.time.Instant;

/**
 * 清单里的**一项**（FR-v3.11-01）。
 *
 * <p>设计要点全在「拿不到时怎么表示」上：
 * <ul>
 *   <li>{@code value} 为空 + {@code reason} 有值 = **未采集到**，界面照原样显示原因；</li>
 *   <li>不允许用 {@code "0"} / {@code "未知"} / {@code "-"} 之类的占位值冒充采集结果——
 *       判据③明确「不填假值」。占位值最坏的地方不是难看，而是事后无法与真值区分；</li>
 *   <li>每项都带 {@code command} 与 {@code collectedAt}（判据②）：看的人要能自己复核
 *       「这个数是怎么来的、什么时候的」。</li>
 * </ul>
 *
 * @param category   10 类之一：OS / KERNEL / CPU / MEMORY / DISK / GPU / NIC / IP / ROCE / ROUTE
 * @param label      人类可读的分类名（中文，直接进报告）
 * @param command    实际下发的只读命令（白名单内，原样保留）
 * @param collectedAt 该项采集时刻
 * @param value      采集到的结构化内容；未采到为 null
 * @param reason     未采到的原因；采到为 null
 */
public record HostInventoryItem(String category,
                                String label,
                                String command,
                                Instant collectedAt,
                                Object value,
                                String reason) {

    /** 该项是否有可用值（判据③的判定入口：界面与机检都读这个，不各判一套）。 */
    public boolean collected() {
        return value != null && (reason == null || reason.isBlank());
    }

    public static HostInventoryItem ok(String category, String label, String command,
                                       Instant collectedAt, Object value) {
        return new HostInventoryItem(category, label, command, collectedAt, value, null);
    }

    public static HostInventoryItem missing(String category, String label, String command,
                                            Instant collectedAt, String reason) {
        return new HostInventoryItem(category, label, command, collectedAt, null,
                reason == null || reason.isBlank() ? "未采集到（未给出原因）" : reason);
    }
}
