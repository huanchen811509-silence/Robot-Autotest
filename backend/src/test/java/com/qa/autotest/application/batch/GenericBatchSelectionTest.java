package com.qa.autotest.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.CredentialService;
import com.qa.autotest.application.asset.DeviceService;
import com.qa.autotest.application.asset.LinkService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.UserService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通用批量的条件选择（v5.8.0 / FR-v5.8-02）。
 *
 * <p>通用批量管的是设备 / 链路 / 凭证 / 用户四类资源，各自的筛选维度并不一样。
 * 这里钉死的是「<b>按资源校验字段</b>」：LINK 没有 domain 维度、CREDENTIAL 根本不能按关键字筛，
 * 传了就 400 并点名 —— 若默默忽略，用户会以为「按技术域筛出来的 5 条」被删了，
 * 实际删的是全部命中，这类错没有报错也没有日志，只能靠拒跑来防。
 */
class GenericBatchSelectionTest {

    private final DeviceService devices = mock(DeviceService.class);
    private final LinkService links = mock(LinkService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final UserService users = mock(UserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GenericBatchService service = new GenericBatchService(
            devices, links, credentials, users, audit, (TransactionTemplate) null);

    private static Device dev(long id) {
        return new Device(id, 7L, "dev-" + id, "GPU", "M", "V", "SN", "10.0.0.1",
                "A01", "R01", "roce", true, null, null, null, "linux", "22.04", "x86_64", 22);
    }

    @Test
    @DisplayName("ids 与 selection 同时给 → 400，并点名两个字段")
    void bothIdsAndSelectionRejected() {
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "DEVICE", "delete", List.of(1L), null,
                new GenericBatchService.SelectionSpec("filter", null, null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ids")
                .hasMessageContaining("selection");
    }

    @Test
    @DisplayName("mode 只支持 filter：给 ids 模式直接 400")
    void unsupportedModeRejected() {
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "DEVICE", "delete", null, null,
                new GenericBatchService.SelectionSpec("ids", null, null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("filter");
    }

    @Test
    @DisplayName("LINK 传 domain → 400 并点名字段（不许静默忽略）")
    void linkRejectsDomain() {
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "LINK", "delete", null, null,
                new GenericBatchService.SelectionSpec("filter", null, null, "roce", null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LINK")
                .hasMessageContaining("domain");
    }

    @Test
    @DisplayName("CREDENTIAL 传 keyword → 400（该资源只能全量 + 排除）")
    void credentialRejectsKeyword() {
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "CREDENTIAL", "delete", null, null,
                new GenericBatchService.SelectionSpec("filter", "abc", null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CREDENTIAL")
                .hasMessageContaining("keyword");
    }

    @Test
    @DisplayName("ids 空且无 selection → 400，错误信息给出两条出路")
    void emptyIdsWithoutSelectionRejected() {
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "DEVICE", "delete", List.of(), null);

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("selection");
    }

    @Test
    @DisplayName("DEVICE 条件命中 201 条 → 400，报出真实命中数与上限")
    void deviceOverLimitRejected() {
        when(devices.page(anyLong(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(dev(1L)), 201, 1, 201));
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "DEVICE", "delete", null, null,
                new GenericBatchService.SelectionSpec("filter", "gpu", null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("201")
                .hasMessageContaining("200");
    }

    @Test
    @DisplayName("DEVICE 条件 + excludeIds：排除的条目不下发，resolvedCount 如实")
    void deviceExcludeApplied() {
        when(devices.page(anyLong(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(dev(1L), dev(2L), dev(3L)), 3, 1, 201));
        GenericBatchService.BatchRequest req = new GenericBatchService.BatchRequest(
                "DEVICE", "delete", null, null,
                new GenericBatchService.SelectionSpec("filter", "gpu", null, null, List.of(2L)));

        GenericBatchService.BatchResult result = service.batch(7L, req);

        assertThat(result.resolvedCount()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(GenericBatchService.BatchItemResult::id)
                .containsExactly(1L, 3L);
        assertThat(result.succeeded()).isEqualTo(2);
    }
}
