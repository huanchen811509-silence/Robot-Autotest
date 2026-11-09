package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.RunPrecheckService;
import com.qa.autotest.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运行提交前预检（FR-3.1-06）。 */
@RestController
@RequestMapping("/api/execution")
public class PrecheckController {

    private final RunPrecheckService precheck;

    public PrecheckController(RunPrecheckService precheck) {
        this.precheck = precheck;
    }

    @PostMapping("/precheck")
    public ApiResponse<RunPrecheckService.Result> precheck(@RequestBody PrecheckRequest request) {
        return ApiResponse.ok(precheck.run(new RunPrecheckService.Request(
                request.projectId(), request.testSetId(), request.planId(), request.caseIds(),
                request.shards() == null ? 1 : request.shards(), request.nodeId())));
    }

    public record PrecheckRequest(long projectId, Long testSetId, Long planId, List<Long> caseIds,
                                  Integer shards, Long nodeId) {
    }
}
