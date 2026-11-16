package com.qa.autotest.application.env;

import com.qa.autotest.common.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 拓扑三源分级（v5.15.0 / FR-v5.15-05~07）。
 *
 * <p>「实测盖规划」的落地口径：MEASURED 行与 PLANNED 行**同时保留**，
 * 判定时按 confidence 取优先级，绝不删改规划行——规划是人的意图，实测是事实，
 * 两者都属证据，覆盖掉任何一份都会让后续无法复盘「当初为什么这么设计」。
 */
@Service
public class TopologyTrustService {

    public static final String CONFIDENCE_MEASURED = "MEASURED";
    public static final String CONFIDENCE_PLANNED = "PLANNED";
    public static final String SOURCE_LLDP = "LLDP";
    public static final String SOURCE_MANUAL = "MANUAL";

    public record Bucket(String confidence, String source, long count) {
    }

    public record TrustView(List<Bucket> buckets, long measured, long planned,
                            long overriddenPairs, String priority) {
    }

    public record Neighbor(Long aDeviceId, Long bDeviceId, String name) {
    }

    public record ImportResult(int imported, int skipped, long plannedKept) {
    }

    private final JdbcTemplate jdbc;

    public TopologyTrustService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TrustView trust(long projectId) {
        String groups = "SELECT confidence, source, COUNT(*) FROM link WHERE project_id = ? "
                + "GROUP BY confidence, source ORDER BY confidence, source";
        TenantGuard.requireProjectScope(groups, "link");
        List<Bucket> buckets = jdbc.query(groups,
                (rs, n) -> new Bucket(rs.getString("confidence"), rs.getString("source"),
                        rs.getLong(3)), projectId);
        long measured = buckets.stream().filter(b -> CONFIDENCE_MEASURED.equals(b.confidence()))
                .mapToLong(Bucket::count).sum();
        long planned = buckets.stream().filter(b -> CONFIDENCE_PLANNED.equals(b.confidence()))
                .mapToLong(Bucket::count).sum();
        String pairs = "SELECT COUNT(*) FROM link p JOIN link m "
                + "ON p.project_id = m.project_id AND p.a_device_id = m.a_device_id "
                + "AND p.b_device_id = m.b_device_id "
                + "WHERE p.project_id = ? AND p.confidence = 'PLANNED' "
                + "AND m.confidence = 'MEASURED'";
        TenantGuard.requireProjectScope(pairs, "link");
        Long overridden = jdbc.queryForObject(pairs, Long.class, projectId);
        return new TrustView(buckets, measured, planned, overridden == null ? 0 : overridden,
                "MEASURED > INFERRED > PLANNED");
    }

    /** 一条边：端点、判定类别与来源。 */
    public record EdgeRow(long id, String name, Long aDeviceId, String aDeviceName,
                          Long bDeviceId, String bDeviceName, String kind, String source) {
    }

    /** 规划节点（`topology_node`）：有/无 ref_device_id 都要能出图并标「规划」。 */
    public record PlannedNode(long id, String name, String nodeType, Long refDeviceId, Long parentId) {
    }

    /**
     * 拓扑边视图（v5.18.0 / FR-v5.18-03~04 / W68-07~09）。
     *
     * <p>为什么要把「计数」和「边」分开返回：图例要能对上数。`kindCounts` 的三类之和
     * **恒等于** `total`（每条边恰好一类），`overridden` 是另一种东西——「规划边被实测
     * 覆盖」的事件数。把它混进 kindCounts 会让「四类之和 = 总数」永远差几条，
     * 而那种差数最容易被当成算错。
     */
    public record EdgeView(List<EdgeRow> edges, java.util.Map<String, Long> kindCounts,
                           java.util.Map<String, Long> counts, long total, long overridden,
                           List<PlannedNode> plannedNodes) {
    }

