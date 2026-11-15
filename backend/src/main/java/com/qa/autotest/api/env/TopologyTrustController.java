package com.qa.autotest.api.env;

import com.qa.autotest.application.env.TopologyTrustService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 拓扑三源分级与 LLDP 导入（v5.15.0 / FR-v5.15-05~06）。 */
@RestController
@RequestMapping("/api/projects/{projectId}/topology")
public class TopologyTrustController {

    public record ImportRequest(List<TopologyTrustService.Neighbor> neighbors) {
    }

    private final TopologyTrustService topology;
    private final AccessControlService access;

    public TopologyTrustController(TopologyTrustService topology, AccessControlService access) {
        this.topology = topology;
        this.access = access;
    }

    @GetMapping("/trust")
    public ApiResponse<TopologyTrustService.TrustView> trust(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(topology.trust(projectId));
    }

    @PostMapping("/lldp-import")
    public ApiResponse<TopologyTrustService.ImportResult> importLldp(
            @PathVariable long projectId, @RequestBody ImportRequest request) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(topology.importLldp(projectId,
                request == null ? List.of() : request.neighbors()));
    }
}
