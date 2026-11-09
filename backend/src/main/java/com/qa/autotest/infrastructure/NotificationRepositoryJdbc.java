package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.NotificationRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.notification.NotificationItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 通知与订阅偏好 JDBC 实现。 */
@Repository
public class NotificationRepositoryJdbc implements NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(long projectId, String runKey, String type, String status, String message,
                       Long operatorId) {
        String sql = "INSERT INTO notification "
                + "(project_id, run_key, channel, notification_type, notify_status, message, "
                + "created_by, updated_by) VALUES (?, ?, 'web', ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE project_id = VALUES(project_id), "
                + "notify_status = VALUES(notify_status), message = VALUES(message), "
                + "updated_by = VALUES(updated_by)";
        TenantGuard.requireProjectScope(sql, "notification");
        jdbc.update(sql, projectId, runKey, type, status, message, operatorId, operatorId);
    }

    @Override
    public PageResult<NotificationItem> page(long projectId, long userId, boolean unreadOnly,
                                             int page, int size) {
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        // LEFT JOIN 到本人的已读记录：r.id 为空即该用户未读，读过的行仍能取到自己的 read_at
        String from = "FROM notification n "
                + "LEFT JOIN notification_read r "
                + "ON r.notification_id = n.id AND r.user_id = ? "
                + "WHERE n.project_id = ?"
                + (unreadOnly ? " AND r.id IS NULL" : "");
        String countSql = "SELECT COUNT(*) " + from;
        TenantGuard.requireProjectScope(countSql, "notification");
        Long total = jdbc.queryForObject(countSql, Long.class, userId, projectId);
        String sql = "SELECT n.id, n.project_id, n.run_key, n.notification_type, n.notify_status, "
                + "n.message, r.read_at, n.created_at " + from
                + " ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(sql, "notification");
        long totalValue = total == null ? 0 : total;
        List<NotificationItem> items = jdbc.query(sql, (rs, rowNum) -> new NotificationItem(
                rs.getLong("id"),
                rs.getObject("project_id", Long.class),
                rs.getString("run_key"),
                rs.getString("notification_type"),
                rs.getString("notify_status"),
                rs.getString("message"),
                instant(rs.getTimestamp("read_at")),
                instant(rs.getTimestamp("created_at"))),
                userId, projectId, pageSize, (long) (pageNo - 1) * pageSize);
        return new PageResult<>(items, totalValue, pageNo, pageSize);
    }

    @Override
    public long unreadCount(long projectId, long userId) {
        String sql = "SELECT COUNT(*) FROM notification n "
                + "LEFT JOIN notification_read r "
                + "ON r.notification_id = n.id AND r.user_id = ? "
                + "WHERE n.project_id = ? AND r.id IS NULL";
        TenantGuard.requireProjectScope(sql, "notification");
        Long value = jdbc.queryForObject(sql, Long.class, userId, projectId);
        return value == null ? 0 : value;
    }

    @Override
    public int markRead(long projectId, long userId, long id) {
        // INSERT ... SELECT 保证只能对自己项目内的通知落已读记录；
        // ON DUPLICATE KEY UPDATE read_at = read_at 让重复点击不推进时间（受影响行数为 0）。
        String sql = "INSERT INTO notification_read "
                + "(notification_id, project_id, user_id, created_by, updated_by) "
                + "SELECT id, project_id, ?, ?, ? FROM notification "
                + "WHERE project_id = ? AND id = ? "
                + "ON DUPLICATE KEY UPDATE read_at = read_at";
        TenantGuard.requireProjectScope(sql, "notification_read");
        return jdbc.update(sql, userId, userId, userId, projectId, id);
    }

    @Override
    public int markAllRead(long projectId, long userId) {
        // 只补「本人还没有」的行；NOT EXISTS 让已读的不会重复插入，返回值即新增条数
        String sql = "INSERT INTO notification_read "
                + "(notification_id, project_id, user_id, created_by, updated_by) "
                + "SELECT n.id, n.project_id, ?, ?, ? FROM notification n "
                + "WHERE n.project_id = ? AND NOT EXISTS ("
                + "SELECT 1 FROM notification_read r "
                + "WHERE r.notification_id = n.id AND r.user_id = ?)";
        TenantGuard.requireProjectScope(sql, "notification_read");
        return jdbc.update(sql, userId, userId, userId, projectId, userId);
    }

    @Override
    public List<Preference> preferences(long projectId, long userId) {
        String sql = "SELECT notification_type, enabled FROM notification_preference "
                + "WHERE project_id = ? AND user_id = ?";
        TenantGuard.requireProjectScope(sql, "notification_preference");
        return jdbc.query(sql, (rs, rowNum) ->
                new Preference(rs.getString("notification_type"), rs.getInt("enabled") == 1),
                projectId, userId);
    }

    @Override
    public void upsertPreference(long projectId, long userId, String type, boolean enabled,
                                 Long operatorId) {
        String sql = "INSERT INTO notification_preference "
                + "(project_id, user_id, notification_type, enabled, created_by, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_by = VALUES(updated_by)";
        TenantGuard.requireProjectScope(sql, "notification_preference");
        jdbc.update(sql, projectId, userId, type, enabled ? 1 : 0, operatorId, operatorId);
    }

    @Override
    public Optional<Boolean> findPreference(long projectId, long userId, String type) {
        String sql = "SELECT enabled FROM notification_preference "
                + "WHERE project_id = ? AND user_id = ? AND notification_type = ?";
        TenantGuard.requireProjectScope(sql, "notification_preference");
        return jdbc.query(sql, (rs, rowNum) -> rs.getInt("enabled") == 1, projectId, userId, type)
                .stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
