package com.qa.autotest.application.repository;

import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import java.util.Optional;

/** 设备与端口仓储（所有方法强制带 projectId，tenant 守卫覆盖）。 */
public interface DeviceRepository {

    PageResult<Device> page(long projectId, String keyword, Integer status, String domain,
                            int page, int size);

    Optional<Device> find(long projectId, long id);

    long insert(Device device, Long operatorId);

    void update(Device device, Long operatorId);

    void delete(long projectId, long id);

    boolean serialExists(long projectId, String serialNo, Long excludeId);

    List<DevicePort> ports(long projectId, long deviceId);

    /** 本项目可见（私有 + 已引用平台）的全部端口，供连接/拓扑派生使用（v4.3.0）。 */
    List<DevicePort> portsOfVisibleDevices(long projectId);

    /** 本项目可见的全部设备（不分页、不截断），供连接/拓扑派生使用（v4.3.0）。 */
    List<Device> listAll(long projectId);

    Optional<DevicePort> findPort(long projectId, long portId);

    long insertPort(long projectId, DevicePort port, Long operatorId);

    void updatePort(long projectId, DevicePort port, Long operatorId);

    void deletePort(long projectId, long portId);

    boolean portNameExists(long projectId, long deviceId, String portName, Long excludeId);

    /** 项目中已使用的技术域标签（设备侧）。 */
    List<String> domains(long projectId);
}
