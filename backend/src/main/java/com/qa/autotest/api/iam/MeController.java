package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户偏好（v4.5.0 / FR-v4.5-02）：记住上次界面模式。
 *
 * <p>无权限时不改库值，只回落 project；这样管理员临时降权再恢复后仍能回到全局模式。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    public record UiModeRequest(String mode) {
    }

    private final UserRepository users;
    private final AccessControlService access;

    public MeController(UserRepository users, AccessControlService access) {
        this.users = users;
        this.access = access;
    }

    @GetMapping("/ui-mode")
    public ApiResponse<Map<String, String>> uiMode() {
        long userId = TenantContext.requireUserId();
        String mode = users.defaultUiMode(userId);
        if ("global".equals(mode) && !access.canEnterGlobal(userId)) {
            mode = "project";
        }
        return ApiResponse.ok(Map.of("mode", mode));
    }

    @PutMapping("/ui-mode")
    public ApiResponse<Map<String, String>> update(@RequestBody UiModeRequest request) {
        long userId = TenantContext.requireUserId();
        String mode = request.mode() == null ? "" : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!"global".equals(mode) && !"project".equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "界面模式只能是 global/project");
        }
        if ("global".equals(mode) && !access.canEnterGlobal(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权进入全局模式");
        }
        users.updateDefaultUiMode(userId, mode);
        return ApiResponse.ok(Map.of("mode", mode));
    }
}
