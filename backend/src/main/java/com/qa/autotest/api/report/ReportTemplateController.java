package com.qa.autotest.api.report;

import com.qa.autotest.application.report.ReportTemplateService;
import com.qa.autotest.application.report.ReportTemplateView;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报告模板接口（TPLX-01，v2.1）。
 *
 * <p>`GET /fields` 是**前端勾选项的唯一来源**：界面不许内联一份字段名单，
 * 否则白名单收紧后界面还能选到已禁用的字段，出现「选得上、导出被拒」的死角。
 * 这一半拦截与后端 400 一起构成 DEV-PLAN 要求的「双拦截」。
 */
@RestController
@RequestMapping("/api/report-templates")
public class ReportTemplateController {

    private final ReportTemplateService templates;

    public ReportTemplateController(ReportTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public ApiResponse<List<ReportTemplateView>> list(@RequestParam long projectId) {
        return ApiResponse.ok(templates.list(projectId));
    }

    /** 可选字段清单（C5 白名单顺序；v5.14.0 起可按 kernel 取专属字段）。 */
    @GetMapping("/fields")
    public ApiResponse<List<String>> fields(@RequestParam(required = false) String kernel) {
        return ApiResponse.ok(templates.selectableFields(kernel));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportTemplateView> get(@RequestParam long projectId, @PathVariable long id) {
        return ApiResponse.ok(templates.get(projectId, id));
    }

    @PostMapping
    public ApiResponse<ReportTemplateView> create(@RequestBody TemplateRequest request) {
        return ApiResponse.ok(templates.create(request.toCommand()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ReportTemplateView> update(@PathVariable long id,
                                                  @RequestBody TemplateRequest request) {
        return ApiResponse.ok(templates.update(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@RequestParam long projectId, @PathVariable long id) {
        templates.delete(projectId, id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/default")
    public ApiResponse<ReportTemplateView> setDefault(@RequestParam long projectId,
                                                      @PathVariable long id) {
        return ApiResponse.ok(templates.setDefault(projectId, id));
    }

    public record FieldRequest(String fieldKey, String titleOverride) {
    }

    public record TemplateRequest(Long projectId, String name, String description,
                                  List<FieldRequest> fields, String kernel) {

        ReportTemplateService.TemplateCommand toCommand() {
            List<ReportTemplateService.FieldCommand> mapped = fields == null ? List.of()
                    : fields.stream()
                            .map(f -> f == null ? null
                                    : new ReportTemplateService.FieldCommand(f.fieldKey(), f.titleOverride()))
                            .toList();
            return new ReportTemplateService.TemplateCommand(
                    projectId == null ? 0L : projectId, name, description, mapped, kernel);
        }
    }
}
