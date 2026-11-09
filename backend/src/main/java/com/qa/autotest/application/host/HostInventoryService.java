package com.qa.autotest.application.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.HostInventoryRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.host.HostInventory;
import com.qa.autotest.domain.host.HostInventoryItem;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 主机清单服务（FR-v3.11-01）。
 *
 * <p>三件事各归各位：
 * <ul>
 *   <li>采集只由**显式接口**触发（判据：不在后台自动跑）。没有定时任务、没有启动预热；</li>
 *   <li>入库只追加（{@link HostInventoryRepository} 没有 update）：采集是历史时点，
 *       允许重复采集同一台机器，形成可 diff 的时间线；</li>
 *   <li>摘要列（网口数 / RoCE 口数 / 最高速率）在写入时从清单里算出来，
 *       列表页不必解析 JSON——但**详情页仍以 JSON 为准**，摘要列只是索引。</li>
 * </ul>
 */
@Service
public class HostInventoryService {

    private final HostInventoryCollector collector;
    private final HostInventoryRepository repository;
    private final ObjectMapper mapper;

    public HostInventoryService(HostInventoryCollector collector,
                                HostInventoryRepository repository,
                                ObjectMapper mapper) {
        this.collector = collector;
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 采集 + 入库，返回落库后的记录（含 id，前端据此拉详情）。 */
    public HostInventoryRecord collect(long projectId, String host, Long deviceId, Long operatorId) {
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "采集目标 host 不能为空");
        }
        HostInventory inventory = collector.collect(host.trim(), deviceId);
        String json;
        try {
            json = mapper.writeValueAsString(inventory);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "序列化主机清单失败：" + ex.getMessage());
        }
        HostInventoryRecord record = new HostInventoryRecord(0L, projectId, deviceId, inventory.host(),
                inventory.collectedAt(), inventory.status(), json, sha256(json),
                countOf(inventory, "NIC"), countOf(inventory, "ROCE"), maxRoceGbps(inventory));
        long id = repository.insert(record, operatorId);
        return repository.find(projectId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.INTERNAL_ERROR, "采集结果写入后读不回来"));
    }

    public List<HostInventoryRecord> list(long projectId, Long deviceId, int limit) {
        return repository.list(projectId, deviceId, limit);
    }

    public HostInventoryRecord find(long projectId, long id) {
        return repository.find(projectId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "采集记录不存在: " + id));
    }

    /** 某一类的条目数（网口 / RoCE 端口）；没采到算 0，不抛。 */
    private static int countOf(HostInventory inventory, String category) {
        return inventory.items().stream()
                .filter(item -> category.equals(item.category()))
                .filter(HostInventoryItem::collected)
                .mapToInt(item -> item.value() instanceof List<?> list ? list.size() : 0)
                .sum();
    }

    /** 最高速率：只取解析出来的 `rateGbps`，解析不出就是 null（判据：不写 0 冒充）。 */
    private static Integer maxRoceGbps(HostInventory inventory) {
        Integer max = null;
        for (HostInventoryItem item : inventory.items()) {
            if (!"ROCE".equals(item.category()) || !item.collected()) {
                continue;
            }
            if (!(item.value() instanceof List<?> ports)) {
                continue;
            }
            for (Object port : ports) {
                if (!(port instanceof Map<?, ?> map)) {
                    continue;
                }
                Object gbps = map.get("rateGbps");
                if (gbps instanceof Number number) {
                    int value = number.intValue();
                    if (max == null || value > max) {
                        max = value;
                    }
                }
            }
        }
        return max;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，走不到这里；真走不到也不该让采集失败
            return null;
        }
    }

    /** 供接口层做「这一条是不是刚刚采的」判断（时间戳可能为 null 的旧数据）。 */
    public static Instant collectedAtOf(HostInventoryRecord record) {
        return record.collectedAt() == null ? Instant.EPOCH : record.collectedAt();
    }
}
