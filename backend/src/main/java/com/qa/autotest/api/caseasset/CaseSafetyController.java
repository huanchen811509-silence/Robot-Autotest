package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CaseSafetyService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 安全级建议与批量定级（v5.31.0 / FR-v5.31-02）。 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class CaseSafetyController {

    public record ApplyRequest(List<Long> caseIds, String categoryL1, String level) {
    }

    private final CaseSafetyService safety;

    public CaseSafetyController(CaseSafetyService safety) {
        this.safety = safety;
    }

    @GetMapping("/cases/safety/recommendations")
    public ApiResponse<List<CaseSafetyService.Recommendation>> recommendations(
            @PathVariable long projectId) {
        return ApiResponse.ok(safety.recommendations(projectId));
    }

    @PostMapping("/cases/safety/apply")
    public ApiResponse<CaseSafetyService.ApplyResult> apply(
            @PathVariable long projectId, @RequestBody ApplyRequest request) {
        return ApiResponse.ok(safety.apply(projectId, request.caseIds(),
                request.categoryL1(), request.level()));
    }
}
