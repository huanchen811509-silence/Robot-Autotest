package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.ProjectCompositeService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目向导一次性创建（v5.10.0 / FR-v5.10-10）。
 *
 * <p>独立 Controller 而不是塞进 {@link ProjectController}：后者的构造器有「一参 / 两参」两套
 * 历史装配（单测 {@code standaloneSetup} 直接用一参），再挂一个依赖就得改它的构造链，
 * 牵连一批不相干的用例。新端点自带一个只有一项依赖的控制器，改动面最小。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectCompositeController {

    private final ProjectCompositeService composite;

    public ProjectCompositeController(ProjectCompositeService composite) {
        this.composite = composite;
    }

    /** 向导第 8 步唯一写入口：项目 + 成员 + 组授权 + 资产 + 凭证 + 内容，一个事务。 */
    @PostMapping("/composite")
    public ApiResponse<ProjectCompositeService.CompositeResult> create(
            @RequestBody ProjectCompositeService.CompositeRequest request) {
        return ApiResponse.ok(composite.create(request));
    }
}
