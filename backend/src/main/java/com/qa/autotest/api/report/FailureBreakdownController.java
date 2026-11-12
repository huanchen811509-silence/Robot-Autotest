package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.FailureBreakdown;
import com.qa.autotest.application.report.ReportChapterBuilder;
import com.qa.autotest.application.report.RunFailureBreakdownService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 失败归因分布与环境失败分流（FR-v5.2-06 / W52-10）。
 *
 * <p>两个只读端点：`distribution` 给页面画图（含下钻用例编号），`chapter` 给报告渲染
 * 直接取第 4 章。二者同一份计算，避免页面与报告显示两个不同的通过率。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/runs/{runId}/failure-breakdown")
public class FailureBreakdownController {

    private final RunFailureBreakdownService service;
    private final AccessControlService access;

    public FailureBreakdownController(RunFailureBreakdownService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    /**
     * 分布。
     *
     * @param top 只要前 N 个聚簇（默认 5）；返回的 buckets 始终是全量分类桶
     */
    @GetMapping
    public ApiResponse<Distribution> distribution(@PathVariable long projectId,
                                                  @PathVariable String runId,
                                                  @RequestParam(defaultValue = "5") int top) {
        access.requireProjectAccess(projectId);
        FailureBreakdown.Snapshot snapshot = service.of(projectId, runId);
        return ApiResponse.ok(new Distribution(snapshot, snapshot.top(top)));
    }

    /** 报告第 4 章（失败分析）。 */
    @GetMapping("/chapter")
    public ApiResponse<ReportChapterBuilder.Chapter> chapter(@PathVariable long projectId,
                                                             @PathVariable String runId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.chapterOf(projectId, runId));
    }

    /**
     * 分布响应。
     *
     * @param snapshot 全量快照（含四类计数与分母口径）
     * @param top      前 N 个聚簇（按条数降序、同数按指纹升序，可复现）
     */
    public record Distribution(FailureBreakdown.Snapshot snapshot,
                               java.util.List<FailureBreakdown.Cluster> top) {
    }
}
