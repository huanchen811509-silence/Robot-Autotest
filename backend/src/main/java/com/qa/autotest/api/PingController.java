package com.qa.autotest.api;

import com.qa.autotest.application.system.BuildInfoService;
import com.qa.autotest.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探测端点（FR-v0.1-06）。
 *
 * <p>前端请求封装（FR-v0.1-11）与 CI 冒烟都打这个端点；它不查库、不依赖外部服务，
 * 用来证明「骨架活着」。
 *
 * <p>v5.0.0：{@code version} 曾硬编码 {@code "v0.1"}，与 build-info 的 {@code app.version}
 * 及版本迭代页分属三套口径，导致「关于」页显示旧版本。现统一读
 * {@link BuildInfoService#info()} 的构建产物版本——该类只读 classpath 上的
 * {@code build-info.properties}，不查库、不触外部服务，不破坏本端点的探测语义。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private final BuildInfoService buildInfo;

    public PingController(BuildInfoService buildInfo) {
        this.buildInfo = buildInfo;
    }

    @GetMapping("/_ping")
    public ApiResponse<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "robot-autotest");
        body.put("version", buildInfo.info().get("version"));
        return ApiResponse.ok(body);
    }
}
