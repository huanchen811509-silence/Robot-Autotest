package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.iam.AuditService;
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
 * v5.9.0 / W59-20：公共资产的**改名**（台账里 {@code /g/assets} 缺的那个 U）。
 *
 * <p>只改显示名是刻意的：型号/序列号/管理 IP 由采集与导入回写，手工改会在下次采集被覆盖，
 * 变成「界面一套、设备一套」。这里同时锁住「不改成事实列」的边界。
 */
class GlobalAssetRenameTest {

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
                new ObjectMapper(), mock(CredentialVerificationService.class));
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("改名只写 name 列，不碰型号/序列号等采集回写列")
    void renamesOnlyDisplayName() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String saved = service.rename("device", 12L, "  机房 A 的 GPU-01  ");

        assertThat(saved).isEqualTo("机房 A 的 GPU-01");
        verify(jdbc).update(contains("UPDATE device SET name = ?"), any(Object[].class));
        verify(jdbc, never()).update(contains("device_type"), any(Object[].class));
    }

    @Test
    @DisplayName("空名 / 超长名 400，非管理员 403")
    void rejectsBadInput() {
        assertThatThrownBy(() -> service.rename("device", 12L, "   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.rename("device", 12L, "x".repeat(129)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);

        when(access.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.rename("device", 12L, "名字"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("未知资产类型 400，行不存在 404")
    void validatesTypeAndExistence() {
        assertThatThrownBy(() -> service.rename("router", 12L, "名字"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> service.rename("device", 12L, "名字"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
