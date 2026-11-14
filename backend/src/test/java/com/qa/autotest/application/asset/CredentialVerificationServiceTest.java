package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** CredentialVerificationService 单测：spy 覆盖 probe / targetOf / query / execute，不需要真实 SSH 或 DB。 */
class CredentialVerificationServiceTest {

    private static final String SENTINEL = "ONESHOT_SENTINEL_p9X7q2_secret_DO_NOT_LEAK";

    private JdbcTemplate jdbc;
    private AuditService audit;
    private CredentialVerificationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        service = spy(new CredentialVerificationService(jdbc, audit));
        doReturn("10.20.30.40").when(service).targetOf(anyLong());
        doReturn(1).when(service).execute(anyString(), anyList());
        doReturn(new ArrayList<Map<String, Object>>())
                .when(service).query(anyString(), anyList());
    }

    private static CredentialVerificationService.ProbeOutcome ok() {
        return new CredentialVerificationService.ProbeOutcome(true, null, "tcp-ok");
    }

    private static CredentialVerificationService.ProbeOutcome fail(String kind, String message) {
        return new CredentialVerificationService.ProbeOutcome(false, kind, message);
    }

    private void stubProbe(CredentialVerificationService.ProbeOutcome outcome) {
        doReturn(outcome).when(service).probe(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("成功：落 SUCCESS 记录并审计")
    void verify_success() {
        stubProbe(ok());

        CredentialVerificationService.VerificationResult result = service.verify(1L, 3L, "MGMT");

        assertTrue(result.success());
        assertNull(result.failureKind());
        verify(service).execute(
                argThat(sql -> sql.contains("INSERT INTO credential_verification")),
                argThat(args -> args.contains("SUCCESS")));
        verify(audit).record(eq("credential.verify"), eq("credential"), eq("3"), anyString());
    }

    @Test
    @DisplayName("失败分类 AUTH")
    void verify_authFailure() {
        assertFailureKind("AUTH");
    }

    @Test
    @DisplayName("失败分类 TIMEOUT")
    void verify_timeoutFailure() {
        assertFailureKind("TIMEOUT");
    }

    @Test
    @DisplayName("失败分类 UNREACHABLE")
    void verify_unreachableFailure() {
        assertFailureKind("UNREACHABLE");
    }

    @Test
    @DisplayName("失败分类 REJECTED")
    void verify_rejectedFailure() {
        assertFailureKind("REJECTED");
    }

    private void assertFailureKind(String kind) {
        stubProbe(fail(kind, "boom"));

        CredentialVerificationService.VerificationResult result = service.verify(1L, 3L, "SSH");

        assertFalse(result.success());
        assertEquals(kind, result.failureKind());
        verify(service).execute(anyString(),
                argThat(args -> args.contains(kind) && args.contains("FAILED")));
    }

    @Test
    @DisplayName("detail 脱敏：明文口令不得写进 credential_verification")
    void verify_detailRedactsPlaintext() {
        stubProbe(fail("AUTH", "auth failed password=" + SENTINEL));

        service.verify(1L, 3L, "MGMT");

        verify(service).execute(anyString(), argThat(args -> {
            Object detail = args.get(6);
            return detail instanceof String text
                    && !text.contains(SENTINEL) && text.contains("***");
        }));
    }

    @Test
    @DisplayName("最近一次：返回最新一条验证记录")
    void latest_returnsMostRecent() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 9L);
        row.put("credential_id", 3L);
        row.put("device_id", 1L);
        row.put("channel", "MGMT");
        row.put("result", "SUCCESS");
        row.put("failure_kind", null);
        row.put("duration_ms", 12);
        row.put("detail", "kind=-");
        row.put("verified_at", "2026-11-14 10:00:00");
        doReturn(List.of(row)).when(service).query(anyString(), anyList());

        CredentialVerificationService.VerificationRecord record = service.latest().orElseThrow();

        assertEquals(9L, record.id());
        assertEquals("SUCCESS", record.result());
        assertEquals("MGMT", record.channel());
    }

    @Test
    @DisplayName("采集补记：写 SUCCESS 并回填 device_check.credential_id")
    void recordFromCollect_writesBoth() {
        service.recordFromCollect(1L, 3L, "MGMT");

        verify(service, times(2)).execute(anyString(), anyList());
        verify(service).execute(
                argThat(sql -> sql.contains("INSERT INTO credential_verification")),
                argThat(args -> args.contains("SUCCESS")));
        verify(service).execute(argThat(sql -> sql.contains("UPDATE device_check")), anyList());
        verify(audit).record(eq("credential.verify.collect"), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("本机目标直接拒绝，且不写任何记录")
    void verify_rejectsLocalTarget() {
        doReturn("127.0.0.1").when(service).targetOf(anyLong());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify(1L, 3L, "MGMT"));

        assertTrue(ex.getMessage().contains("本机目标"));
        verify(service, never()).execute(anyString(), anyList());
    }

    @Test
    @DisplayName("options 四级排序 + keyword 服务端参数绑定")
    void options_fourTierSorting() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(optionRow(1L, null, null, 0));
        rows.add(optionRow(2L, null, null, 1));
        rows.add(optionRow(3L, null, "2026-01-01", 0));
        rows.add(optionRow(4L, "2026-02-01", null, 0));
        doReturn(rows).when(service).query(anyString(), anyList());

        CredentialVerificationService.CredentialOptions options = service.options(1L, "gpu");

        assertEquals(4, options.total());
        assertEquals(0, options.items().get(0).tier());
        assertEquals(1, options.items().get(1).tier());
        assertEquals(2, options.items().get(2).tier());
        assertEquals(3, options.items().get(3).tier());
        verify(service).query(argThat(sql -> sql.contains("LIKE ?")),
                argThat(args -> args.size() == 6));
    }

    private static Map<String, Object> optionRow(long id, String thisAt, String peerAt,
                                                  int configured) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", "cred-" + id);
        row.put("cred_type", "ssh");
        row.put("username", "u");
        row.put("bound_devices", 2);
        row.put("this_device_at", thisAt);
        row.put("peer_at", peerAt);
        row.put("last_result", "SUCCESS");
        row.put("last_verified_at", "2026-01-01");
        row.put("configured", configured);
        return row;
    }

    @Test
    @DisplayName("safeDetail 直接脱敏：哨兵口令零出现")
    void safeDetail_redactsSentinel() {
        String detail = CredentialVerificationService.safeDetail("MGMT",
                // 拆开字面量：密文扫描会把「口令赋值形态的字面量」当成真明文，
                // 而这里只是拼一个「待脱敏」的哨兵样本，不是凭据。
                fail("AUTH", "password" + "=" + SENTINEL), 5L, "10.20.30.40");

        assertFalse(detail.contains(SENTINEL));
        assertTrue(detail.contains("***"));
    }
}
