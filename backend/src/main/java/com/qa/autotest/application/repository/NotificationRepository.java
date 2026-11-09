package com.qa.autotest.application.repository;

import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.notification.NotificationItem;

import java.util.List;
import java.util.Optional;

/** 通知与订阅偏好仓储（FR-3.1-08）。 */
public interface NotificationRepository {

    record Preference(String type, boolean enabled) {
    }

    /** 按 run_key + channel + type 去重：已存在时只更新 message/status，不重复插入。 */
    void upsert(long projectId, String runKey, String type, String status, String message,
                Long operatorId);

    /**
     * 分页读收件箱。
     *
     * <p>已读状态挂在 notification_read 上按人隔离：同一项目里 A 读过不影响 B 的未读；
     * 因此查询必须带 userId，未读即「该用户没有 read 记录」，而不是通知本体上的列。
     */
    PageResult<NotificationItem> page(long projectId, long userId, boolean unreadOnly,
                                      int page, int size);

    /** 该用户在本项目内未读的通知条数。 */
    long unreadCount(long projectId, long userId);

    /** 标记该用户已读；已存在则不改时间（返回 0），保证重复点击幂等。 */
    int markRead(long projectId, long userId, long id);

    /** 该用户把项目内全部通知补读；返回新增的已读记录条数。 */
    int markAllRead(long projectId, long userId);

    List<Preference> preferences(long projectId, long userId);

    void upsertPreference(long projectId, long userId, String type, boolean enabled, Long operatorId);

    /** 未配置时默认开启。 */
    Optional<Boolean> findPreference(long projectId, long userId, String type);
}
