package com.qa.autotest.api.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ReportViewRenderer;
import com.qa.autotest.application.report.TestReportService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.TestReport;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 测试报告 API（W2）。所有读写先做项目访问校验，跨项目一律 403。 */
@RestController
@RequestMapping("/api/test-reports")
public class TestReportController {

    private final TestReportService reports;
    private final AccessControlService access;

    public TestReportController(TestReportService reports, AccessControlService access) {
        this.reports = reports;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<TestReport>> list(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(reports.list(projectId));
    }

    @PostMapping
    public ApiResponse<TestReport> create(@RequestBody CreateRequest request) {
        if (request == null || request.projectId() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 不能为空");
        }
        access.requireProjectAccess(request.projectId());
        ReportSourceType sourceType;
        try {
            sourceType = ReportSourceType.parse(request.sourceType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, e.getMessage());
        }
        if (sourceType == ReportSourceType.CASE_SET) {
            if (request.caseSetId() == null) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "按用例集建报告时 caseSetId 不能为空");
            }
            return ApiResponse.ok(reports.createFromCaseSet(request.projectId(),
                    request.caseSetId(), request.runId(), request.title()));
        }
        return ApiResponse.ok(reports.create(request.projectId(), request.runId(), request.title()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> render(@PathVariable long id,
                                                   @RequestParam(defaultValue = "true")
                                                   boolean external) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.render(id, external));
    }

    /**
     * 报告冻结时绑定的模板（TPLX-02）。
     *
     * <p>刻意单独开一个只读接口而不是塞进渲染结果：渲染结果受 C5 白名单自检保护，
     * 往里加 templateId 这种键就得给白名单开口子，而模板回指是**元信息**不是报告字段。
     */
    @GetMapping("/{id}/template")
    public ApiResponse<ReportViewRenderer.TemplateRef> template(@PathVariable long id) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.template(id));
    }

    @PostMapping("/{id}/freeze")
    public ApiResponse<TestReport> freeze(@PathVariable long id) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.freeze(id));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<TestReport> revoke(@PathVariable long id) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.transition(id,
                com.qa.autotest.domain.report.ReportState.REVOKED));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<TestReport> archive(@PathVariable long id) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.transition(id,
                com.qa.autotest.domain.report.ReportState.ARCHIVED));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id, @RequestParam String format) {
        access.requireProjectAccess(reports.find(id).projectId());
        byte[] bytes = reports.export(id, format);
        String ext = com.qa.autotest.application.report.ReportExportService.extension(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"report-" + id + "." + ext + "\"")
                .contentType(MediaType.parseMediaType(
                        com.qa.autotest.application.report.ReportExportService.contentType(format)))
                .body(bytes);
    }

    @GetMapping("/{id}/exports")
    public ApiResponse<?> exports(@PathVariable long id) {
        access.requireProjectAccess(reports.find(id).projectId());
        return ApiResponse.ok(reports.exports(id));
    }

    /**
     * 创建请求体：只收项目 / 来源 / run / 标题，不接受任何外部传入的快照内容。
     *
     * <p>v3.10.0 起多了两个来源字段（FR-v3.10-03）：
     * <ul>
     *   <li>{@code sourceType}：`run`（默认，旧客户端不发这个字段）或 `case_set`；</li>
     *   <li>{@code caseSetId}：按用例集建时必填。</li>
     * </ul>
     * `runId` 在按用例集建时**可空**（空 = 取该集最近一次执行），非空则必须属于该集。
     */
    public record CreateRequest(Long projectId, String runId, String title,
                                String sourceType, Long caseSetId) {
    }
}
