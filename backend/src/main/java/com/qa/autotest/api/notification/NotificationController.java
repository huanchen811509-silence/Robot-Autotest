package com.qa.autotest.api.notification;

import com.qa.autotest.application.notification.NotificationService;
import com.qa.autotest.application.repository.NotificationRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.notification.NotificationItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知中心（FR-3.1-08）。
 *
 * <p>已读接口是幂等的：重复点同一条返回 0，不会把 read_at 往后推，
 * 前端可以安全地重复调用。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<PageResult<NotificationItem>> page(
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "unread") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notifications.page(projectId(projectId), status, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(Map.of("count", notifications.unreadCount(projectId(projectId))));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Integer>> read(@RequestParam(required = false) Long projectId,
                                                    @PathVariable long id) {
        return ApiResponse.ok(Map.of("count", notifications.markRead(projectId(projectId), id)));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> readAll(@RequestBody(required = false) ReadAllRequest request) {
        long projectId = projectId(request == null ? null : request.projectId());
        return ApiResponse.ok(Map.of("count", notifications.markAllRead(projectId)));
    }

    @GetMapping("/preferences")
    public ApiResponse<List<NotificationRepository.Preference>> preferences(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(notifications.preferences(projectId(projectId)));
    }

    @PutMapping("/preferences")
    public ApiResponse<List<NotificationRepository.Preference>> updatePreferences(
            @RequestParam(required = false) Long projectId,
            @RequestBody PreferenceRequest request) {
        return ApiResponse.ok(notifications.updatePreferences(
                projectId(projectId), request.items() == null ? List.of() : request.items()));
    }

    private static long projectId(Long provided) {
        return provided == null ? TenantContext.requireProjectId() : provided;
    }

    public record ReadAllRequest(Long projectId) {
    }

    public record PreferenceRequest(List<NotificationRepository.Preference> items) {
    }
}
