package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.UserService;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户偏好（v4.5.0 / FR-v4.5-02）：记住上次界面模式；v4.12.0 增改自己的口令。
 *
 * <p>无权限时不改库值，只回落 project；这样管理员临时降权再恢复后仍能回到全局模式。
 *
 * <p>改口令走 {@code /api/me/password} 而不是 {@code /api/users/{id}/reset-password}：
 * 前者身份由会话决定、请求体里没有 id；后者要求 {@code USER_ROLE:UPDATE}。放在这里，
 * 「改自己」在 URL 层面就不存在传别人 id 的可能。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    public record UiModeRequest(String mode) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    private final UserRepository users;
    private final AccessControlService access;
    private final UserService userService;

    public MeController(UserRepository users, AccessControlService access, UserService userService) {
        this.users = users;
        this.access = access;
        this.userService = userService;
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

    /**
     * 修改自己的口令（FR-v4.12-23）。
     *
     * <p>200 不代表「会话已刷新」：改完口令后旧会话仍然有效，这是刻意的——本版不引入
     * 「改密即踢出所有设备」（那会让正在跑的采集链路断开，收益远小于代价）。
     */
    @PostMapping("/password")
    public ApiResponse<Map<String, Boolean>> changePassword(
            @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request.oldPassword(), request.newPassword());
        return ApiResponse.ok(Map.of("changed", true));
    }
}
