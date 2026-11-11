package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CaseImportService;
import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.application.caseasset.CapabilityService;
import com.qa.autotest.application.caseasset.GlobalCaseService;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.common.PageResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 用例台账 / 能力索引 / Excel 导入（CAS-02 / CAS-03 / CAS-05）。 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class CaseController {

    public record SubmitRequest(String action, String comment) {
    }

    public record CommitRequest(List<CaseImportService.ImportRow> rows) {
    }

    /** 排序白名单：请求里的 key → 实际 SQL 列；未登记的字段一律 400（FR-3.2-02）。 */
    private static final Map<String, String> SORTS = Map.of(
            "id", "id",
            "case_code", "case_code",
            "title", "title",
            "priority", "priority",
            "review_status", "review_status",
            "updated_at", "updated_at",
            "created_at", "created_at");

    private final TestCaseService cases;
    private final CapabilityService capabilities;
    private final CaseImportService importer;

    /** OPT-02：统一详情契约的可选装配；既有测试用 3 参构造器直接 new，未注入时降级。 */
    private GlobalCaseService globalCases;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGlobalCases(GlobalCaseService globalCases) {
        this.globalCases = globalCases;
    }

    public CaseController(TestCaseService cases, CapabilityService capabilities,
                          CaseImportService importer) {
        this.cases = cases;
        this.capabilities = capabilities;
        this.importer = importer;
    }

    @GetMapping("/cases")
    public ApiResponse<PageResult<TestCase>> page(
            @PathVariable long projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String safetyLevel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Long beforeId) {
        PageQuery paging = beforeId == null
                ? PageQuery.of(page, size, sort, SORTS, "id")
                : PageQuery.keyset(size, beforeId);
        return ApiResponse.ok(cases.page(projectId, keyword, domain, priority, reviewStatus,
                capability, safetyLevel, paging));
    }

    /** FR-49-07：项目用例详情与公共库共用同一字段契约（四列补录 + 来源徽章 + 脱敏）。 */
    @GetMapping("/cases/{id}")
    public ApiResponse<GlobalCaseService.CaseDetailView> get(@PathVariable long projectId,
                                                             @PathVariable long id) {
        if (globalCases != null) {
            return ApiResponse.ok(globalCases.detailForProject(projectId, id));
        }
        // 纯单测装配（无 Spring 上下文）：仍返回同一 DTO 契约，库级补录/来源字段留空，
        // 且对 mock 返回 null 保持与原实现一致（data=null），不引入 NPE。
        TestCaseService.CaseDetail detail = cases.get(projectId, id);
        TestCase tc = detail == null ? null : detail.testCase();
        if (tc == null) {
            return ApiResponse.ok(null);
        }
        boolean localOverride = "local_override".equals(tc.source());
        return ApiResponse.ok(new GlobalCaseService.CaseDetailView(
                tc.id() == null ? 0L : tc.id(), tc.caseCode(), tc.caseCode(), tc.title(),
                tc.category(), "", tc.priority(), tc.safetyLevel(), "active", 1, "", "",
                "", "", "", "", "", null, localOverride,
                localOverride ? "LOCAL_OVERRIDE" : "LOCAL", "****"));
    }

    @PostMapping("/cases")
    public ApiResponse<TestCaseService.CaseDetail> create(
            @PathVariable long projectId,
            @RequestBody TestCaseService.CaseCommand command) {
        return ApiResponse.ok(cases.create(projectId, command));
    }

    @PutMapping("/cases/{id}")
    public ApiResponse<TestCaseService.CaseDetail> update(
            @PathVariable long projectId, @PathVariable long id,
            @RequestBody TestCaseService.CaseCommand command) {
        return ApiResponse.ok(cases.update(projectId, id, command));
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> delete(@PathVariable long projectId, @PathVariable long id) {
        cases.delete(projectId, id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/cases/{id}/submit")
    public ApiResponse<TestCaseService.CaseDetail> submit(@PathVariable long projectId,
                                                          @PathVariable long id) {
        return ApiResponse.ok(cases.submit(projectId, id));
    }

    /** action=approve / reject。 */
    @PostMapping("/cases/{id}/review")
    public ApiResponse<TestCaseService.CaseDetail> review(@PathVariable long projectId,
                                                          @PathVariable long id,
                                                          @RequestBody SubmitRequest request) {
        boolean approved = "approve".equalsIgnoreCase(request.action());
        return ApiResponse.ok(cases.review(projectId, id, approved, request.comment()));
    }

    /**
     * 批量操作（UX-02，FR-v0.2-36~39）：部分成功返回 200 + 逐条结果。
     *
     * <p>仅参数级错误（ids 空 / 超 200 / action 未知 / 动作专属参数缺失）返回 400。
     */
    @PostMapping("/cases/batch")
    public ApiResponse<TestCaseService.BatchResult> batch(
            @PathVariable long projectId,
            @RequestBody TestCaseService.BatchRequest request) {
        return ApiResponse.ok(cases.batch(projectId, request));
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<CapabilityStat>> capabilities(@PathVariable long projectId) {
        return ApiResponse.ok(capabilities.list(projectId));
    }

    @PostMapping("/cases/import/preview")
    public ApiResponse<CaseImportService.ImportPreview> preview(
            @PathVariable long projectId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(importer.preview(projectId, file.getInputStream()));
    }

    @PostMapping("/cases/import/commit")
    public ApiResponse<CaseImportService.ImportResult> commit(
            @PathVariable long projectId, @RequestBody CommitRequest request) {
        return ApiResponse.ok(importer.commit(projectId, request.rows()));
    }
}
