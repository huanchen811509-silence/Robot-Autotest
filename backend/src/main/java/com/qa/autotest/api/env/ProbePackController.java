package com.qa.autotest.api.env;

import com.qa.autotest.application.env.ProbePackService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 探针包只读通道（v5.15.0 / FR-v5.15-04）：命令文本始终来自唯一真源。 */
@RestController
@RequestMapping("/api/probe-packs")
public class ProbePackController {

    private final ProbePackService packs;
    private final AccessControlService access;

    public ProbePackController(ProbePackService packs, AccessControlService access) {
        this.packs = packs;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<String>> codes(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(packs.codes());
    }

    /**
     * 按设备类型取探针包（v5.18.0 / FR-v5.18-05）。
     *
     * <p>v5.18.0 去掉了 `defaultValue = "HOST_LINUX"`：默认值会让「漏传类型」悄悄按 Linux
     * 采一整套，全项失败却看不出是调用方的问题。现在留空由服务层判 400 并点名支持的类型，
     * 判据 2「空 → 要求指定」才有落点。
     */
    @GetMapping("/commands")
    public ApiResponse<ProbePackService.PackView> commands(
            @RequestParam long projectId,
            @RequestParam(required = false) String deviceType) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(packs.commandsFor(deviceType));
    }

    @GetMapping("/evaluate")
    public ApiResponse<ProbePackService.Evaluation> evaluate(
            @RequestParam long projectId, @RequestParam long snapshotId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(packs.evaluate(projectId, snapshotId));
    }
}
