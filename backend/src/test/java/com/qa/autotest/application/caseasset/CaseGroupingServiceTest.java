package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** W57-08：分类归组聚合的纯 Mockito 单测（spy 覆盖三条聚合查询，不依赖真实 DB）。 */
class CaseGroupingServiceTest {

    private CaseGroupingService service;

    @BeforeEach
    void setUp() {
        service = spy(new CaseGroupingService(mock(JdbcTemplate.class),
                mock(PlatformProjectService.class), mock(AccessControlService.class)));
    }

    @Test
    @DisplayName("空库：返回空列表")
    void 空库() {
        doReturn(List.of()).when(service).loadL1(anyLong());
        doReturn(List.of()).when(service).loadL2(anyLong());
        doReturn(List.of()).when(service).loadAggregation(anyLong());
        assertEquals(List.of(), service.grouped());
    }

    @Test
    @DisplayName("未分类兜底：只有文本没有 id 的脏数据落到「未分类」节点")
    void 未分类兜底() {
        doReturn(List.of()).when(service).loadL1(anyLong());
        doReturn(List.of()).when(service).loadL2(anyLong());
        doReturn(List.of(new CaseGroupingService.AggRow(null, null, 5L)))
                .when(service).loadAggregation(anyLong());

        List<CaseGroupingService.L1Group> result = service.grouped();
        assertEquals(1, result.size());
        assertEquals("未分类", result.get(0).l1Name());
        assertEquals(5L, result.get(0).total());
        assertEquals(5, result.get(0).uncategorized());
        assertTrue(result.get(0).l2s().isEmpty());
    }

    @Test
    @DisplayName("正常分组：二级计数、总数；禁用分类不出现并入 uncategorized")
    void 正常分组() {
        doReturn(List.of(new CaseGroupingService.Category(1L, null, "一级A")))
                .when(service).loadL1(anyLong());
        doReturn(List.of(new CaseGroupingService.Category(2L, 1L, "二级A")))
                .when(service).loadL2(anyLong());
        // 3 条落 (1,2)；2 条只有一级；1 条二级指向禁用分类（id=99 不在启用集合）→ 并入未分类计数
        doReturn(List.of(
                new CaseGroupingService.AggRow(1L, 2L, 3L),
                new CaseGroupingService.AggRow(1L, null, 2L),
                new CaseGroupingService.AggRow(1L, 99L, 1L)))
                .when(service).loadAggregation(anyLong());

        List<CaseGroupingService.L1Group> result = service.grouped();
        assertEquals(1, result.size());
        CaseGroupingService.L1Group group = result.get(0);
        assertEquals(6L, group.total());
        assertEquals(3, group.uncategorized());
        assertEquals(1, group.l2s().size());
        assertEquals(3, group.l2s().get(0).count());
    }
}
