package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.CompareService;
import com.qa.autotest.application.report.ReportIngestService;
import com.qa.autotest.application.report.ReportLogService;
import com.qa.autotest.application.report.ReportQueryService;
import com.qa.autotest.application.report.ReportTreeService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告接口（RPT-01）：解析入库、详情、分页列表。
 *
 * <p>鉴权由 {@code AuthInterceptor} 做登录拦截；项目级校验必须在控制器先
 * {@link AccessControlService#bindProject(long)}，再交给应用服务判权。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportIngestService ingest;
    private final ReportQueryService query;
    private final ReportTreeService tree;
    private final ReportLogService logs;
    private final CompareService compare;
    private final AccessControlService access;

    /** 兼容 RPT-01 的单测装配（仅覆盖解析/明细/列表三端点）。 */
    public ReportController(ReportIngestService ingest, ReportQueryService query,
                            AccessControlService access) {
        this(ingest, query, null, null, null, access);
    }

    @Autowired
    public ReportController(ReportIngestService ingest, ReportQueryService query,
                            ReportTreeService tree, ReportLogService logs,
                            CompareService compare, AccessControlService access) {
        this.ingest = ingest;
        this.query = query;
        this.tree = tree;
        this.logs = logs;
        this.compare = compare;
        this.access = access;
    }

    /**
     * 解析 output.xml 并入库。outputXmlPath 可选；不传时由服务层按 run_artifact / artifacts_dir 推导。
     */
    @PostMapping("/{runId}/parse")
    public ApiResponse<ReportDetailView> parse(@PathVariable String runId,
                                              @RequestParam long projectId,
                                              @RequestParam(required = false) String outputXmlPath) {
        access.bindProject(projectId);
        return ApiResponse.ok(ReportDetailView.from(ingest.parseAndIngest(projectId, runId, outputXmlPath)));
    }

    @GetMapping("/{runId}")
    public ApiResponse<ReportDetailView> detail(@PathVariable String runId,
                                                @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(ReportDetailView.from(query.detail(projectId, runId)));
    }

    @GetMapping
    public ApiResponse<PageResult<ReportListItem>> page(@RequestParam long projectId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        access.bindProject(projectId);
        PageResult<ReportRecord> result = query.page(projectId, page, Math.min(size, 100));
        List<ReportListItem> items = result.items().stream().map(ReportListItem::from).toList();
        return ApiResponse.ok(new PageResult<>(items, result.total(), result.page(), result.size()));
    }

    /**
     * 套件树（RPT-02）：套件名/状态、用例 caseKey/verdict/durationMs/failureMsg、关键字步骤树。
     * 日志正文不在本接口返回。
     */
    @GetMapping("/{runId}/tree")
    public ApiResponse<ReportTreeService.TreeSuite> tree(@PathVariable String runId,
                                                         @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(tree.tree(projectId, runId));
    }

    /**
     * 用例日志分段读取（RPT-02）：默认 200 行、上限 1000 行；nextOffset 为 null 表示已到末尾。
     */
    @GetMapping("/{runId}/logs")
    public ApiResponse<ReportLogService.LogPage> logs(@PathVariable String runId,
                                                      @RequestParam long projectId,
                                                      @RequestParam String testKey,
                                                      @RequestParam(defaultValue = "0") int offset,
                                                      @RequestParam(defaultValue = "200") int limit) {
        access.bindProject(projectId);
        return ApiResponse.ok(logs.logs(projectId, runId, testKey, offset, limit));
    }

    /**
     * 双列并置对比（RPT-03）：baselineRunId 可选；不传时按 DEV-PLAN §4.2 自动选基线。
     */
    @GetMapping("/{runId}/compare")
    public ApiResponse<CompareService.CompareResult> compare(
            @PathVariable String runId,
            @RequestParam long projectId,
            @RequestParam(required = false) String baselineRunId) {
        access.bindProject(projectId);
        return ApiResponse.ok(compare.compare(projectId, runId, baselineRunId));
    }

    /**
     * 手动基线候选（RPT-03 补口）：只返回同项目同环境的 finished 运行，最多 20 条。
     */
    @GetMapping("/{runId}/baseline-candidates")
    public ApiResponse<List<CompareService.BaselineCandidate>> baselineCandidates(
            @PathVariable String runId,
            @RequestParam long projectId) {
        access.bindProject(projectId);
        return ApiResponse.ok(compare.candidates(projectId, runId));
    }

    /** 计数结构：与前端约定的 pass / fail / skip 三段。 */
    public record ReportCounts(int pass, int fail, int skip) {

        static ReportCounts from(ReportRecord record) {
            return new ReportCounts(record.passCount(), record.failCount(), record.skipCount());
        }
    }

    /** 报告详情。 */
    /**
     * 报告详情。
     *
     * <p><b>为什么要带判据结论（v5.8.0 / FR-v5.8-01）</b>：结论原本只在快照、导出件与视图渲染里，
     * 详情接口不返回，界面上就没有任何一处能让人看到「这批到底算不算通过」。用户要么导出、
     * 要么自己数 pass/fail —— 而按 failCount 反推结论正是 v5.7.0 要消除的口径。
     * 这里直接透出 {@link ReportRecord#effectiveConclusion()}：未声明判据时恒为
     * {@code UNDETERMINED}，**不回落**成「失败数为 0 就 PASS」。
     *
     * <p>同时带两个计数，让界面能区分「全都没声明判据」和「声明了但缺一条」：
     * {@code criteriaEvaluated} 参与评估的用例数，{@code criteriaMissingCount} 判据缺失数。
     */
    public record ReportDetailView(String runId,
                                   long projectId,
                                   String state,
                                   boolean exportable,
                                   List<String> metadataMissing,
                                   Map<String, String> metadata,
                                   ReportCounts counts,
                                   Instant startedAt,
                                   Instant finishedAt,
                                   String criteriaConclusion,
                                   int criteriaEvaluated,
                                   int criteriaMissingCount) {

        static ReportDetailView from(ReportRecord record) {
            return new ReportDetailView(record.runId(), record.projectId(), record.state(),
                    record.exportable(), record.metadataMissing(), record.metadata(),
                    ReportCounts.from(record), record.startedAt(), record.finishedAt(),
                    record.effectiveConclusion(), record.criteriaEvaluated(),
                    record.criteriaMissingCount());
        }
    }

    /** 报告列表项，供前端报告页直接渲染。 */
    public record ReportListItem(String runId,
                                 String state,
                                 boolean exportable,
                                 List<String> metadataMissing,
                                 ReportCounts counts,
                                 Instant startedAt,
                                 Instant finishedAt) {

        static ReportListItem from(ReportRecord record) {
            return new ReportListItem(record.runId(), record.state(), record.exportable(),
                    record.metadataMissing(), ReportCounts.from(record),
                    record.startedAt(), record.finishedAt());
        }
    }
}
