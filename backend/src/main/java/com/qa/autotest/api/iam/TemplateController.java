package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.ProjectTemplateService;
import com.qa.autotest.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板中心接口（v4.8.0 / FR-v4.8-01..09）。
 *
 * <p>模板是全局对象，统一挂在 {@code /api/global/templates}；创建项目时的「从模板建」与
 * 「存为模板」走项目作用域接口（{@link ProjectContentController}）。
 */
@RestController
@RequestMapping("/api/global/templates")
public class TemplateController {

    public record StatusRequest(String status) {
    }

    private final ProjectTemplateService service;

    public TemplateController(ProjectTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProjectTemplateService.TemplateRow>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String visibility) {
        return ApiResponse.ok(service.list(keyword, visibility));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectTemplateService.TemplateDetail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<ProjectTemplateService.TemplateRow> create(
            @RequestBody ProjectTemplateService.CreateTemplateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 模板清单更新（OPT-05 / FR-49-20/21）：builtin 内容改写 4xx 且 items 不变。 */
    @PutMapping("/{id}/content")
    public ApiResponse<ProjectTemplateService.TemplateRow> replaceContent(
            @PathVariable long id,
            @RequestBody ProjectTemplateService.CreateTemplateRequest request) {
        return ApiResponse.ok(service.replaceContent(id, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectTemplateService.TemplateRow> update(
            @PathVariable long id,
            @RequestBody ProjectTemplateService.TemplateRequest request,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.update(id, request, status));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<ProjectTemplateService.TemplateRow> status(
            @PathVariable long id, @RequestBody StatusRequest request) {
        return ApiResponse.ok(service.update(id, new ProjectTemplateService.TemplateRequest(
                null, null, null, null, null), request.status()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Boolean>> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<ProjectTemplateService.TemplateRow> copy(
            @PathVariable long id,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name) {
        return ApiResponse.ok(service.copy(id, code, name));
    }
}
