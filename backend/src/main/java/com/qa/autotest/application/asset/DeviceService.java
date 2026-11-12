package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 设备与端口台账（AST-01）。 */
@Service
public class DeviceService {

    /**
     * 设备写入表单。
     *
     * <p>v5.2.0（W52-03）增 {@code hostKey} 与 {@code modelDictId}：
     * <ul>
     *   <li>{@code hostKey} 是 inventory 渲染的 hosts 键。留空则按
     *       {@code pytest.inventory_key_rule} 从名称与 IP 派生；显式填写可以让一台机器
     *       在改名后仍保持同一个键，从而不打断已有基线与证据的关联。</li>
     *   <li>{@code modelDictId} 指向网卡型号字典；为空表示按 {@code model} 文本猜（不猜），
     *       只做未映射统计。</li>
     * </ul>
     * 旧调用方按 12 参构造，行为完全不变（两字段为 null）。
     */
    public record DeviceCommand(String name, String deviceType, String model, String vendor,
                                String serialNo, String managementIp, String location,
                                String rackNo, String domainTag, boolean enabled, Long ownerId,
                                String hostKey, Long modelDictId) {

        /** v5.1.0 及以前的 11 参形态。 */
        public DeviceCommand(String name, String deviceType, String model, String vendor,
                             String serialNo, String managementIp, String location,
                             String rackNo, String domainTag, boolean enabled, Long ownerId) {
            this(name, deviceType, model, vendor, serialNo, managementIp, location, rackNo,
                    domainTag, enabled, ownerId, null, null);
        }
    }

    /**
     * 端口写入表单（v4.3.0 / FR-v4.3-03 增 {@code nicName}/{@code nicIndex}，均可空）。
     * 既有调用方不传时行为不变。
     */
    public record PortCommand(String portName, String portType, String protocol, String macAddress,
                              Integer speedGbps, Integer mtu, String ipAddress, String portStatus,
                              String nicName, Integer nicIndex) {
    }

    private final DeviceRepository devices;
    private final ProjectAssetRepository projectAssets;
    private final AccessControlService access;
    private final AuditService audit;

    /** v4.3.0：写操作后发缓存失效事件；单测手工构造服务时可为 null（不发布）。 */
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public DeviceService(DeviceRepository devices, ProjectAssetRepository projectAssets,
                         AccessControlService access, AuditService audit) {
        this.devices = devices;
        this.projectAssets = projectAssets;
        this.access = access;
        this.audit = audit;
    }

