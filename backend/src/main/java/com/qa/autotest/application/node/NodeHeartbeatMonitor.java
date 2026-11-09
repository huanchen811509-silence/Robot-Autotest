package com.qa.autotest.application.node;

import com.qa.autotest.application.notification.NotificationService;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 节点离线监视器（NODX-01）。
 *
 * <p>口径：心跳周期 {@code node.heartbeat-interval-seconds}（默认 30s），
 * **连续 3 个周期**没有心跳判离线——单次网络抖动不该把节点打掉，
 * 3 个周期（默认 90s）是「agent 真挂了」的最低置信度。
 *
 * <p>只把 ONLINE 置 OFFLINE：NEW（从没心跳过）保持 NEW——那是「注册了还没部署 agent」，
 * 和「agent 挂了」是两回事，界面要能区分（NODX-04 的四态之一）。
 */
@Component
@Profile("!node")  // node profile 的 agent 进程不跑主控侧监视器
public class NodeHeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(NodeHeartbeatMonitor.class);

    private final ExecutionNodeRepository nodes;
    private final Clock clock;
    private final int heartbeatIntervalSeconds;
    private final int missedLimit;
    private final NotificationService notifications;

    /** 兼容既有单测：不传通知服务时只做状态判定。 */
    public NodeHeartbeatMonitor(ExecutionNodeRepository nodes, Clock clock,
                                int heartbeatIntervalSeconds, int missedLimit) {
        this(nodes, clock, heartbeatIntervalSeconds, missedLimit, null);
    }

    @Autowired
    public NodeHeartbeatMonitor(ExecutionNodeRepository nodes, Clock clock,
                                @Value("${node.heartbeat-interval-seconds:30}")
                                int heartbeatIntervalSeconds,
                                @Value("${node.offline-after-misses:3}") int missedLimit,
                                NotificationService notifications) {
        this.nodes = nodes;
        this.clock = clock;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.missedLimit = missedLimit;
        this.notifications = notifications;
    }

    /** 每 15 秒扫一次；离线阈值 90s，扫描间隔远小于阈值，不会漏判。 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void sweep() {
        Instant staleBefore = clock.instant()
                .minusSeconds((long) heartbeatIntervalSeconds * missedLimit);
        List<Long> staleIds = nodes.findStaleOnlineIds(staleBefore);
        int marked = nodes.markStaleOffline(staleBefore);
        if (marked > 0) {
            // 单行日志说明判了几个离线；不带节点令牌相关任何信息
            log.info("node-monitor: marked OFFLINE: {}", marked);
        }
        if (notifications != null && !staleIds.isEmpty()) {
            // 节点离线通知按项目落库：监视器线程无用户上下文，项目取该节点最近一次 run 的归属。
            Map<Long, Long> projectByNode = nodes.latestProjectByNode();
            for (Long nodeId : staleIds) {
                Long projectId = projectByNode.get(nodeId);
                if (projectId != null) {
                    String name = nodes.find(nodeId).map(node -> node.name()).orElse(null);
                    notifications.notifyNodeOffline(projectId, nodeId, name);
                }
            }
        }
        // NODX-03：agent 崩溃不留悬挂 run——刚判离线的节点上 RUNNING 的 run 立即判 FAILED
        if (!staleIds.isEmpty()) {
            int failed = nodes.failRunningRunsByNodes(staleIds,
                    "node_agent_lost: 节点心跳连续 " + missedLimit + " 个周期缺席，"
                            + "agent 已判离线，本次运行终止（不回落本机）");
            if (failed > 0) {
                log.info("node-monitor: failed {} running run(s) on stale node(s) {}",
                        failed, staleIds);
            }
        }
    }
}
