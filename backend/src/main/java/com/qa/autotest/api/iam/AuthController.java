package com.qa.autotest.api.iam;

import com.qa.autotest.api.AuthInterceptor;
import com.qa.autotest.application.iam.AuthService;
import com.qa.autotest.application.iam.LoginRateLimiter;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录 / 注销 / 当前用户（IAM-02）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserSummary user,
                                boolean passwordExpired) {
    }

    public record RenewResponse(Instant expiresAt) {
    }

    private final AuthService auth;
    private final LoginRateLimiter loginLimiter;

    public AuthController(AuthService auth, LoginRateLimiter loginLimiter) {
        this.auth = auth;
        this.loginLimiter = loginLimiter;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request,
                                            HttpServletRequest http) {
        String ip = clientIp(http);
        loginLimiter.requireAllowed(ip, request.username());
        try {
            AuthService.LoginResult result = auth.login(request.username(), request.password());
            loginLimiter.reset(ip, request.username());
            return ApiResponse.ok(new LoginResponse(result.token(), result.expiresAt(),
                    result.user(), result.passwordExpired()));
        } catch (RuntimeException failure) {
            loginLimiter.recordFailure(ip, request.username());
            throw failure;
        }
    }

    /** 代理场景取 X-Forwarded-For 第一跳；无代理头时用直连地址。 */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        Object token = request.getAttribute(AuthInterceptor.ATTR_TOKEN);
        auth.logout(token == null ? null : token.toString());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<UserSummary> me() {
        return ApiResponse.ok(auth.me(TenantContext.requireUserId()));
    }

    /** 会话续期（FR-v0.2-04）：前端在过期前 5 分钟调用，把有效期再推一个 TTL。 */
    @PostMapping("/renew")
    public ApiResponse<RenewResponse> renew(HttpServletRequest request) {
        Object token = request.getAttribute(AuthInterceptor.ATTR_TOKEN);
        return ApiResponse.ok(new RenewResponse(auth.renew(token == null ? null : token.toString())));
    }
}
