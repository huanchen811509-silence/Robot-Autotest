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
