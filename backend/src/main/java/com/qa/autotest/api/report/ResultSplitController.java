package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ResultSplitService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动 / 手工分离统计（FR-v5.4-05 / W54-05）。
 *
 * <p>只有一个只读端点。{@code runId} 给「看某一次执行」用，不给就按项目与时间窗聚合。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/result-split")
public class ResultSplitController {

    private final ResultSplitService service;
    private final AccessControlService access;

    public ResultSplitController(ResultSplitService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<ResultSplitService.Snapshot> split(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String runId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.stats(projectId, days, runId));
    }
}
