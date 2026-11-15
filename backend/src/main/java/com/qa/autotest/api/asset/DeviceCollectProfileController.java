package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.DeviceCollectProfile;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.DeviceCollectProfileRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集画像只读端点（v5.13.0 / FR-v5.13-01、FR-v5.13-10）。
 *
 * <p>全局资产页没有项目上下文，可显式传平台项目 id；其余情况一律以当前租户为准。
 * 显式传入的项目 id 必须与当前租户一致（或就是平台项目），否则 403 —— 否则
 * 「换一个 projectId 就能读到别人项目设备」会成为新的越权面（NFR-v5.13-06）。
 */
@RestController
@RequestMapping("/api/assets")
public class DeviceCollectProfileController {

    private final DeviceCollectProfileRepository profiles;
    private final AccessControlService access;
    private final PlatformProjectService platform;

    public DeviceCollectProfileController(DeviceCollectProfileRepository profiles,
                                          AccessControlService access,
                                          PlatformProjectService platform) {
        this.profiles = profiles;
        this.access = access;
        this.platform = platform;
    }

    @GetMapping("/collect-profiles")
    public ApiResponse<List<DeviceCollectProfile>> profiles(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String state) {
        Long current = TenantContext.projectId();
        long scope;
        if (projectId == null) {
            scope = current == null ? platform.id() : current;
        } else {
            scope = projectId;
            if (!projectId.equals(current) && projectId != platform.id()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "不能查询其他项目的采集画像：" + projectId);
            }
        }
        access.bindProject(scope);
        return ApiResponse.ok(profiles.list(scope, deviceId, state));
    }
}
