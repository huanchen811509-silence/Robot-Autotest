package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.qa.autotest.application.iam.AuditService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-02：成功/失败都写事实、source 可区分、四类失败原因、失败不产生假 READY。 */
class CollectVerificationTest {

    private CredentialVerificationService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = spy(new CredentialVerificationService(jdbc, mock(AuditService.class)));
        org.mockito.Mockito.doReturn(List.of()).when(service).query(anyString(), anyList());
    }

    @Test
    @DisplayName("采集成功：INSERT 一行 SUCCESS + 回填 device_check，共两次写")
    void successWritesFactAndBackfillsDeviceCheck() {
        service.recordCollectResult(9L, 3L, "MGMT", true, null);

        verify(service, times(2)).execute(anyString(), anyList());
        verify(service).execute(
                argThat(sql -> sql.contains("INSERT INTO credential_verification")),
                argThat(args -> args.contains("SUCCESS")
                        && args.stream().anyMatch(v -> v instanceof String text
                                && text.startsWith("source=COLLECT;"))));
        verify(service).execute(argThat(sql -> sql.contains("UPDATE device_check")), anyList());
    }

    @Test
    @DisplayName("采集失败：仍写一行 FAILED + failure_kind，但不回填 device_check")
    void failureWritesFactWithoutBackfill() {
        service.recordCollectResult(9L, 3L, "MGMT", false, "AUTH");

        verify(service, times(1)).execute(anyString(), anyList());
        verify(service).execute(
                argThat(sql -> sql.contains("INSERT INTO credential_verification")),
                argThat(args -> args.contains("FAILED") && args.contains("AUTH")));
    }

    @Test
    @DisplayName("失败事实不得冒充已验证：result 必须是 FAILED")
    void failureNeverLooksReady() {
        service.recordCollectResult(9L, 3L, "MGMT", false, "TIMEOUT");
        verify(service).execute(anyString(), argThat(args -> args.contains("FAILED")
                && !args.contains("SUCCESS")));
    }

    @Test
    @DisplayName("手工 /verify 写 source=VERIFY，与 COLLECT 可区分")
    void verifySourceIsDistinguishable() {
        org.mockito.Mockito.doReturn(List.of(java.util.Map.of(
                "management_ip", "10.20.30.40", "management_ip_alt", "",
                "bmc_address", "", "bmc_address_alt", "")))
                .when(service).query(anyString(), anyList());
        // 不 stub 探测器就会真去连 10.20.30.40:22 等 8 秒超时
        org.mockito.Mockito.doReturn(
                new CredentialVerificationService.ProbeOutcome(true, null, "tcp-ok"))
                .when(service).probe(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), anyString());
        service.verify(9L, 3L, "MGMT");
        verify(service).execute(
                argThat(sql -> sql.contains("INSERT INTO credential_verification")),
                argThat(args -> args.stream().anyMatch(v -> v instanceof String text
                        && text.startsWith("source=VERIFY;"))));
    }

    @Test
    @DisplayName("四类采集失败原因映射：AUTH / TIMEOUT / UNREACHABLE / REJECTED")
    void failureKindsAreFourWay() {
        assertEquals("AUTH", CredentialVerificationService.collectFailureKind(
                new RuntimeException("authentication denied")));
        assertEquals("TIMEOUT", CredentialVerificationService.collectFailureKind(
                new RuntimeException("connect timeout")));
        assertEquals("UNREACHABLE", CredentialVerificationService.collectFailureKind(
                new RuntimeException("host unreachable")));
        assertEquals("REJECTED", CredentialVerificationService.collectFailureKind(
                new RuntimeException("command unsupported")));
        assertEquals("REJECTED", CredentialVerificationService.collectFailureKind(null));
    }

    @Test
    @DisplayName("detail 里不写异常原文，只写分类码")
    void detailNeverContainsRawMessage() {
        service.recordCollectResult(9L, 3L, "MGMT", false, "AUTH");
        verify(service).execute(anyString(), argThat(args -> {
            Object detail = args.get(6);
            return detail instanceof String text
                    && !text.contains("password=hunter2") && text.contains("AUTH");
        }));
    }

    @Test
    @DisplayName("两个来源标记互斥且都是大写，DB 可用 detail 前缀查询")
    void sourceMarkersAreDistinct() {
        assertEquals("COLLECT", CredentialVerificationService.SOURCE_COLLECT);
        assertEquals("VERIFY", CredentialVerificationService.SOURCE_VERIFY);
        assertFalse(CredentialVerificationService.SOURCE_COLLECT.equals(
                CredentialVerificationService.SOURCE_VERIFY));
    }
}
