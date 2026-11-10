package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产共享池（AST-05）：私有资产「共享到平台」、项目「引用 / 解除引用」平台资产。
 *
 * <p>写操作口径与资产本体一致：**只有归属项目能共享自己的资产**；引用只对平台共享资产开放，
 * 别人的私有资产一律 403（不是 404——避免用状态码探测别的项目有什么设备，PRJ-03 同一判据）。
 *
 * <p>本包不做「平台资产转私有」和「删除引用保护」，那两条在 PRJ-03。
 */
@Service
public class ProjectAssetService {

    private static final Set<String> TYPES = Set.of("DEVICE", "LINK", "CREDENTIAL");

    private final ProjectAssetRepository assets;
    private final AccessControlService access;
    private final AuditService audit;

    /** v4.3.0：共享/引用/解除引用后发缓存失效事件（FR-v4.3-05 T1）。 */
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public ProjectAssetService(ProjectAssetRepository assets, AccessControlService access,
                               AuditService audit) {
        this.assets = assets;
        this.access = access;
        this.audit = audit;
    }

    /** 把本项目私有资产转为平台共享；共享后本项目立即补一条引用，视图不断。 */
    @Transactional
    public void share(String assetType, long assetId) {
        String type = normalizeType(assetType);
        ProjectAssetRepository.AssetScope scope = requireAsset(type, assetId);
        if (scope.platform()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该资产已经是平台共享状态");
        }
        if (scope.projectId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "资产归属缺失，无法共享：请先修复数据");
        }
        long ownerProjectId = scope.projectId();
        access.bindProject(ownerProjectId);
        access.require(resourceOf(type), PermissionAction.UPDATE);
        assets.shareToPlatform(type, assetId, ownerProjectId);
        assets.addReference(ownerProjectId, type, assetId, null, TenantContext.userId());
        audit.record("asset.share", type.toLowerCase(Locale.ROOT), String.valueOf(assetId),
                "owner_project=" + ownerProjectId);
        publish(ownerProjectId, type, assetId);
    }

    /** 项目引用一条平台共享资产。 */
    public void reference(long projectId, String assetType, long assetId) {
        String type = normalizeType(assetType);
        access.bindProject(projectId);
        access.require(resourceOf(type), PermissionAction.UPDATE);
        ProjectAssetRepository.AssetScope scope = requireAsset(type, assetId);
        if (!scope.platform()) {
            if (scope.projectId() != null && scope.projectId() == projectId) {
                return; // 本项目的私有资产，不需要也不允许重复引用
            }
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权引用其他项目的私有资产");
        }
        assets.addReference(projectId, type, assetId, null, TenantContext.userId());
        audit.record("asset.reference", type.toLowerCase(Locale.ROOT), String.valueOf(assetId),
                "project=" + projectId);
        publish(projectId, type, assetId);
    }

    /** 只解除引用，不动物资本体。 */
    public void unreference(long projectId, String assetType, long assetId) {
        String type = normalizeType(assetType);
        access.bindProject(projectId);
        access.require(resourceOf(type), PermissionAction.UPDATE);
        if (!assets.removeReference(projectId, type, assetId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该项目没有引用这条资产");
        }
        audit.record("asset.unreference", type.toLowerCase(Locale.ROOT), String.valueOf(assetId),
                "project=" + projectId);
        publish(projectId, type, assetId);
    }

    private void publish(long projectId, String type, long assetId) {
        if (events != null) {
            events.publishEvent(new AssetChangedEvent(projectId, type, assetId));
        }
    }

    private ProjectAssetRepository.AssetScope requireAsset(String type, long assetId) {
        return assets.scopeOf(type, assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资产不存在"));
    }

    private static PermissionResource resourceOf(String type) {
        return "CREDENTIAL".equals(type) ? PermissionResource.CREDENTIAL : PermissionResource.ASSET;
    }

    static String normalizeType(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("资产类型不能为空");
        }
        String type = assetType.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("资产类型只支持 DEVICE/LINK/CREDENTIAL：" + assetType);
        }
        return type;
    }
}
