package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.asset.OneShotCredential;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ManualCredentialCollectService 单测：零泄漏 / 老分支回归 / 另存默认关闭。 */
class ManualCredentialCollectServiceTest {

    private static final String SENTINEL = "ONESHOT_SENTINEL_p9X7q2_secret_DO_NOT_LEAK";

    private GlobalAssetService assets;
    private AccessControlService access;
    private ManualCredentialCollectService service;

    @BeforeEach
    void setUp() {
        assets = mock(GlobalAssetService.class);
        access = mock(AccessControlService.class);
        service = new ManualCredentialCollectService(assets, access);
    }

    private static ManualCredentialCollectService.ManualCollectRequest oneShot(String password) {
        return new ManualCredentialCollectService.ManualCollectRequest(
                null, "probe_user", password, null, "ssh", null, false, false, null);
    }

    @Test
    @DisplayName("老分支 credentialId：原样委托既有 collect，行为零改动")
    void credentialIdBranch_delegatesUnchanged() {
        ManualCredentialCollectService.ManualCollectRequest request =
                new ManualCredentialCollectService.ManualCollectRequest(
                        5L, "alice", null, null, null, null, true, false, null);

        ManualCredentialCollectService.ManualCollectOutcome outcome =
                service.collect(42L, request);

        assertFalse(outcome.oneShot());
        verify(assets).collect(42L, 5L, "alice", true);
        verify(assets, never()).createCredential(any());
    }

    @Test
    @DisplayName("一次性分支不落库：assets 零交互，响应不含材料")
    void oneShot_doesNotPersist() {
        ManualCredentialCollectService.ManualCollectOutcome outcome =
                service.collect(42L, oneShot(SENTINEL));

        assertTrue(outcome.oneShot());
        verifyNoInteractions(assets);
        assertFalse(outcome.toString().contains(SENTINEL));
        assertFalse(outcome.toString().contains("password"));
    }

    @Test
    @DisplayName("另存为凭据默认关闭：createCredential 从不调用")
    void saveAsCredential_defaultOff() {
        service.collect(42L, oneShot(SENTINEL));

        verify(assets, never()).createCredential(any());
    }

    @Test
    @DisplayName("显式 saveAsCredential=true 才走加密录入通道")
    void saveAsCredential_explicitOn() {
        ManualCredentialCollectService.ManualCollectRequest request =
                new ManualCredentialCollectService.ManualCollectRequest(
                        null, "alice", SENTINEL, null, "ssh", null, false, true, "saved-cred");

        service.collect(42L, request);

        verify(assets).createCredential(argThat(
                body -> body != null && "saved-cred".equals(body.name())));
    }

    @Test
    @DisplayName("失败不回显：异常消息与堆栈都不含哨兵口令")
    void failure_doesNotLeakSecret() {
        ManualCredentialCollectService.ManualCollectRequest request =
                new ManualCredentialCollectService.ManualCollectRequest(
                        null, SENTINEL, null, null, "ssh", SENTINEL, false, false, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.collect(42L, request));

        assertFalse(ex.getMessage().contains(SENTINEL));
        assertFalse(Arrays.toString(ex.getStackTrace()).contains(SENTINEL));
    }

    @Test
    @DisplayName("OneShotCredential.toString 对口令/私钥/连接参数打码")
    void oneShotCredential_toStringMasks() {
        OneShotCredential credential = new OneShotCredential("alice", SENTINEL,
                "PRIVATE-" + SENTINEL, "ssh", "--password=" + SENTINEL);

        String text = credential.toString();

        assertFalse(text.contains(SENTINEL));
        assertFalse(text.contains("PRIVATE-"));
        assertTrue(text.contains("secret=***"));
        assertTrue(text.contains("privateKey=***"));
    }
}
