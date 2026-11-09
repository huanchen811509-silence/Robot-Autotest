package com.qa.autotest.domain.host;

import java.time.Instant;
import java.util.List;

/**
 * 一台主机的清单（FR-v3.11-01）：一次采集 = 一个时点的整体事实。
 *
 * <p>整体性是有意义的：{@link #items} 全部共享同一个 {@link #collectedAt} 起点，
 * 所以报告里不会出现「CPU 是 10:00 采的、磁盘是昨天的」这种拼接事实。
 * 每项自己的 {@code collectedAt} 是这一项命令真正执行的时刻（同一批命令仍在毫秒级差异内）。
 *
 * @param status OK / PARTIAL / FAILED —— 由「有值项数」推出，不由采集方随意填
 */
public record HostInventory(String host,
                            Long deviceId,
                            Instant collectedAt,
                            String status,
                            List<HostInventoryItem> items) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    /** 10 类的固定顺序（判据①按这个顺序机检，界面也按它排）。 */
    public static final List<String> CATEGORIES = List.of(
            "OS", "KERNEL", "CPU", "MEMORY", "DISK", "GPU", "NIC", "IP", "ROCE", "ROUTE");

    public HostInventory {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 采到项的个数。 */
    public long collectedCount() {
        return items.stream().filter(HostInventoryItem::collected).count();
    }

    /**
     * 由项数推状态：全采到 OK、一项都没采到 FAILED、其余 PARTIAL。
     *
     * <p>刻意不让调用方自己传状态：「采集器觉得成功」和「真的采到了」是两件事，
     * 状态只能由结果算出来。
     */
    public static String statusOf(List<HostInventoryItem> items) {
        long ok = items.stream().filter(HostInventoryItem::collected).count();
        if (ok == 0) {
            return STATUS_FAILED;
        }
        return ok == items.size() ? STATUS_OK : STATUS_PARTIAL;
    }
}
