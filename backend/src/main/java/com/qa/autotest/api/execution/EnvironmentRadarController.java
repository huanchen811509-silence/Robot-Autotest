package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.EnvironmentRadarService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境健康雷达（FR-v5.3-07 / W53-07）。
 *
 * <p>只有一个只读端点。{@code days} 只接受 7 与 30，其它值按 7 处理并在返回体里
 * 带 {@code windowDays} 说明实际用的窗口——前端不该因为传了个 15 就得到一份
 * 「看起来是 15 天、其实是 7 天」的数据。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/environment-radar")
public class EnvironmentRadarController {

    private final EnvironmentRadarService service;
    private final AccessControlService access;

    public EnvironmentRadarController(EnvironmentRadarService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<EnvironmentRadarService.Radar> radar(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "7") int days) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.radar(projectId, days));
    }
}
