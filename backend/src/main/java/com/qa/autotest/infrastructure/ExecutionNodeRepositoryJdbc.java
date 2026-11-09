package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.domain.node.ExecutionNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 执行节点 JDBC 实现（NODX-01）。
 *
 * <p>execution_node 是平台级表（无 project_id 列），**有意不走**
 * {@code TenantGuard.requireProjectScope}——豁免依据写在接口与迁移注释里。
 */
@Repository
public class ExecutionNodeRepositoryJdbc implements ExecutionNodeRepository {

    private static final String SELECT = """
            SELECT id, name, token_hash, status, rf_available, pytest_available,
                   max_concurrency, last_heartbeat_at, created_at, agent_base_url
              FROM execution_node
            """;

    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    public ExecutionNodeRepositoryJdbc(JdbcTemplate jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<ExecutionNode> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", ExecutionNodeRepositoryJdbc::map, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ExecutionNode> findByName(String name) {
        return jdbc.query(SELECT + " WHERE name = ?", ExecutionNodeRepositoryJdbc::map, name)
                .stream().findFirst();
    }

    @Override
    public boolean nameExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_node WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    @Override
    public List<ExecutionNode> list() {
        return jdbc.query(SELECT + " ORDER BY id", ExecutionNodeRepositoryJdbc::map);
    }

    @Override
    public ExecutionNode insert(String name, String tokenHash, boolean rfAvailable,
                                boolean pytestAvailable, int maxConcurrency, Long operatorId,
                                String agentBaseUrl) {
        jdbc.update("""
                INSERT INTO execution_node
                  (name, token_hash, status, rf_available, pytest_available, max_concurrency,
                   agent_base_url, created_by)
                VALUES (?, ?, 'NEW', ?, ?, ?, ?, ?)
                """, name, tokenHash, rfAvailable, pytestAvailable, maxConcurrency,
                agentBaseUrl, operatorId);
        return findByName(name).orElseThrow();
    }

    @Override
    public void updateAgentBaseUrl(long id, String agentBaseUrl) {
        jdbc.update("UPDATE execution_node SET agent_base_url = ? WHERE id = ?",
                agentBaseUrl, id);
    }

    @Override
    public void heartbeat(long id, boolean rfAvailable, boolean pytestAvailable) {
        jdbc.update("""
                UPDATE execution_node
                   SET status = 'ONLINE', rf_available = ?, pytest_available = ?,
                       last_heartbeat_at = ?
                 WHERE id = ?
                """, rfAvailable, pytestAvailable, Timestamp.from(clock.instant()), id);
    }

    @Override
    public int markStaleOffline(Instant staleBefore) {
        return jdbc.update("""
                UPDATE execution_node SET status = 'OFFLINE'
                 WHERE status = 'ONLINE' AND last_heartbeat_at < ?
                """, Timestamp.from(staleBefore));
    }

    @Override
    public void updateStatus(long id, String status, Long operatorId) {
        jdbc.update("UPDATE execution_node SET status = ?, updated_by = ? WHERE id = ?",
                status, operatorId, id);
    }

    /**
     * 节点占用读数（NODX-02）：按 run.node_id 聚合在跑数。
     * 平台级统计——节点本身是全局资源（V9_4 迁移注释同口径），跨项目聚合
     * 在本仓储内完成（本类有意不进 TenantGuard），单条 SQL 一次聚合，不分页。
     */
    @Override
    public java.util.Map<Long, Integer> runningRunsByNode() {
        return jdbc.query("""
                SELECT node_id, COUNT(*) AS running_runs
                  FROM run
                 WHERE node_id IS NOT NULL AND run_status = 'running'
                 GROUP BY node_id
                """, rs -> {
            java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
            while (rs.next()) {
                counts.put(rs.getLong("node_id"), rs.getInt("running_runs"));
            }
            return counts;
        });
    }

    /**
     * 节点最近任务（NODX-04）：平台级 SQL——与 runningRunsByNode 同口径
     * （run.node_id 无租户路径，本类有意不进 TenantGuard）。
     * 每节点取 MAX(id) 一条，时间用 finished/started/enqueued 三级回退。
     */
    @Override
    public java.util.Map<Long, NodeLastRun> latestRunsByNode() {
        return jdbc.query("""
                SELECT r.node_id, r.run_id, r.run_status,
                       COALESCE(r.finished_at, r.started_at, r.enqueued_at) AS last_at
                  FROM run r
                  JOIN (SELECT node_id, MAX(id) AS mid
                          FROM run WHERE node_id IS NOT NULL GROUP BY node_id) t
                    ON r.id = t.mid
                """, rs -> {
            java.util.Map<Long, NodeLastRun> latest = new java.util.HashMap<>();
            while (rs.next()) {
                java.sql.Timestamp at = rs.getTimestamp("last_at");
                latest.put(rs.getLong("node_id"), new NodeLastRun(
                        rs.getString("run_id"), rs.getString("run_status"),
                        at == null ? null : at.toInstant().toString()));
            }
            return latest;
        });
    }

    @Override
    public java.util.Map<Long, Long> latestProjectByNode() {
        return jdbc.query("""
                SELECT r.node_id, r.project_id
                  FROM run r
                  JOIN (SELECT node_id, MAX(id) AS mid
                          FROM run WHERE node_id IS NOT NULL GROUP BY node_id) t
                    ON r.id = t.mid
                """, rs -> {
            java.util.Map<Long, Long> latest = new java.util.HashMap<>();
            while (rs.next()) {
                latest.put(rs.getLong("node_id"), rs.getLong("project_id"));
            }
            return latest;
        });
    }

    @Override
    public List<Long> findStaleOnlineIds(Instant staleBefore) {
        return jdbc.queryForList("""
                SELECT id FROM execution_node
                 WHERE status = 'ONLINE' AND last_heartbeat_at < ?
                """, Long.class, Timestamp.from(staleBefore));
    }

    /**
     * 崩溃判失败（NODX-03）：平台级 SQL——节点与 run 的归属关系是平台维度
     * （run.node_id 无租户路径），监视器线程也无用户会话；豁免理由与
     * runningRunsByNode 同口径（本类有意不进 TenantGuard）。
     * 只动挂在指定节点上的未终态 run（queued/preparing/running 都是悬挂）；
     * test_result 不伪造（崩溃无用例级结果）。
     */
    @Override
    public int failRunningRunsByNodes(List<Long> nodeIds, String reason) {
        if (nodeIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        Object[] params = new Object[nodeIds.size() + 1];
        params[0] = reason;
        for (int i = 0; i < nodeIds.size(); i++) {
            params[i + 1] = nodeIds.get(i);
        }
        return jdbc.update("""
                UPDATE run r
                   SET run_status = 'failed', finished_at = NOW(3),
                       failure_class = 'ENV', failure_reason = ?
                 WHERE r.run_status IN ('queued', 'preparing', 'running')
                   AND r.node_id IN (%s)
                   AND NOT EXISTS (
                       SELECT 1 FROM execution_shard s
                        WHERE s.run_id = r.run_id
                          AND s.project_id = r.project_id
                          AND s.state IN ('PENDING', 'LEASED', 'LOST')
                   )
                """.formatted(placeholders), params);
    }

    private static ExecutionNode map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
        return new ExecutionNode(rs.getLong("id"), rs.getString("name"),
                rs.getString("token_hash"), rs.getString("status"),
                rs.getBoolean("rf_available"), rs.getBoolean("pytest_available"),
                rs.getInt("max_concurrency"),
                heartbeat == null ? null : heartbeat.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("agent_base_url"));
    }
}
