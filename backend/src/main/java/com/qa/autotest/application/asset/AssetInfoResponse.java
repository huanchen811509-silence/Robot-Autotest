package com.qa.autotest.application.asset;

import java.time.Instant;
import java.util.List;

/**
 * 连接与拓扑派生结果（v4.3.0 / FR-v4.3-03、FR-v4.3-04）。
 *
 * <p>一次返回三块：元信息、连接明细、拓扑。结构固定，GET 与手工 refresh 共用，
 * 前端可原地替换。{@code connections.items} 为全量，不分页、不截断（PRD N2）。
 *
 * <p>放在 {@code application} 而不是 {@code api}：派生在应用层完成，控制器只做协议转换；
 * 分层规则 R4 禁止 {@code application → api}（见 {@code LayerRulesTest}）。
 */
public record AssetInfoResponse(
        Instant generatedAt,
        Meta meta,
        Connections connections,
        Topology topology,
        boolean empty) {

    /** 缓存与数据来源元信息（PRD FR-v4.3-05 R5）。 */
    public record Meta(boolean cacheHit, String source, boolean expired, int ttlSeconds) {
    }

    /** 连接明细：一条 = 一条链路的两端。 */
    public record Connections(List<ConnectionItem> items, int count) {
    }

    /**
     * 连接一行：链路 + A 端（设备/网卡/接口）+ B 端 + 协议/速率/MTU/MAC/IP/端口状态。
     * 端点端口缺失时对应字段为 null，行仍然保留（不静默丢数据）。
     */
    public record ConnectionItem(
            Long linkId,
            String linkName,
            String linkType,
            String aDevice,
            String aNic,
            String aPort,
            String bDevice,
            String bNic,
            String bPort,
            String protocol,
            Integer speedGbps,
            Integer mtu,
            String macAddress,
            String ipAddress,
            String portStatus,
            boolean enabled) {
    }

    /** 三级拓扑：设备 → 网卡 → 接口，边为链路两端的接口。 */
    public record Topology(List<TopologyNode> nodes, List<TopologyEdge> edges, TopologyStats stats) {
    }

    public record TopologyNode(String id, String name, String type, String parent, boolean enabled) {
    }

    public record TopologyEdge(String from, String to, Long linkId, String linkType,
                               Integer speedGbps) {
    }

    public record TopologyStats(int deviceCount, int nicCount, int portCount, int linkCount) {
    }
}
