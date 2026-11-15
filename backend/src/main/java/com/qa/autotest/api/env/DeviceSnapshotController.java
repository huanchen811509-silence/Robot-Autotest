package com.qa.autotest.api.env;

import com.qa.autotest.application.env.DeviceSnapshotService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.env.DeviceSnapshot;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备快照按项通道（v5.15.0 / FR-v5.15-01~03）。
 *
 * <p>挂在真实的 {@code /api/projects/{projectId}/snapshots} 前缀下，不另造
 * {@code /api/environment/*} 平行体系。所有路径都带数字正则或独立后缀，
 * 与既有 EnvController 的 profiles / latest / diff 不产生歧义映射。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/snapshots")
public class DeviceSnapshotController {

    public record DiffByItemRequest(long beforeId, long afterId) {
    }

    private final DeviceSnapshotService snapshots;
    private final AccessControlService access;

    public DeviceSnapshotController(DeviceSnapshotService snapshots, AccessControlService access) {
        this.snapshots = snapshots;
        this.access = access;
    }

    @GetMapping("/device")
    public ApiResponse<List<DeviceSnapshot>> list(@PathVariable long projectId,
                                                  @RequestParam(defaultValue = "20") int limit) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(snapshots.list(projectId, limit));
    }

    @GetMapping("/items/latest")
    public ApiResponse<List<DeviceSnapshotItem>> latestItems(@PathVariable long projectId,
                                                             @RequestParam(required = false) String key) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(snapshots.latestItems(projectId, key));
    }

    @GetMapping("/{snapshotId:\\d+}")
    public ApiResponse<DeviceSnapshot> detail(@PathVariable long projectId,
                                              @PathVariable long snapshotId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(snapshots.snapshot(projectId, snapshotId));
    }

    @GetMapping("/{snapshotId:\\d+}/items")
    public ApiResponse<List<DeviceSnapshotItem>> items(@PathVariable long projectId,
                                                       @PathVariable long snapshotId,
                                                       @RequestParam(required = false) String key) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(snapshots.items(projectId, snapshotId, key));
    }

    @PostMapping("/diff-by-item")
    public ApiResponse<List<DeviceSnapshotService.ItemDiff>> diffByItem(
            @PathVariable long projectId, @RequestBody DiffByItemRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(snapshots.diffByItem(projectId, request.beforeId(), request.afterId()));
    }
}
