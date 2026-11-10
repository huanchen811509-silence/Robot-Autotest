package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.ProjectSyncService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存量项目同步（v4.9.0 / FR-v4.9-01..07）。
 *
 * <p>预览零写入、执行可回滚；冲突项由前端逐条选择 `TAKE_REMOTE` / `DUPLICATE`，缺省 `KEEP_LOCAL`。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sync")
public class ProjectSyncController {

    /** 键为 sync_job_item.id（字符串），值为 TAKE_REMOTE / DUPLICATE / KEEP_LOCAL。 */
    public record ResolveRequest(Map<String, String> resolutions) {
    }

    private final ProjectSyncService service;

    public ProjectSyncController(ProjectSyncService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ApiResponse<ProjectSyncService.SyncPreview> preview(@PathVariable long projectId) {
        return ApiResponse.ok(service.preview(projectId));
    }

    @PostMapping("/jobs/{jobId}/execute")
    public ApiResponse<ProjectSyncService.SyncExecResult> execute(
            @PathVariable long projectId,
            @PathVariable long jobId,
            @RequestBody(required = false) ResolveRequest request) {
        return ApiResponse.ok(service.execute(jobId, toLongKeys(request)));
    }

    @PostMapping("/jobs/{jobId}/rollback")
    public ApiResponse<ProjectSyncService.SyncRollbackResult> rollback(
            @PathVariable long projectId, @PathVariable long jobId) {
        return ApiResponse.ok(service.rollback(jobId));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.jobs(projectId, limit));
    }

    @PostMapping("/cases/{caseId}/local-override")
    public ApiResponse<Map<String, Boolean>> markLocalOverride(
            @PathVariable long projectId, @PathVariable long caseId) {
        service.markLocalOverride(projectId, caseId);
        return ApiResponse.ok(Map.of("updated", true));
    }

    private static Map<Long, String> toLongKeys(ResolveRequest request) {
        if (request == null || request.resolutions() == null) {
            return Map.of();
        }
        Map<Long, String> result = new java.util.HashMap<>();
        request.resolutions().forEach((key, value) -> {
            try {
                result.put(Long.parseLong(key), value);
            } catch (NumberFormatException ignored) {
                // 非数字键直接忽略：它们不可能对应 sync_job_item.id
            }
        });
        return result;
    }
}
