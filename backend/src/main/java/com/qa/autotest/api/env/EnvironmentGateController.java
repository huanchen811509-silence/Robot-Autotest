package com.qa.autotest.api.env;

import com.qa.autotest.application.env.EnvironmentGateService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.EnvironmentGateRepository;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 执行前环境闸门（v5.15.0 / FR-v5.15-09）：独立端点，不触碰冻结执行链。 */
@RestController
@RequestMapping("/api/projects/{projectId}/env-check")
public class EnvironmentGateController {

    private final EnvironmentGateService gate;
    private final AccessControlService access;

    public EnvironmentGateController(EnvironmentGateService gate, AccessControlService access) {
        this.gate = gate;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<EnvironmentGateService.CheckResult> check(
            @PathVariable long projectId, @RequestBody EnvironmentGateService.CheckRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(gate.check(projectId, request));
    }

    @GetMapping("/notes")
    public ApiResponse<List<EnvironmentGateRepository.RunNote>> notes(
            @PathVariable long projectId, @RequestParam(defaultValue = "20") int limit) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(gate.notes(projectId, limit));
    }
}
