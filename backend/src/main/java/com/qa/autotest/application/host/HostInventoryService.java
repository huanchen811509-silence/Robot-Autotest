package com.qa.autotest.application.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.asset.CredentialService;
import com.qa.autotest.application.iam.AuditService;
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
    private final NetworkDeviceCollector networkCollector;
    private final BmcRedfishCollector bmcCollector;
    private final HostInventoryRepository repository;
    private final ObjectMapper mapper;
    private final CredentialService credentials;
    private final AuditService audit;

    public HostInventoryService(HostInventoryCollector collector,
                                NetworkDeviceCollector networkCollector,
                                BmcRedfishCollector bmcCollector,
                                HostInventoryRepository repository,
                                ObjectMapper mapper,
                                CredentialService credentials,
                                AuditService audit) {
        this.collector = collector;
        this.networkCollector = networkCollector;
        this.bmcCollector = bmcCollector;
        this.repository = repository;
        this.mapper = mapper;
        this.credentials = credentials;
        this.audit = audit;
    }

    /** 采集 + 入库，返回落库后的记录（含 id，前端据此拉详情）。 */
    public HostInventoryRecord collect(long projectId, String host, Long deviceId, Long operatorId) {
        return collect(projectId, host, deviceId, operatorId, null, null, false);
    }

    /**
     * 采集 + 入库（FR-v4.2-03）。
     *
     * <p>远程主机的三道门都在这层：必须是项目成员（接口层已判）、必须显式二次确认、
     * 凭据必须可执行取用（CREDENTIAL:USE）。本机采集不需要凭据，历史调用保持四参不变。
     * 成功与失败都落审计，detail 里的 host 是掩码后的。
     */
    public HostInventoryRecord collect(long projectId, String host, Long deviceId, Long operatorId,
                                       String username, Long credentialId, boolean confirmed) {
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "采集目标 host 不能为空");
        }
        String target = host.trim();
        String detail = "host=" + maskHost(target) + "; remote=" + (credentialId != null);
        try {
            HostAccess access = accessOf(projectId, target, username, credentialId, confirmed);
            HostInventory inventory = collectByChannel(target, deviceId, access);
            detail = detail + "; channel=" + (access == null ? "local" : access.channel());
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
            HostInventoryRecord saved = repository.find(projectId, id).orElseThrow(
                    () -> new BusinessException(ErrorCode.INTERNAL_ERROR, "采集结果写入后读不回来"));
            audit.record("host.inventory.collect", "device_host_spec", String.valueOf(id),
                    detail + "; status=" + saved.status());
            return saved;
        } catch (BusinessException ex) {
            audit.recordFailure("host.inventory.collect", "device_host_spec", "0",
                    detail + "; " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * 按凭据类型选采集通道（v4.12.0 / FR-v4.12-05）。
     *
     * <p>`access == null` 是本机：只有 Linux 采集器认识本机，另外两条通道都需要网络对端。
     * Linux 分支**仍然调用原来那个采集器**，一行不改 —— 8 台现场主机与 v2.7 双内核演示
     * 都是照它的形状验证过的，为交换机改造它等于自伤已验证路径。
     */
    private HostInventory collectByChannel(String host, Long deviceId, HostAccess access) {
        if (access == null) {
            return collector.collect(host, deviceId, null);
        }
        return switch (access.channel()) {
            case "network" -> networkCollector.collect(host, deviceId, access);
            case "bmc" -> bmcCollector.collect(host, deviceId, access);
            default -> collector.collect(host, deviceId, access);
        };
    }

    /**
     * 决定这次采集走哪条通道。返回 null 表示本机（不需要凭据）。
     *
     * <p>本机判定先于凭据校验：给 localhost 传凭据不该被要求二次确认，也不该真去 SSH。
     */
    private HostAccess accessOf(long projectId, String target, String username, Long credentialId,
                                boolean confirmed) {
        if (LocalHostCommandRunner.isLocal(target)) {
            return null;
        }
        if (credentialId == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "远程主机采集需要传 credentialId（凭据库里的 SSH 口令或私钥）");
        }
        if (!confirmed) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "远程主机采集需要二次确认：请求需带 confirm=true");
        }
        CredentialService.Material material = credentials.resolveMaterial(projectId, credentialId);
        String user = username == null || username.isBlank() ? material.username() : username.trim();
        if (user == null || user.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "凭据未配置 SSH 账号，请在请求里传 username");
        }
        return new HostAccess(projectId, user, credentialId, material.secret(),
                material.credType(), material.connectOptions());
    }

    /** 审计里的主机掩码：IPv4 保留首尾段，主机名只留前两位，避免日志里成片泄漏内网资产。 */
    static String maskHost(String host) {
        if (host == null || host.isBlank()) {
            return "***";
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            return parts[0] + ".***.***." + parts[3];
        }
        int keep = Math.min(2, host.length());
        return host.substring(0, keep) + "***";
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
