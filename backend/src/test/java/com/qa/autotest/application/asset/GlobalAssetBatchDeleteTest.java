package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v5.12.0 / W62-14：全局资产批量删。
 *
 * <p>判据是「选 3 台、1 台被引用 → 2 成功 1 返 409 且点名项目」。这里同时钉住
 * 两个容易退化成 500 的点：① 单条删原来只给引用数量，不给项目名；
 * ② 批量删不能因为一条被引用就整批回滚，也不能把 NOT_FOUND 混进 conflicted。
 */
class GlobalAssetBatchDeleteTest {

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

    private void stubReferences(long id, List<String> names) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("DEVICE"), eq(id)))
                .thenReturn(names);
    }

    @Test
    @DisplayName("3 台中 1 台被引用 → 2 成功、1 CONFLICT 且点名项目名")
    void batchDeleteSkipsReferencedAndNamesProjects() {
        stubReferences(1L, List.of());
        stubReferences(2L, List.of("项目甲", "项目乙"));
        stubReferences(3L, List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        GlobalAssetService.BatchDeleteResult result =
                service.batchDelete("device", List.of(1L, 2L, 3L));

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.conflicted()).isEqualTo(1);
        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).deleted()).isTrue();
        assertThat(result.items().get(1).deleted()).isFalse();
        assertThat(result.items().get(1).reason()).isEqualTo("CONFLICT");
        assertThat(result.items().get(1).projects()).containsExactly("项目甲", "项目乙");
        assertThat(result.items().get(2).deleted()).isTrue();
        verify(jdbc, times(2)).update(contains("DELETE FROM device"), any(Object[].class));
    }

    @Test
    @DisplayName("单条删除被引用资产 → 409 且文案点名项目，不是只给数量、更不是 500")
    void singleDeleteNamesProjects() {
        stubReferences(2L, List.of("项目甲", "项目乙"));

        assertThatThrownBy(() -> service.delete("device", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.delete("device", 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目甲")
                .hasMessageContaining("项目乙")
                .hasMessageContaining("请先解除引用");
        verify(jdbc, never()).update(contains("DELETE FROM device"), any(Object[].class));
    }

    @Test
    @DisplayName("NOT_FOUND 单独登记，不计入 conflicted，也不中断后续条目")
    void notFoundIsReportedSeparately() {
        stubReferences(9L, List.of());
        stubReferences(10L, List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0, 1);

        GlobalAssetService.BatchDeleteResult result =
                service.batchDelete("DEVICE", List.of(9L, 10L));

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.conflicted()).isZero();
        assertThat(result.items().get(0).reason()).isEqualTo("NOT_FOUND");
        assertThat(result.items().get(1).deleted()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("空批次 / 未知类型 / 非管理员一律拒绝；空 id 元素跳过")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> service.batchDelete("device", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.batchDelete("device", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.batchDelete("router", List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);

        when(access.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.batchDelete("device", List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("Controller 接收 type + ids，不夹带其它入参")
    void controllerRequestShape() {
        GlobalAssetController.BatchDeleteRequest body =
                new GlobalAssetController.BatchDeleteRequest("DEVICE", List.of(1L, 2L));
        assertThat(body.type()).isEqualTo("DEVICE");
        assertThat(body.ids()).containsExactly(1L, 2L);

        stubReferences(1L, List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        GlobalAssetController controller = new GlobalAssetController(service);
        assertThat(controller.batchDelete(body)).isNotNull();
        // 空 body 不许悄悄落成「什么都不删」的成功响应
        assertThatThrownBy(() -> controller.batchDelete(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }
}
