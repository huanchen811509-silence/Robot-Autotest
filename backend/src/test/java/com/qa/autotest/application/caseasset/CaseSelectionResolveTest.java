package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.common.PageResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 条件选择解析（v5.8.0 / FR-v5.8-02）。
 *
 * <p>要钉死的是「界面上的全选所有命中」能不能真的落到一批 id 上，以及**不该猜的地方绝不猜**：
 * ids 与 selection 同时给 → 400（猜错就是删错数据）；条件命中超上限 → 400 并报出真实命中数
 * （不许静默截断成 200 条）；解析为空 → 400（不许让用户点了没反应）。
 *
 * <p>这里只验解析层，所以让每条动作都走 NOT_FOUND 失败路径 —— 计数与解析数照常可断言，
 * 不必造出可成功的用例数据。
 */
class CaseSelectionResolveTest {

    private final TestCaseRepository cases = mock(TestCaseRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AuditService audit = mock(AuditService.class);
    private final TestCaseService service = new TestCaseService(cases, access, audit);

    private static TestCase tc(long id) {
        return new TestCase(id, 7L, "C-" + id, "用例" + id, "cat", "P0", null, null,
                "net", "APPROVED", "MANUAL", null, null, "L0");
    }

    private void stubPage(List<TestCase> items, long total) {
        when(cases.page(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(items, total, 1, TestCaseService.BATCH_MAX + 1));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("ids 与 selection 同时给 → 400，并点名两个字段")
    void bothIdsAndSelectionRejected() {
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", List.of(1L), null, null, "net", null,
                new TestCaseService.CaseSelectionSpec("filter", null, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ids")
                .hasMessageContaining("selection")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("selection.mode 只支持 filter：给 ids 模式直接 400")
    void unsupportedModeRejected() {
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", null, null, null, "net", null,
                new TestCaseService.CaseSelectionSpec("ids", null, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("filter");
    }

    @Test
    @DisplayName("条件命中超过 200 条 → 400，报出真实命中数与上限（不静默截断）")
    void overLimitRejected() {
        stubPage(List.of(tc(1L)), 201);
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", null, null, null, "net", null,
                new TestCaseService.CaseSelectionSpec("filter", "关键字", null, null, null, null, null, null));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("201")
                .hasMessageContaining("200");
    }

    @Test
    @DisplayName("excludeIds 生效：命中 3 条排除 1 条 → 只处理 2 条，resolvedCount=2")
    void excludeIdsApplied() {
        stubPage(List.of(tc(1L), tc(2L), tc(3L)), 3);
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", null, null, null, "net", null,
                new TestCaseService.CaseSelectionSpec("filter", "x", null, null, null, null, null, List.of(2L)));

        TestCaseService.BatchResult result = service.batch(7L, req);

        assertThat(result.resolvedCount()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(TestCaseService.BatchItemResult::id)
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("只有 ids 的老请求行为不变：resolvedCount = 条目数")
    void legacyIdsPathUnchanged() {
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.empty());
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", List.of(11L, 12L), null, null, "net", null);

        TestCaseService.BatchResult result = service.batch(7L, req);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.resolvedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("解析结果为空 → 400（命中 0 条或全被排除），不留「点了没反应」")
    void emptyResolutionRejected() {
        stubPage(List.of(tc(1L)), 1);
        TestCaseService.BatchRequest req = new TestCaseService.BatchRequest(
                "setDomainTag", null, null, null, "net", null,
                new TestCaseService.CaseSelectionSpec("filter", "x", null, null, null, null, null, List.of(1L)));

        assertThatThrownBy(() -> service.batch(7L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0 条");
    }
}
