package com.qa.autotest.api.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.AssetInfoResponse;
import com.qa.autotest.application.asset.AssetInfoService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连接/拓扑接口单测（v4.3.0 / PRD §6）。
 *
 * <p>覆盖：scope 校验、refresh 默认 force=true、刷新写审计、GET 只读不写审计。
 */
class AssetInfoControllerTest {

    private static final long PROJECT = 31L;

    private AssetInfoService assets;
    private AccessControlService access;
    private AuditService audit;
    private AssetInfoController controller;

    @BeforeEach
    void setUp() {
        assets = mock(AssetInfoService.class);
        access = mock(AccessControlService.class);
        audit = mock(AuditService.class);
        controller = new AssetInfoController(assets, access, audit);
        AssetInfoResponse response = new AssetInfoResponse(Instant.now(),
                new AssetInfoResponse.Meta(false, "internal", false, 43200),
                new AssetInfoResponse.Connections(List.of(), 0),
                new AssetInfoResponse.Topology(List.of(), List.of(),
                        new AssetInfoResponse.TopologyStats(0, 0, 0, 0)), true);
        when(assets.get(anyLong(), anyBoolean())).thenReturn(response);
    }

    @Test
    @DisplayName("GET 合法 scope 返回 200 结构，不写审计")
    void 读取() {
        assertThat(controller.get(PROJECT, "connections,topology", true).data().empty()).isTrue();

        verify(assets).get(PROJECT, false);
        verify(audit, org.mockito.Mockito.never())
                .record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("GET 未知 scope → 400 INVALID_ARGUMENT")
    void 读取未知scope() {
        assertThatThrownBy(() -> controller.get(PROJECT, "links", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("refresh 缺省 force=true 并写审计 asset-info.refresh")
    void 刷新默认强制() {
        controller.refresh(PROJECT, null);

        verify(assets).get(PROJECT, true);
        verify(audit).record(eq("asset-info.refresh"), eq("project"), eq(String.valueOf(PROJECT)),
                eq("scope=connections,topology,force=true"));
    }

    @Test
    @DisplayName("refresh force=false 遵循窗口，未知 scope 400")
    void 刷新参数() {
        controller.refresh(PROJECT, new AssetInfoController.RefreshRequest(
                List.of("connections"), false));

        verify(assets).get(PROJECT, false);
        assertThatThrownBy(() -> controller.refresh(PROJECT,
                new AssetInfoController.RefreshRequest(List.of("devices"), false)))
                .isInstanceOf(BusinessException.class);
    }
}
