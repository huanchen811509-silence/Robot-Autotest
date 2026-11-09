package com.qa.autotest.domain.host;

import java.time.Instant;

/**
 * 已入库的一次采集（FR-v3.11-01）。
 *
 * <p>{@code specJson} 是 {@link HostInventory} 的原始序列化结果，接口原样返回给前端：
 * 10 类里的每项都带命令、时间、值或原因，前端只负责渲染，不再二次加工——
 * 二次加工会让「页面上看到的」与「证据文件里的」出现两套事实。
 *
 * @param nicCount    网口数（提列，供列表筛选）
 * @param rocePorts   RoCE 端口数
 * @param roceMaxGbps RoCE 最高速率；没采到为 null（不写 0）
 */
public record HostInventoryRecord(long id,
                                  long projectId,
                                  Long deviceId,
                                  String host,
                                  Instant collectedAt,
                                  String status,
                                  String specJson,
                                  String specHash,
                                  int nicCount,
                                  int rocePorts,
                                  Integer roceMaxGbps) {
}