    /**
     * 拓扑边全量（去重后）。
     *
     * <p>三件必须一起做的事：
     * <ol>
     *   <li><b>同对端只出一条</b>：`MEASURED > MANUAL > PLANNED`。同一对设备同时有 LLDP
     *       实测与规划时，两条都画会让图上出现「平行双线」，看图的人以为接了两根；</li>
     *   <li><b>被覆盖的规划要计数</b>：不画但要说清「有 N 条规划已被实测覆盖」，
     *       否则规划源等于凭空消失；</li>
     *   <li><b>`topology_node` 纳入规划层</b>：父节点 → 子节点成边；没有父的孤立节点
     *       单独作为 `plannedNodes` 返回，否则「规划了但还没连」的节点在图上根本不存在。</li>
     * </ol>
     */
    public EdgeView edges(long projectId) {
        String linkSql = "SELECT l.id, l.name, l.a_device_id, ad.name AS a_device_name, "
                + "l.b_device_id, bd.name AS b_device_name, l.confidence, l.source "
                + "FROM link l LEFT JOIN device ad ON ad.id = l.a_device_id "
                + "LEFT JOIN device bd ON bd.id = l.b_device_id "
                + "WHERE l.project_id = ? ORDER BY l.id";
        TenantGuard.requireProjectScope(linkSql, "link");
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(linkSql, projectId);

        // 先按「无序对端」分组：组内挑 rank 最高的一条，同时记下「组内既有实测又有规划」
        java.util.Map<String, List<java.util.Map<String, Object>>> groups =
                new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : rows) {
            groups.computeIfAbsent(pairKey(row), k -> new java.util.ArrayList<>()).add(row);
        }

        List<EdgeRow> edges = new java.util.ArrayList<>();
        long overridden = 0;
        for (List<java.util.Map<String, Object>> group : groups.values()) {
            java.util.Map<String, Object> best = group.get(0);
            boolean hasMeasured = false;
            boolean hasPlanned = false;
            for (java.util.Map<String, Object> candidate : group) {
                String kind = kindOf(candidate);
                if (CONFIDENCE_MEASURED.equals(kind)) {
                    hasMeasured = true;
                }
                if (CONFIDENCE_PLANNED.equals(kind)) {
                    hasPlanned = true;
                }
                if (rank(kind) > rank(kindOf(best))) {
                    best = candidate;
                }
            }
            if (hasMeasured && hasPlanned) {
                overridden++;
            }
            edges.add(toEdge(best));
        }

