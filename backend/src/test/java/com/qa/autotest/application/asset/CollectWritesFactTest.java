package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-02：collect() 主路径真的调用了写事实（不是只有 service 里有方法）。 */
class CollectWritesFactTest {

    private JdbcTemplate jdbc;
    private HostInventoryService hostInventory;
    private CredentialVerificationService verifications;
    private GlobalAssetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        hostInventory = mock(HostInventoryService.class);
        verifications = mock(CredentialVerificationService.class);
        service = new GlobalAssetService(jdbc, mock(AccessControlService.class),
                mock(AuditService.class), mock(ProjectAssetRepository.class),
                mock(PlatformProjectService.class), hostInventory,
                mock(SecretCipher.class), new ObjectMapper(), verifications);
        doReturn(List.of(Map.of("id", 9L, "name", "gpu-01",
                "management_ip", "10.20.30.40")))
                .when(jdbc).queryForList(anyString(), any(Object[].class));
    }

    private static HostInventoryRecord okRecord() {
        return new HostInventoryRecord(1L, 0L, 9L, "10.20.30.40", Instant.now(),
                "OK", "{}", "hash", 2, 1, 100);
    }

    @Test
    @DisplayName("采集成功 → 写 SUCCESS 事实，credential_id 为本次实际所用")
    void successWritesFact() {
        doReturn(okRecord()).when(hostInventory).collect(anyLong(), anyString(), any(),
                any(), any(), any(), anyBoolean());

        service.collect(9L, 3L, null, true);

        verify(verifications).recordCollectResult(eq(9L), eq(3L), eq("MGMT"),
                eq(true), isNull());
    }

    @Test
    @DisplayName("采集失败 → 写 FAILED 事实 + 分类原因，原始异常照常外抛")
    void failureWritesFactAndRethrows() {
        doThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "connect timeout"))
                .when(hostInventory).collect(anyLong(), anyString(), any(),
                        any(), any(), any(), anyBoolean());

        assertThrows(BusinessException.class, () -> service.collect(9L, 3L, null, true));

        verify(verifications).recordCollectResult(eq(9L), eq(3L), eq("MGMT"),
                eq(false), eq("TIMEOUT"));
    }

    @Test
    @DisplayName("采集器落 FAILED 状态但没有抛异常时，也不算成功事实")
    void failedStatusIsNotSuccess() {
        HostInventoryRecord failed = new HostInventoryRecord(1L, 0L, 9L, "10.20.30.40",
                Instant.now(), "FAILED", "{}", "hash", 0, 0, null);
        doReturn(failed).when(hostInventory).collect(anyLong(), anyString(), any(),
                any(), any(), any(), anyBoolean());

        service.collect(9L, 3L, null, true);

        verify(verifications).recordCollectResult(eq(9L), eq(3L), eq("MGMT"),
                eq(false), isNull());
    }

    @Test
    @DisplayName("没有 credentialId 时不写事实（W63-05 回退链接入前的安全行为）")
    void noCredentialWritesNoFact() {
        doReturn(okRecord()).when(hostInventory).collect(anyLong(), anyString(), any(),
                any(), any(), any(), anyBoolean());

        service.collect(9L, null, null, true);

        verify(verifications, org.mockito.Mockito.never())
                .recordCollectResult(anyLong(), anyLong(), anyString(), anyBoolean(), any());
    }
}
