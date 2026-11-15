package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 设备 ↔ 凭证绑定的唯一写入口（v5.13.0 / W63-07、08、15）。
 *
 * <p>手工关联、解绑、采集后的自动填空共用这里的 SQL 常量：自动填空必须带
 * 「仅当列为空」条件，手工关联则是显式覆盖。把两处 SQL 收在一处，避免哪天
 * 有人给手工路径加了条件、自动填空却偷偷改成总是覆盖。
 *
 * <p>可见性判定不引 infrastructure（会与 infrastructure→application.repository 成环）：
 * 归属快照与「本项目是否引用」都走已注入的 {@link ProjectAssetRepository} 接口。
 */
@Service
public class DeviceCredentialBindingService {

    /** 自动填空：绝不覆盖非空绑定。GlobalAssetService.autoBindIfNull 直接引用本常量。 */
    public static final String SQL_FILL_IF_NULL = """
            UPDATE device SET management_credential_id = ?, updated_by = ?
             WHERE id = ? AND management_credential_id IS NULL
            """;

    /** 手工关联：null=不动、0=清空、>0=绑定（沿用 5.10 向导的参数语义）。 */
    public static final String SQL_BIND = """
            UPDATE device SET management_credential_id = ?, bmc_credential_id = ?,
                   updated_by = ? WHERE id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAssetRepository projectAssets;
    private final AuditService audit;

    public DeviceCredentialBindingService(JdbcTemplate jdbc,
                                          ProjectAssetRepository projectAssets,
                                          AuditService audit) {
        this.jdbc = jdbc;
        this.projectAssets = projectAssets;
        this.audit = audit;
    }

    public record BindingResult(long deviceId, Long managementCredentialId, Long bmcCredentialId) {
    }

    public BindingResult bind(long projectId, long deviceId, Long managementCredentialId,
                              Long bmcCredentialId, long platformId) {
        assertDeviceVisible(projectAssets, projectId, deviceId, platformId);
        if (managementCredentialId != null && managementCredentialId > 0) {
            assertCredentialVisible(projectAssets, projectId, managementCredentialId, platformId);
        }
        if (bmcCredentialId != null && bmcCredentialId > 0) {
            assertCredentialVisible(projectAssets, projectId, bmcCredentialId, platformId);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT management_credential_id, bmc_credential_id FROM device WHERE id = ?",
                deviceId);
        Map<String, Object> current = rows.isEmpty() ? Map.of() : rows.get(0);
        Object mgmt = managementCredentialId == null ? current.get("management_credential_id")
                : (managementCredentialId == 0 ? null : managementCredentialId);
        Object bmc = bmcCredentialId == null ? current.get("bmc_credential_id")
                : (bmcCredentialId == 0 ? null : bmcCredentialId);
        jdbc.update(SQL_BIND, bindArgs(mgmt, bmc, TenantContext.userId(), deviceId));
        audit.recordChange("asset.credential.bind", "device", String.valueOf(deviceId), null,
                "mgmt=" + mgmt + ";bmc=" + bmc + ";projectId=" + projectId
                        + ";operator=" + TenantContext.userId());
        return new BindingResult(deviceId, longOf(mgmt), longOf(bmc));
    }

    /** 包可见：参数数组单独成形，单测不必和 Mockito 的 varargs 捕获规则较劲。 */
    static Object[] bindArgs(Object mgmt, Object bmc, Long operator, long deviceId) {
        return new Object[] {mgmt, bmc, operator, deviceId};
    }

    /** 解绑：两面同时清空，画像立即回落 BLOCKED（画像查询期聚合，不滞后）。 */
    public BindingResult clear(long projectId, long deviceId, long platformId) {
        return bind(projectId, deviceId, 0L, 0L, platformId);
    }

    /** 采集成功后只填空；返回受影响行数，0 表示已有绑定、不覆盖。 */
    public int fillIfNull(long deviceId, long credentialId) {
        return jdbc.update(SQL_FILL_IF_NULL, credentialId, TenantContext.userId(), deviceId);
    }

    /** 设备可见性：本项目私有，或平台共享且本项目已引用；平台项目本身恒可见。 */
    public static void assertDeviceVisible(ProjectAssetRepository assets, long projectId,
                                           long deviceId, long platformId) {
        ProjectAssetRepository.AssetScope scope = assets.scopeOf("DEVICE", deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "设备不存在：" + deviceId));
        if (!visible(assets, scope, projectId, "DEVICE", deviceId, platformId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "设备不存在或不在本项目可见范围：" + deviceId);
        }
    }

    /** 凭证可见性：跨项目私有凭证一律 400 点名，不静默成功（NFR-v5.13-06）。 */
    public static void assertCredentialVisible(ProjectAssetRepository assets, long projectId,
                                               long credentialId, long platformId) {
        ProjectAssetRepository.AssetScope scope = assets.scopeOf("CREDENTIAL", credentialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "凭证不存在：" + credentialId));
        if (!visible(assets, scope, projectId, "CREDENTIAL", credentialId, platformId)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "凭证不属于本项目，不能用于采集或关联：" + credentialId);
        }
    }

    private static boolean visible(ProjectAssetRepository assets,
                                   ProjectAssetRepository.AssetScope scope,
                                   long projectId, String type, long assetId, long platformId) {
        if (scope.platform()) {
            return projectId == platformId || assets.referencedBy(projectId, type, assetId);
        }
        return scope.projectId() != null && scope.projectId() == projectId;
    }

    private static Long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
