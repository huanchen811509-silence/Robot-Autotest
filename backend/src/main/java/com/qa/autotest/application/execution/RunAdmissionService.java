package com.qa.autotest.application.execution;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 运行准入与背压（FR-3.4-02 / EW34-02）。
 *
 * <p>三层上限：全局在飞、单项目、单节点。锁是公平锁，等待者按到达顺序获得名额，
 * 避免高优先级项目把后来者饿死；名额满时在 {@code max-wait-ms}（默认 0）内等待，
 * 超时抛 {@link ErrorCode#CONFLICT} 并给出当前占用，前端可据此提示「稍后重试」。
 *
 * <p>名额在创建运行时占用、在运行进入终态时释放。兜底：每小时回收超过
 * {@code stale-after-hours}（默认 6h）仍未释放的名额，避免异常路径造成永久占用。
 */
@Service
public class RunAdmissionService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RunAdmissionService.class);

    private record Slot(long projectId, Long nodeId, Instant admittedAt) {
    }

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition released = lock.newCondition();
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    @Value("${execution.admission.max-inflight:50}")
    private int maxInflight = 50;

    @Value("${execution.admission.max-per-project:20}")
    private int maxPerProject = 20;

    @Value("${execution.admission.max-per-node:10}")
    private int maxPerNode = 10;

    @Value("${execution.admission.max-wait-ms:0}")
    private long maxWaitMs = 0;

    @Value("${execution.admission.stale-after-hours:6}")
    private long staleAfterHours = 6;

    /**
     * 申请名额；失败抛 CONFLICT（HTTP 409）。
     *
     * @param runId 运行业务键，用于终态释放与幂等
     */
    public void admit(long projectId, Long nodeId, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }
        if (slots.containsKey(runId)) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, maxWaitMs));
        lock.lock();
        try {
            while (!fits(projectId, nodeId)) {
                long remainMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainMs <= 0) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "并发已满（背压）：全局 " + slots.size() + "/" + maxInflight
                                    + "，本项目 " + countProject(projectId) + "/" + maxPerProject
                                    + "，本节点 " + countNode(nodeId) + "/" + maxPerNode
                                    + "；请稍后重试或降低并发");
                }
                released.await(remainMs, TimeUnit.MILLISECONDS);
            }
            slots.put(runId, new Slot(projectId, nodeId, Instant.now()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONFLICT, "申请并发名额被中断");
        } finally {
            lock.unlock();
        }
    }

    /** 终态释放；重复调用无副作用。 */
    public void release(String runId) {
        if (runId == null) {
            return;
        }
        lock.lock();
        try {
            if (slots.remove(runId) != null) {
                released.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 兜底回收：异常路径未释放的名额按最大运行时长兜底。 */
    @Scheduled(fixedDelayString = "${execution.admission.reap-interval-ms:3600000}",
            initialDelayString = "${execution.admission.reap-initial-delay-ms:300000}")
    @Profile("!test")
    public void reap() {
        Instant before = Instant.now().minus(Duration.ofHours(Math.max(1, staleAfterHours)));
        int removed = 0;
        lock.lock();
        try {
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                if (entry.getValue().admittedAt().isBefore(before)) {
                    slots.remove(entry.getKey());
                    removed++;
                }
            }
            if (removed > 0) {
                released.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (removed > 0) {
            LOG.warn("回收超时未释放的并发名额 {} 个（阈值 {}h）", removed, staleAfterHours);
        }
    }

    public View view() {
        lock.lock();
        try {
            Map<Long, Integer> byProject = new LinkedHashMap<>();
            slots.values().forEach(slot -> byProject.merge(slot.projectId(), 1, Integer::sum));
            Map<Long, Integer> byNode = new LinkedHashMap<>();
            slots.values().stream().filter(slot -> slot.nodeId() != null)
                    .forEach(slot -> byNode.merge(slot.nodeId(), 1, Integer::sum));
            return new View(slots.size(), maxInflight, maxPerProject, maxPerNode, byProject, byNode);
        } finally {
            lock.unlock();
        }
    }

    private boolean fits(long projectId, Long nodeId) {
        return slots.size() < maxInflight
                && countProject(projectId) < maxPerProject
                && (nodeId == null || countNode(nodeId) < maxPerNode);
    }

    private int countProject(long projectId) {
        return (int) slots.values().stream().filter(slot -> slot.projectId() == projectId).count();
    }

    private int countNode(Long nodeId) {
        if (nodeId == null) {
            return 0;
        }
        return (int) slots.values().stream()
                .filter(slot -> nodeId.equals(slot.nodeId())).count();
    }

    /** 准入视图：运维中心用于解释「为什么被拒」。 */
    public record View(int inflight, int maxInflight, int maxPerProject, int maxPerNode,
                       Map<Long, Integer> byProject, Map<Long, Integer> byNode) {
    }
}
