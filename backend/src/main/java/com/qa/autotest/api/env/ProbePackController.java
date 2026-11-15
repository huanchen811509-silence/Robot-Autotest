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

    @GetMapping("/commands")
    public ApiResponse<ProbePackService.PackView> commands(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "HOST_LINUX") String deviceType) {
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
