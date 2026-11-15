package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.DeviceCredentialBindingService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工关联 / 解绑凭证（v5.13.0 / W63-07、08）。
 *
 * <p>独立 Controller 且类级只挂 {@code /api}：既有 {@code GlobalAssetController} 的
 * {@code PUT /{type}/{id}} 是两段路径，新端点若挂同前缀会与它抢匹配；写成完整路径
 * 并以 {@code \d+} 约束 deviceId，Spring 会稳定选到字面量更具体的这一条。
 */
@RestController
@RequestMapping("/api")
public class DeviceCredentialBindingController {

    public record BindingRequest(Long managementCredentialId, Long bmcCredentialId) {
    }

    private final DeviceCredentialBindingService bindings;
    private final AccessControlService access;
    private final PlatformProjectService platform;

    public DeviceCredentialBindingController(DeviceCredentialBindingService bindings,
                                             AccessControlService access,
                                             PlatformProjectService platform) {
        this.bindings = bindings;
        this.access = access;
        this.platform = platform;
    }

    /** 项目侧：只写本项目可见设备与本项目可见凭证。 */
    @PutMapping("/projects/{projectId:\\d+}/assets/{deviceId:\\d+}/credentials")
    public ApiResponse<DeviceCredentialBindingService.BindingResult> bindProject(
            @PathVariable long projectId,
            @PathVariable long deviceId,
            @RequestBody(required = false) BindingRequest body) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        return ApiResponse.ok(bind(body, projectId, deviceId, platform.id()));
    }

    /** 全局侧：平台项目口径，凭证必须是平台可见。 */
    @PutMapping("/global/assets/{deviceId:\\d+}/credentials")
    public ApiResponse<DeviceCredentialBindingService.BindingResult> bindGlobal(
            @PathVariable long deviceId,
            @RequestBody(required = false) BindingRequest body) {
        access.requireGlobalEnter();
        long projectId = platform.id();
        return ApiResponse.ok(bind(body, projectId, deviceId, projectId));
    }

    /** 项目侧解绑：两面同时清空。 */
    @DeleteMapping("/projects/{projectId:\\d+}/assets/{deviceId:\\d+}/credentials")
    public ApiResponse<DeviceCredentialBindingService.BindingResult> clearProject(
            @PathVariable long projectId,
            @PathVariable long deviceId) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        return ApiResponse.ok(bindings.clear(projectId, deviceId, platform.id()));
    }

    private DeviceCredentialBindingService.BindingResult bind(BindingRequest body,
                                                              long projectId, long deviceId,
                                                              long platformId) {
        return bindings.bind(projectId, deviceId,
                body == null ? null : body.managementCredentialId(),
                body == null ? null : body.bmcCredentialId(), platformId);
    }
}