        // 规划节点：父→子成边，孤立节点单列
        String nodeSql = "SELECT id, name, node_type, ref_device_id, parent_id FROM topology_node "
                + "WHERE project_id = ? ORDER BY id";
        TenantGuard.requireProjectScope(nodeSql, "topology_node");
        List<java.util.Map<String, Object>> nodeRows = jdbc.queryForList(nodeSql, projectId);
        java.util.Map<Long, String> nodeNames = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> node : nodeRows) {
            nodeNames.put(((Number) node.get("id")).longValue(), String.valueOf(node.get("name")));
        }
        List<PlannedNode> plannedNodes = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> node : nodeRows) {
            long id = ((Number) node.get("id")).longValue();
            Long parentId = node.get("parent_id") == null
                    ? null : ((Number) node.get("parent_id")).longValue();
            Long refDeviceId = node.get("ref_device_id") == null
                    ? null : ((Number) node.get("ref_device_id")).longValue();
            plannedNodes.add(new PlannedNode(id, String.valueOf(node.get("name")),
                    String.valueOf(node.get("node_type")), refDeviceId, parentId));
            if (parentId != null) {
                edges.add(new EdgeRow(id, nodeNames.getOrDefault(parentId, "?") + " → "
                        + node.get("name"), null, nodeNames.getOrDefault(parentId, "?"),
                        refDeviceId, String.valueOf(node.get("name")),
                        CONFIDENCE_PLANNED, SOURCE_TOPOLOGY_NODE));
            }
        }

        java.util.Map<String, Long> kindCounts = new java.util.LinkedHashMap<>();
        kindCounts.put(CONFIDENCE_MEASURED, 0L);
        kindCounts.put("MANUAL", 0L);
        kindCounts.put(CONFIDENCE_PLANNED, 0L);
        for (EdgeRow edge : edges) {
            kindCounts.merge(edge.kind(), 1L, Long::sum);
        }
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>(kindCounts);
        counts.put("CONFLICT", overridden);
        return new EdgeView(edges, kindCounts, counts, edges.size(), overridden, plannedNodes);
    }

    /** 对端无序键：A→B 与 B→A 必须是同一对。 */
    private static String pairKey(java.util.Map<String, Object> row) {
        Long a = row.get("a_device_id") == null ? null : ((Number) row.get("a_device_id")).longValue();
        Long b = row.get("b_device_id") == null ? null : ((Number) row.get("b_device_id")).longValue();
        if (a == null && b == null) {
            return "row:" + row.get("id");
        }
        long left = a == null ? -1 : a;
        long right = b == null ? -1 : b;
        return Math.min(left, right) + "|" + Math.max(left, right);
    }

    /** 判定类别：实测 / 人工 / 规划。人工 = 显式 MANUAL 来源且非规划。 */
    private static String kindOf(java.util.Map<String, Object> row) {
        String confidence = row.get("confidence") == null
                ? "" : String.valueOf(row.get("confidence"));
        String source = row.get("source") == null ? "" : String.valueOf(row.get("source"));
        if (CONFIDENCE_MEASURED.equals(confidence)) {
            return CONFIDENCE_MEASURED;
        }
        if (CONFIDENCE_PLANNED.equals(confidence)) {
            return CONFIDENCE_PLANNED;
        }
        if (SOURCE_MANUAL.equals(source)) {
            return "MANUAL";
        }
        return "MANUAL";
    }

    public static final String SOURCE_TOPOLOGY_NODE = "TOPO_NODE";

    private static int rank(String kind) {
        return switch (kind) {
            case CONFIDENCE_MEASURED -> 3;
            case "MANUAL" -> 2;
            default -> 1;
        };
    }

    private static EdgeRow toEdge(java.util.Map<String, Object> row) {
        Long aId = row.get("a_device_id") == null ? null : ((Number) row.get("a_device_id")).longValue();
        Long bId = row.get("b_device_id") == null ? null : ((Number) row.get("b_device_id")).longValue();
        return new EdgeRow(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")),
                aId, row.get("a_device_name") == null ? null : String.valueOf(row.get("a_device_name")),
                bId, row.get("b_device_name") == null ? null : String.valueOf(row.get("b_device_name")),
                kindOf(row), row.get("source") == null ? null : String.valueOf(row.get("source")));
    }

    /** LLDP 邻居幂等导入：同设备对已有实测行就跳过；规划行原样保留。 */
    public ImportResult importLldp(long projectId, List<Neighbor> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) {
            return new ImportResult(0, 0, plannedKept(projectId));
        }
        int imported = 0;
        int skipped = 0;
        String exists = "SELECT COUNT(*) FROM link WHERE project_id = ? AND a_device_id = ? "
                + "AND b_device_id = ? AND source = 'LLDP'";
        TenantGuard.requireProjectScope(exists, "link");
        String insert = "INSERT IGNORE INTO link (project_id, name, link_type, a_device_id, "
                + "b_device_id, status, confidence, source) VALUES (?, ?, 'rdma', ?, ?, 1, ?, ?)";
        TenantGuard.requireProjectScope(insert, "link");
        for (Neighbor neighbor : neighbors) {
            Long existing = jdbc.queryForObject(exists, Long.class, projectId,
                    neighbor.aDeviceId(), neighbor.bDeviceId());
            if (existing != null && existing > 0) {
                skipped++;
                continue;
            }
            String name = neighbor.name() == null || neighbor.name().isBlank()
                    ? "LLDP-" + neighbor.aDeviceId() + "-" + neighbor.bDeviceId()
                    : neighbor.name();
            int rows = jdbc.update(insert, projectId, name, neighbor.aDeviceId(),
                    neighbor.bDeviceId(), CONFIDENCE_MEASURED, SOURCE_LLDP);
            if (rows > 0) {
                imported++;
            } else {
                skipped++;
            }
        }
        return new ImportResult(imported, skipped, plannedKept(projectId));
    }

    private long plannedKept(long projectId) {
        String sql = "SELECT COUNT(*) FROM link WHERE project_id = ? AND confidence = 'PLANNED'";
        TenantGuard.requireProjectScope(sql, "link");
        Long count = jdbc.queryForObject(sql, Long.class, projectId);
        return count == null ? 0 : count;
    }
}
