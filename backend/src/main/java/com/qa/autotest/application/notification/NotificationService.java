package com.qa.autotest.application.notification;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.NotificationRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.notification.NotificationItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知中心（FR-3.1-08）。
 *
 * <p>写入遵循两条约束：
 * <ul>
 *   <li>同 {@code run_key + type} 只保留一条（表唯一键 + upsert），重复终态回调不会刷屏；</li>
 *   <li>当前用户显式关闭该类型时跳过写入；没有偏好记录视为开启。</li>
 * </ul>
 * 无用户上下文（定时任务）时按项目级写入，不因缺少偏好而丢通知。
 */
@Service
public class NotificationService {

    public static final List<String> TYPES =
            List.of("RUN_FINISHED", "RUN_FAILED", "NODE_OFFLINE", "PLAN_CONFLICT");
    private static final String CHANNEL_STATUS = "pending";

    private final NotificationRepository repository;
    private final AccessControlService access;

    public NotificationService(NotificationRepository repository, AccessControlService access) {
        this.repository = repository;
        this.access = access;
    }

    public PageResult<NotificationItem> page(long projectId, String status, int page, int size) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        boolean unreadOnly = status == null || status.isBlank() || "unread".equalsIgnoreCase(status);
        return repository.page(projectId, userId, unreadOnly, page, size);
    }

    /**
     * 未读数按人算：通知本体是项目级共享的，但「读没读」是个人行为。
     * 共用一列会让先打开通知中心的人把其他人的未读也清掉，因此必须传 userId。
     */
    public long unreadCount(long projectId) {
        access.bindProject(projectId);
        return repository.unreadCount(projectId, TenantContext.requireUserId());
    }

    public int markRead(long projectId, long id) {
        access.bindProject(projectId);
        return repository.markRead(projectId, TenantContext.requireUserId(), id);
    }

    public int markAllRead(long projectId) {
        access.bindProject(projectId);
        return repository.markAllRead(projectId, TenantContext.requireUserId());
    }

    public List<NotificationRepository.Preference> preferences(long projectId) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        Map<String, Boolean> stored = new LinkedHashMap<>();
        for (NotificationRepository.Preference preference : repository.preferences(projectId, userId)) {
            stored.put(preference.type(), preference.enabled());
        }
        List<NotificationRepository.Preference> merged = new ArrayList<>();
        for (String type : TYPES) {
            merged.add(new NotificationRepository.Preference(type, stored.getOrDefault(type, true)));
        }
        return merged;
    }

    public List<NotificationRepository.Preference> updatePreferences(
            long projectId, List<NotificationRepository.Preference> items) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "items 不能为空");
        }
        for (NotificationRepository.Preference item : items) {
            if (!TYPES.contains(item.type())) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未知通知类型：" + item.type());
            }
            repository.upsertPreference(projectId, userId, item.type(), item.enabled(), userId);
        }
        return preferences(projectId);
    }

    /** 运行进入终态时写一条；非终态直接忽略。 */
    public void notifyRun(RunRecord run) {
        if (run == null || run.state() == null || !run.state().terminal()) {
            return;
        }
        String type = run.state() == RunState.FINISHED ? "RUN_FINISHED" : "RUN_FAILED";
        String message = "运行 " + run.runId() + " " + label(run.state())
                + "：通过 " + run.passedTests() + " / 失败 " + run.failedTests();
        notify(run.projectId(), run.runId(), type, CHANNEL_STATUS, message);
    }

    /** 节点连续被判离线时写一条；run_key 用节点 id，避免同一节点反复离线刷屏。 */
    public void notifyNodeOffline(long projectId, long nodeId, String nodeName) {
        String message = "节点 " + (nodeName == null || nodeName.isBlank() ? nodeId : nodeName)
                + " 连续心跳超时，已判定离线";
        notify(projectId, "node:" + nodeId, "NODE_OFFLINE", CHANNEL_STATUS, message);
    }

    public void notifyPlanConflict(long projectId, String planKey, String message) {
        notify(projectId, planKey, "PLAN_CONFLICT", CHANNEL_STATUS, message);
    }

    /**
     * 底层写入。项目必须存在；用户偏好只在有用户上下文时检查——
     * 定时任务/监听器线程没有用户身份，不能因为查不到偏好就丢弃通知。
     */
    public void notify(long projectId, String runKey, String type, String status, String message) {
        if (projectId <= 0 || runKey == null || runKey.isBlank()) {
            return;
        }
        Long userId = TenantContext.userId();
        if (userId != null) {
            boolean enabled = repository.findPreference(projectId, userId, type).orElse(true);
            if (!enabled) {
                return;
            }
        }
        repository.upsert(projectId, runKey, type, status, message, userId);
    }

    private static String label(RunState state) {
        return switch (state) {
            case FINISHED -> "已完成";
            case FAILED -> "失败";
            case CANCELED -> "已取消";
            default -> state.name();
        };
    }
}