    public PageResult<Device> page(long projectId, String keyword, Integer status, String domain,
                                   int page, int size) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return devices.page(projectId, keyword, status, domain, page, size);
    }

    public Device get(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return devices.find(projectId, id)
                .orElseThrow(() -> assetNotFound("DEVICE", id, "设备不存在"));
    }

    public List<String> domains(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return devices.domains(projectId);
    }

    public Device create(long projectId, DeviceCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.CREATE);
        requireName(command.name());
        if (command.serialNo() != null && !command.serialNo().isBlank()
                && devices.serialExists(projectId, command.serialNo(), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "序列号已存在：" + command.serialNo());
        }
        long id = devices.insert(toDevice(null, projectId, command), TenantContext.userId());
        audit.record("device.create", "device", String.valueOf(id), "name=" + command.name());
        publishAssetChanged(projectId, "DEVICE", id);
        return get(projectId, id);
    }

    public Device update(long projectId, long id, DeviceCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        Device existing = devices.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        requireOwned(existing, projectId);
        requireName(command.name());
        if (command.serialNo() != null && !command.serialNo().isBlank()
                && devices.serialExists(projectId, command.serialNo(), id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "序列号已存在：" + command.serialNo());
        }
        devices.update(toDevice(id, projectId, command), TenantContext.userId());
        audit.record("device.update", "device", String.valueOf(id), "name=" + command.name());
        String oldHostKey = existing.hostKey();
        String newHostKey = requireHostKey(command.hostKey());
        if (!java.util.Objects.equals(oldHostKey, newHostKey)) {
            // 换 host key = 换 inventory 里的 hosts 键。历史基线与证据是按旧键关联的，
            // 这条审计是唯一能把「为什么这台机器的阈值对不上了」追回来的线索。
            audit.recordChange("device:host-key-change", "device", String.valueOf(id),
                    "hostKey=" + (oldHostKey == null ? "(按规则派生)" : oldHostKey),
                    "hostKey=" + (newHostKey == null ? "(按规则派生)" : newHostKey));
        }
        publishAssetChanged(projectId, "DEVICE", id);
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
        Device existing = devices.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        // FR-v0.2-51：被引用不可硬删（409），必须先于归属校验，否则平台资产会先落 403
        projectAssets.prepareHardDelete("DEVICE", id);
        requireOwned(existing, projectId);
        devices.delete(projectId, id);
        audit.record("device.delete", "device", String.valueOf(id), "");
        publishAssetChanged(projectId, "DEVICE", id);
    }

    public List<DevicePort> ports(long projectId, long deviceId) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        devices.find(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        return devices.ports(projectId, deviceId);
    }

    public DevicePort addPort(long projectId, long deviceId, PortCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.CREATE);
        Device device = devices.find(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        requireOwned(device, projectId);
        requireName(command.portName());
        if (devices.portNameExists(projectId, deviceId, command.portName(), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "端口名已存在：" + command.portName());
        }
        long id = devices.insertPort(projectId,
                toPort(null, deviceId, command), TenantContext.userId());
        audit.record("device.port-add", "device_port", String.valueOf(id),
                "device=" + deviceId + ",port=" + command.portName());
        publishAssetChanged(projectId, "DEVICE", deviceId);
        return devices.findPort(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "端口不存在"));
    }

    public DevicePort updatePort(long projectId, long portId, PortCommand command) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        DevicePort existing = devices.findPort(projectId, portId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "端口不存在"));
        requireOwnedPort(projectId, existing);
        requireName(command.portName());
        if (devices.portNameExists(projectId, existing.deviceId(), command.portName(), portId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "端口名已存在：" + command.portName());
        }
        devices.updatePort(projectId, toPort(portId, existing.deviceId(), command),
                TenantContext.userId());
        audit.record("device.port-update", "device_port", String.valueOf(portId), "");
        publishAssetChanged(projectId, "DEVICE", existing.deviceId());
        return devices.findPort(projectId, portId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "端口不存在"));
    }

    public void deletePort(long projectId, long portId) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.DELETE);
        DevicePort existing = devices.findPort(projectId, portId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "端口不存在"));
        requireOwnedPort(projectId, existing);
        devices.deletePort(projectId, portId);
        audit.record("device.port-delete", "device_port", String.valueOf(portId), "");
        publishAssetChanged(projectId, "DEVICE", existing.deviceId());
    }

    /** 写操作后通知缓存失效（v4.3.0 / FR-v4.3-05 T2）。 */
    private void publishAssetChanged(long projectId, String assetType, long assetId) {
        if (events != null) {
            events.publishEvent(new AssetChangedEvent(projectId, assetType, assetId));
        }
    }

    /**
     * 平台共享资产对引用方是只读的：归属项目仍是 owner_scope=PROJECT 的那一个。
     * 共享后本行 project_id 为 NULL，映射成 0，与本项目号不等即非归属。
     */
    private static void requireOwned(Device device, long projectId) {
        if (device.projectId() != projectId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台共享资产只能由归属项目修改");
        }
    }

    private void requireOwnedPort(long projectId, DevicePort port) {
        Device device = devices.find(projectId, port.deviceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "设备不存在"));
        requireOwned(device, projectId);
    }

    private static Device toDevice(Long id, long projectId, DeviceCommand c) {
        return new Device(id, projectId, c.name(), c.deviceType(), c.model(), c.vendor(),
                c.serialNo(), c.managementIp(), c.location(), c.rackNo(), c.domainTag(),
                c.enabled(), c.ownerId(), null, requireHostKey(c.hostKey()),
                null, null, null, null, c.modelDictId());
    }

    /**
     * inventory 标识（host key）的形态校验。
     *
     * <p>三条禁令都对应真实故障：含空白会在渲染出的 YAML 里变成两个键；
     * 含 {@code @} 会与 {@code hostname@ip} 规则混淆（同一个键可能被解析成两种含义）；
     * 含 {@code /} 会被当成路径片段。宁可让录入时红一次，也不要等到执行环境渲染出来才发现。
     */
    private static String requireHostKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.contains("@") || value.contains("/")
                || value.contains("\\") || value.contains(":")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "host key 不能包含空白、@、/、\\ 或 :（会与 inventory 键规则冲突）：" + raw);
        }
        return value;
    }

    private static DevicePort toPort(Long id, long deviceId, PortCommand c) {
        return new DevicePort(id, deviceId, c.portName(), c.portType(), c.protocol(),
                c.macAddress(), c.speedGbps(), c.mtu(), c.ipAddress(), c.portStatus(),
                c.nicName(), c.nicIndex());
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "名称不能为空");
        }
    }
}
