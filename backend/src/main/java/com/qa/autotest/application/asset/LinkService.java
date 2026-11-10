package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.LinkRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Link;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 链路台账（AST-02）：两端设备与端口必须存在且不能自环。 */
@Service
public class LinkService {

    /** 链路写入表单。 */
    public record LinkCommand(String name, String linkType, String protocol, Integer speedGbps,
                              Integer mtu, Long aDeviceId, Long aPortId, Long bDeviceId,
                              Long bPortId, String description, boolean enabled) {
    }

    private final LinkRepository links;
    private final DeviceRepository devices;
    private final ProjectAssetRepository projectAssets;
    private final AccessControlService access;
    private final AuditService audit;

    /** v4.3.0：链路写操作后发缓存失效事件。 */
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public LinkService(LinkRepository links, DeviceRepository devices,
                       ProjectAssetRepository projectAssets,
                       AccessControlService access, AuditService audit) {
        this.links = links;
        this.devices = devices;
        this.projectAssets = projectAssets;
        this.access = access;
        this.audit = audit;
    }

    public PageResult<Link> page(long projectId, String keyword, int page, int size) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return links.page(projectId, keyword, page, size);
    }

    public Link get(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return links.find(projectId, id)
                .orElseThrow(() -> assetNotFound("LINK", id, "链路不存在"));
    }

    public Link create(long projectId, LinkCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.CREATE);
        validate(projectId, command);
        long id = links.insert(toLink(null, projectId, command), TenantContext.userId());
        audit.record("link.create", "link", String.valueOf(id), "name=" + command.name());
        publishAssetChanged(projectId, "LINK", id);
        return get(projectId, id);
    }

    public Link update(long projectId, long id, LinkCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        Link existing = links.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "链路不存在"));
        requireOwned(existing, projectId);
        validate(projectId, command);
        links.update(toLink(id, projectId, command), TenantContext.userId());
        audit.record("link.update", "link", String.valueOf(id), "name=" + command.name());
        publishAssetChanged(projectId, "LINK", id);
        return get(projectId, id);
    }

    /** 无引用项目读平台资产返回 403 而不是 404，避免用状态码探测别的项目有什么资产。 */
    private BusinessException assetNotFound(String type, long id, String message) {
        boolean platform = projectAssets.scopeOf(type, id)
                .map(ProjectAssetRepository.AssetScope::platform).orElse(false);
        return platform
                ? new BusinessException(ErrorCode.FORBIDDEN, "无权访问该平台共享资产")
                : new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.DELETE);
        Link existing = links.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "链路不存在"));
        // FR-v0.2-51：被引用不可硬删（409），必须先于归属校验，否则平台资产会先落 403
        projectAssets.prepareHardDelete("LINK", id);
        requireOwned(existing, projectId);
        links.delete(projectId, id);
        audit.record("link.delete", "link", String.valueOf(id), "");
        publishAssetChanged(projectId, "LINK", id);
    }

    private void publishAssetChanged(long projectId, String assetType, long assetId) {
        if (events != null) {
            events.publishEvent(new AssetChangedEvent(projectId, assetType, assetId));
        }
    }

    /** 平台共享链路对引用方只读：归属项目仍是原项目，共享后 project_id 为 NULL（映射成 0）。 */
    private static void requireOwned(Link link, long projectId) {
        if (link.projectId() != projectId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台共享资产只能由归属项目修改");
        }
    }

    /** 校验：名称非空、两端设备存在、不能自环、端口属于对应设备。 */
    private void validate(long projectId, LinkCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "链路名称不能为空");
        }
        if (command.aDeviceId() == null || command.bDeviceId() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "链路两端设备不能为空");
        }
        if (command.aDeviceId().equals(command.bDeviceId())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "链路不能指向同一台设备");
        }
        devices.find(projectId, command.aDeviceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT, "A 端设备不存在"));
        devices.find(projectId, command.bDeviceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT, "B 端设备不存在"));
        if (command.aPortId() != null
                && devices.findPort(projectId, command.aPortId())
                        .filter(port -> port.deviceId().equals(command.aDeviceId()))
                        .isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "A 端端口不属于该设备");
        }
        if (command.bPortId() != null
                && devices.findPort(projectId, command.bPortId())
                        .filter(port -> port.deviceId().equals(command.bDeviceId()))
                        .isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "B 端端口不属于该设备");
        }
    }

    private static Link toLink(Long id, long projectId, LinkCommand c) {
        return new Link(id, projectId, c.name(), c.linkType(), c.protocol(), c.speedGbps(),
                c.mtu(), c.aDeviceId(), null, c.aPortId(), null, c.bDeviceId(), null,
                c.bPortId(), null, c.description(), c.enabled());
    }
}
