package com.qa.autotest.api.report;

import com.qa.autotest.application.report.ReportSummaryService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报告速览（FR-3.1-07）；{runId} 与现有报告详情的 id 语义一致。 */
@RestController
@RequestMapping("/api/reports")
public class ReportSummaryController {

    private final ReportSummaryService summaries;

    public ReportSummaryController(ReportSummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/{runId}/summary")
    public ApiResponse<ReportSummaryService.Summary> summary(@RequestParam long projectId,
                                                              @PathVariable String runId) {
        return ApiResponse.ok(summaries.summarize(projectId, runId));
    }
}
