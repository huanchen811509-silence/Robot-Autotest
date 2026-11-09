package com.qa.autotest.api.admin;

import com.qa.autotest.application.archive.ArchiveService;
import com.qa.autotest.application.repository.ArchiveRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.archive.ArchivePolicy;
import com.qa.autotest.domain.archive.ArchivedRunInfo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 归档与保留 API（FR-3.4-01 / EW34-01）。写操作仅管理员。 */
@RestController
@RequestMapping("/api/admin/archive")
public class ArchiveController {

    public record PolicyRequest(long projectId, boolean enabled, int retentionDays, int batchSize) {
    }

    private final ArchiveService archive;

    public ArchiveController(ArchiveService archive) {
        this.archive = archive;
    }

    @GetMapping("/policy")
    public ApiResponse<ArchivePolicy> policy(@RequestParam long projectId) {
        return ApiResponse.ok(archive.policy(projectId));
    }

    @PutMapping("/policy")
    public ApiResponse<ArchivePolicy> savePolicy(@RequestBody PolicyRequest request) {
        return ApiResponse.ok(archive.savePolicy(request.projectId(), request.enabled(),
                request.retentionDays(), request.batchSize()));
    }

    @GetMapping("/preview")
    public ApiResponse<ArchiveService.Preview> preview(@RequestParam long projectId) {
        return ApiResponse.ok(archive.preview(projectId));
    }

    /** {@code dryRun} 默认 true：不传参数只返回清单。 */
    @PostMapping("/run")
    public ApiResponse<ArchiveService.ExecuteResult> run(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok(archive.run(projectId, dryRun));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ArchivedRunInfo>> runs(@RequestParam long projectId,
                                                   @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(archive.archives(projectId, limit));
    }

    @GetMapping("/runs/{runId}/results")
    public ApiResponse<List<ArchiveRepository.ColdResult>> results(
            @PathVariable String runId, @RequestParam long projectId,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(archive.coldResults(projectId, runId, limit));
    }

    @PostMapping("/runs/{runId}/restore")
    public ApiResponse<ArchiveService.RestoreResult> restore(
            @PathVariable String runId, @RequestParam long projectId) {
        return ApiResponse.ok(archive.restore(projectId, runId));
    }
}
