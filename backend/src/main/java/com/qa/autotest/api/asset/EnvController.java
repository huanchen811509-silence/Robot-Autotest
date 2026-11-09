package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.EnvProfileService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.asset.EnvProfileSummary;
import com.qa.autotest.domain.asset.EnvSnapshot;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 环境快照与 diff（AST-02）。 */
@RestController
@RequestMapping("/api/projects/{projectId}/snapshots")
public class EnvController {

    public record SnapshotRequest(String name, String profileType, String description,
                                  String snapshotJson) {
    }

    public record SnapshotResponse(long id) {
    }

    private final EnvProfileService profiles;

    public EnvController(EnvProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * 环境名清单（FR-v3.11-02）。
     *
     * <p>放在 `/profiles` 而不是 `/`：根路径的 GET 已经被「按 name 查快照列表」占用，
     * 不能靠 `name` 参数有没有传来切换语义——那会让「忘了传参」变成一个静默的
     * 不同接口。它是纯 GET，不带任何写路径。
     */
    @GetMapping("/profiles")
    public ApiResponse<List<EnvProfileSummary>> environments(@PathVariable long projectId) {
        return ApiResponse.ok(profiles.environments(projectId));
    }

    @GetMapping
    public ApiResponse<List<EnvSnapshot>> list(@PathVariable long projectId,
                                               @RequestParam String name) {
        return ApiResponse.ok(profiles.list(projectId, name));
    }

    @GetMapping("/latest")
    public ApiResponse<EnvSnapshot> latest(@PathVariable long projectId,
                                           @RequestParam String name) {
        return ApiResponse.ok(profiles.latest(projectId, name));
    }

    @PostMapping
    public ApiResponse<SnapshotResponse> create(@PathVariable long projectId,
                                                @RequestBody SnapshotRequest request) {
        long id = profiles.snapshot(projectId, request.name(), request.profileType(),
                request.description(), request.snapshotJson());
        return ApiResponse.ok(new SnapshotResponse(id));
    }

    /** 两份快照的差异；由前端把快照原文一并取走后展示。 */
    @PostMapping("/diff")
    public ApiResponse<List<EnvProfileService.DiffItem>> diff(@RequestBody DiffRequest request) {
        return ApiResponse.ok(profiles.diff(request.before(), request.after()));
    }

    public record DiffRequest(EnvSnapshot before, EnvSnapshot after) {
    }
}
