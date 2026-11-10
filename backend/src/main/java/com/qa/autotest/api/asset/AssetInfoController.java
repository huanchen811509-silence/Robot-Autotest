package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.AssetInfoResponse;
import com.qa.autotest.application.asset.AssetInfoService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接与拓扑接口（v4.3.0 / PRD §6）。
 *
 * <p>读取要 {@code ASSET/READ}，手工刷新要 {@code ASSET/UPDATE} 并写审计
 * {@code asset-info.refresh}。{@code scope} 只接受 {@code connections}/{@code topology}，
 * 未知值直接 400，避免前端拼错时静默返回空结构。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/asset-info")
public class AssetInfoController {

    private static final Set<String> KNOWN_SCOPES = Set.of("connections", "topology");
    private static final List<String> DEFAULT_SCOPES = List.of("connections", "topology");

    private final AssetInfoService assets;
    private final AccessControlService access;
    private final AuditService audit;

    public AssetInfoController(AssetInfoService assets, AccessControlService access,
                               AuditService audit) {
        this.assets = assets;
        this.access = access;
        this.audit = audit;
    }

    /** 刷新请求体：{@code scope} 缺省为两块全取；{@code force} 缺省为 true。 */
    public record RefreshRequest(List<String> scope, Boolean force) {
    }

    @GetMapping
    public ApiResponse<AssetInfoResponse> get(
            @PathVariable long projectId,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "true") boolean includeDisabled) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        validateScope(scope == null ? DEFAULT_SCOPES : List.of(scope.split(",")));
        // includeDisabled 只影响前端展示；接口始终全量返回并带 enabled 标记（PRD FR-v4.3-04 R5）
        return ApiResponse.ok(assets.get(projectId, false));
    }

    @PostMapping("/refresh")
    public ApiResponse<AssetInfoResponse> refresh(@PathVariable long projectId,
                                                  @RequestBody(required = false)
                                                  RefreshRequest request) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        List<String> scopes = request == null || request.scope() == null || request.scope().isEmpty()
                ? DEFAULT_SCOPES : request.scope();
        validateScope(scopes);
        boolean force = request == null || request.force() == null || request.force();
        audit.record("asset-info.refresh", "project", String.valueOf(projectId),
                "scope=" + String.join(",", scopes) + ",force=" + force);
        return ApiResponse.ok(assets.get(projectId, force));
    }

    private static void validateScope(List<String> scopes) {
        List<String> normalized = new ArrayList<>();
        for (String scope : scopes) {
            String value = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
            if (!KNOWN_SCOPES.contains(value)) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "scope 只支持 connections/topology：" + scope);
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "scope 不能为空");
        }
    }
}
