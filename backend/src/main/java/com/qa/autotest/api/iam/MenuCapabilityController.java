package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户菜单能力（v4.9.2 / OPT-08 / FR-49-31~34）。
 *
 * <p>前端导航只按服务端下发的 capability 数组做显隐；<b>真正的越权由服务端兜底</b>：
 * 敏感设置页接口在返回数据前调用 {@link AccessControlService#requireCapability(String)}，
 * 不具备能力的角色一律 403，绝不依赖前端把菜单藏起来。
 *
 * <p>6 个设置页对应的敏感能力 {@code ai:config} / {@code user:write} / {@code audit:read}
 * 统一走 {@code /check} 守卫：任何设置页路由进入前先打一次该接口（或由对应业务接口内联
 * 调用 {@code requireCapability}），engineer / viewer 命中即 403。
 */
@RestController
@RequestMapping("/api/me/menu-capabilities")
public class MenuCapabilityController {

    private final AccessControlService access;

    public MenuCapabilityController(AccessControlService access) {
        this.access = access;
    }

    /** 能力字符串数组，如 ["settings:read","ai:config","user:write","audit:read",...]。 */
    @GetMapping
    public ApiResponse<List<String>> capabilities() {
        return ApiResponse.ok(access.currentMenuCapabilities());
    }

    /**
     * 设置页逐页鉴权守卫：{@code requireCapability} 命中能力返回 200，否则 403。
     * 前端可在渲染敏感设置页前调用，也可由业务接口内联同一方法，口径唯一。
     */
    @GetMapping("/check")
    public ApiResponse<List<String>> check(@RequestParam String capability) {
        access.requireCapability(capability);
        return ApiResponse.ok(List.of(capability));
    }
}
