package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.api.asset.GlobalAssetController;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v5.12.0 / W62-12：全局资产编辑从「只改显示名」扩到「显示名 + 管理 IP」。
 *
 * <p>规划假设被实测推翻：5.9 已有 {@code PUT /api/global/assets/{type}/{id}} 且
 * {@code requireAdmin()} 已成立，真实缺口是 managementIp 不透传。这里锁住三点：
 * ① 不传 ip 时 SQL 一字不改（旧调用方零回归）；② ip 只对 DEVICE 生效；
 * ③ 非管理员仍然 403。
 */
class GlobalAssetUpdateTest {

    private JdbcTemplate jdbc;
    private AccessControlService access;
    private GlobalAssetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(AccessControlService.class);
        when(access.isAdmin()).thenReturn(true);
        service = new GlobalAssetService(jdbc, access, mock(AuditService.class),
                mock(ProjectAssetRepository.class), mock(PlatformProjectService.class),
                mock(HostInventoryService.class), mock(SecretCipher.class),
                new ObjectMapper());
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("传管理 IP：写 name + management_ip，返回 trim 后的两个值")
    void updatesNameAndManagementIp() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        GlobalAssetService.AssetUpdate saved =
                service.update("device", 12L, "  机房 A 核心交换机  ", " 10.0.0.9 ");

        assertThat(saved.name()).isEqualTo("机房 A 核心交换机");
        assertThat(saved.managementIp()).isEqualTo("10.0.0.9");
        verify(jdbc).update(contains("management_ip = ?"), any(Object[].class));
    }

    @Test
    @DisplayName("不传 IP（含旧 rename 入口）：SQL 不出现 management_ip，行为与 5.9 一致")
    void keepsLegacyBehaviourWhenIpAbsent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service.rename("device", 12L, "  名字  ")).isEqualTo("名字");
        assertThat(service.update("device", 12L, "名字", null).managementIp()).isNull();
        // 空串视作「没传」，不能把事实列清成空串
        assertThat(service.update("device", 12L, "名字", "   ").managementIp()).isNull();

        verify(jdbc, never()).update(contains("management_ip"), any(Object[].class));
    }

    @Test
    @DisplayName("管理 IP 只适用于设备；非设备资产传 IP 直接 400")
    void rejectsIpOnNonDevice() {
        assertThatThrownBy(() -> service.update("credential", 12L, "名字", "10.0.0.9"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.update("credential", 12L, "名字", "10.0.0.9"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只适用于设备");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("名称 / IP 边界：空名、129 字名、65 字 IP 均 400")
    void validatesNameAndIpLength() {
        assertThatThrownBy(() -> service.update("device", 12L, "  ", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.update("device", 12L, "x".repeat(129), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.update("device", 12L, "名字", "x".repeat(65)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("非管理员调全局 PUT → 403；未知类型 400；行不存在 404")
    void guardsAdminTypeAndExistence() {
        when(access.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.update("device", 12L, "名字", "10.0.0.9"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        when(access.isAdmin()).thenReturn(true);
        assertThatThrownBy(() -> service.update("router", 12L, "名字", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> service.update("device", 12L, "名字", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("1 参 RenameRequest 仍可用，2 参形态把 managementIp 透传到 service")
    void controllerRequestIsBackwardCompatible() {
        GlobalAssetController.RenameRequest legacy = new GlobalAssetController.RenameRequest("旧名");
        assertThat(legacy.name()).isEqualTo("旧名");
        assertThat(legacy.managementIp()).isNull();

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        GlobalAssetController controller = new GlobalAssetController(service);
        assertThat(controller.rename("device", 12L, legacy)).isNotNull();
        verify(jdbc, never()).update(contains("management_ip = ?"), any(Object[].class));

        assertThat(controller.rename("device", 12L,
                new GlobalAssetController.RenameRequest("新名", "10.0.0.9"))).isNotNull();
        verify(jdbc).update(contains("management_ip = ?"), any(Object[].class));
    }
}
