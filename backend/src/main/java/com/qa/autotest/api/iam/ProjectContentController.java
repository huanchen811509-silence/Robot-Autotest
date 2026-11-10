package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.ProjectTemplateService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目侧模板能力（v4.8.0 / FR-v4.8-04、05）：存为模板、向导内容步落库。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectContentController {

    private final ProjectTemplateService templates;

    public ProjectContentController(ProjectTemplateService templates) {
        this.templates = templates;
    }

    @PostMapping("/save-as-template")
    public ApiResponse<ProjectTemplateService.TemplateRow> saveAsTemplate(
            @PathVariable long projectId,
            @RequestParam String name,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String visibility) {
        return ApiResponse.ok(templates.saveAsTemplate(projectId, name, code, visibility));
    }

    @PostMapping("/content")
    public ApiResponse<ProjectTemplateService.ContentResult> content(
            @PathVariable long projectId,
            @RequestBody ProjectTemplateService.ContentRequest request) {
        return ApiResponse.ok(templates.applyContent(projectId, request));
    }
}
