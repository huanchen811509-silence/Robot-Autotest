package com.qa.autotest.application.repository;

import com.qa.autotest.domain.node.ExecutionNode;

import java.util.List;
import java.util.Optional;

/**
 * 执行节点仓储（NODX-01）。
 *
 * <p>平台级表，查询不带 project_id 条件——这条豁免写在类注释与迁移注释里，
 * 不走 {@code TenantGuard.requireProjectScope}。
 */
public interface ExecutionNodeRepository {

    Optional<ExecutionNode> find(long id);

    Optional<ExecutionNode> findByName(String name);

    boolean nameExists(String name);

    List<ExecutionNode> list();

    /** 注册：tokenHash 是 SHA-256(明文)，明文只在服务层返回值里出现一次。 */
    ExecutionNode insert(String name, String tokenHash, boolean rfAvailable,
                         boolean pytestAvailable, int maxConcurrency, Long operatorId,
                         String agentBaseUrl);

    /** NODX-03：登记/更新 agent 接入地址（注册后也可单独补录）。 */
    void updateAgentBaseUrl(long id, String agentBaseUrl);

    /** 心跳：更新能力上报与 last_heartbeat_at，并置 ONLINE。 */
    void heartbeat(long id, boolean rfAvailable, boolean pytestAvailable);

    /** 监视器：把心跳超期的 ONLINE 节点置 OFFLINE（NEW / DISABLED 不动）。 */
    int markStaleOffline(java.time.Instant staleBefore);

    void updateStatus(long id, String status, Long operatorId);

    /** 各节点最近一条 run 的摘要（NODX-04，最近任务读数）。 */
    record NodeLastRun(String runId, String state, String finishedAt) {
    }

    /**
     * 各节点最近一条 run（NODX-04）：按 {@code run.node_id} 取 MAX(id)，
     * 供节点页「最近任务」列。节点从未接过 run 时不出现在结果里。
     */
    java.util.Map<Long, NodeLastRun> latestRunsByNode();

    /**
     * 各节点最近一条 run 所属项目（通知落库用）：run.node_id 是平台维度，
     * 节点离线时拿不到请求上下文，只能靠最近一次运行反查项目。
     */
    default java.util.Map<Long, Long> latestProjectByNode() {
        return java.util.Map.of();
    }

    /**
     * 各节点当前在跑的 run 数（NODX-02，占用读数）：按 {@code run.node_id} 聚合，
     * 只数 {@code run_status='running'}。节点无在跑任务时不出现在结果里。
     */
    java.util.Map<Long, Integer> runningRunsByNode();

    /** NODX-03：心跳已超期的 ONLINE 节点 id（崩溃判失败的候选）。 */
    List<Long> findStaleOnlineIds(java.time.Instant staleBefore);

    /**
     * NODX-03：把指定节点上 RUNNING 的 run 全部判 FAILED（agent 崩溃不留悬挂 run）。
     * 平台级 SQL，写在实现类豁免注释里；返回受影响 run 数。
     */
    int failRunningRunsByNodes(List<Long> nodeIds, String reason);
}
