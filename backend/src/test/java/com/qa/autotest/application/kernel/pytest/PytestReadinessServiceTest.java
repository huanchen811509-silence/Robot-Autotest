package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.HostInventoryRepository;
import com.qa.autotest.domain.asset.Credential;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 台架就绪度（v5.6.0 / FR-v5.6-04 / W56-06）。
 *
 * <p>这条判据存在的理由：pytest 工程「先配台架才能跑」是一句业务事实，但平台从来
 * 没有把它变成一个可判定的问题——v5.5.0 那次真实执行是绕过界面调 API 登记的设备，
 * 于是「缺凭据也能跑」这种事在界面上完全看不出来。四个布尔值就是把它变成可判定。
 *
 * <p>断言集中在三处：<b>零记录即未就绪</b>（不是「有接口就算就绪」）、
 * <b>缺项要显式点名</b>（页面不自己算差集）、<b>只差一项时总判定仍为未就绪</b>
 * （三项齐一项缺，最常见的自我安慰就是「大体上齐了」）。
 */
class PytestReadinessServiceTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final HostInventoryRepository inventories = mock(HostInventoryRepository.class);
    private final CaseScriptBindingRepository bindings = mock(CaseScriptBindingRepository.class);
    private final PytestReadinessService service =
            new PytestReadinessService(devices, credentials, inventories, bindings);

    private static <T> List<T> repeat(int count) {
        List<T> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(null);
        }
        return list;
    }

    private void stub(int deviceCount, int credentialCount, int inventoryCount, long bindingCount) {
        when(devices.listAll(anyLong())).thenReturn(repeat(deviceCount));
        when(credentials.list(anyLong())).thenReturn(repeat(credentialCount));
        when(inventories.list(anyLong(), any(), anyInt())).thenReturn(repeat(inventoryCount));
        when(bindings.countByProject(anyLong(), eq("pytest"))).thenReturn(bindingCount);
    }

    @Test
    void allFourPresentMeansReady() {
        stub(2, 1, 3, 61);

        PytestReadinessService.Readiness readiness = service.readiness(95L);

        assertTrue(readiness.ready(), "四项齐全必须判就绪");
        assertTrue(readiness.missing().isEmpty(), "齐全时不该点名缺项");
        assertEquals(4, readiness.items().size(), "固定四项，多一项少一项都是口径变了");
        for (PytestReadinessService.Item item : readiness.items()) {
            assertTrue(item.ready(), item.key() + " 应为就绪");
        }
    }

    @Test
    void emptyProjectIsNotReadyAndNamesEveryMissingItem() {
        stub(0, 0, 0, 0);

        PytestReadinessService.Readiness readiness = service.readiness(95L);

        assertFalse(readiness.ready(), "四项全空必须判未就绪");
        assertEquals(List.of("devices", "credentials", "environment", "bindings"),
                readiness.missing(), "缺项要按固定顺序点名，页面不自己算差集");
    }

    @Test
    void missingCredentialsBlocksEvenWhenEverythingElseIsReady() {
        // 本轮真实场景：设备登了、环境采了、脚本绑了，但凭据还在进程环境变量里，
        // 平台内一条记录都没有。三项齐一项缺，最容易被人说成「大体上齐了」。
        stub(2, 0, 1, 61);

        PytestReadinessService.Readiness readiness = service.readiness(95L);

        assertFalse(readiness.ready(), "缺凭据即未就绪——环境变量里的凭据在界面上不可见");
        assertEquals(List.of("credentials"), readiness.missing());
    }

    @Test
    void countsArePassedThroughSoThePageCanSayHowMany() {
        stub(2, 3, 1, 61);

        PytestReadinessService.Readiness readiness = service.readiness(95L);

        assertEquals(2, readiness.items().get(0).count());
        assertEquals(3, readiness.items().get(1).count());
        assertEquals(1, readiness.items().get(2).count());
        assertEquals(61, readiness.items().get(3).count());
    }

    @Test
    void bindingCountIsScopedToPytestKernel() {
        stub(1, 1, 1, 0);

        service.readiness(95L);

        // 绑的是 robot 内核的脚本不算数：那是另一个内核的资产。
        verify(bindings).countByProject(95L, "pytest");
    }
}
