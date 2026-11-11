package com.qa.autotest.application.caseasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.domain.caseasset.TestSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** OPT-07 / FR-49-28：按分类批量加入的条数相等与 item_source 归属。 */
class CategoryFilterJoinTest {

    @Test
    void 批量加入条数相等且itemSource正确() {
        TestSetRepository sets = mock(TestSetRepository.class);
        TestCaseRepository cases = mock(TestCaseRepository.class);
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        AssetVersionService versions = mock(AssetVersionService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        when(sets.find(7L, 99L)).thenReturn(Optional.of(
                new TestSet(99L, 7L, "S-1", "回归集", "", null, "active", 0)));
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any()))
                .thenReturn(List.of(11L, 22L, 33L));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        TestSetService service = new TestSetService(sets, cases, access, audit, versions);
        service.setJdbc(jdbc);

        TestSetService.CategoryFilterResult result = service.addByCategoryFilter(7L, 99L,
                new GlobalCaseService.CategoryFilter("基础管理测试", null, null));

        assertEquals(3, result.matched(), "筛选结果条数");
        assertEquals(3, result.added(), "新增条数必须等于筛选结果条数");
        assertEquals("category_filter", result.itemSource());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).update(sql.capture(), any(), any(), any(), any(), any());
        for (String statement : sql.getAllValues()) {
            assertTrue(statement.contains("INSERT INTO test_set_item"));
            assertTrue(statement.contains("'category_filter'"),
                    "每条都必须写 item_source='category_filter'");
        }
    }

    @Test
    void 三种筛选口径生成正确条件() {
        List<Object> a1 = new ArrayList<>();
        assertTrue(GlobalCaseService.categoryWhere(
                new GlobalCaseService.CategoryFilter("L1", null, null), a1)
                .contains("category_l1 = ?"), "全选某一级");
        assertEquals(1, a1.size());

        List<Object> a2 = new ArrayList<>();
        assertTrue(GlobalCaseService.categoryWhere(
                new GlobalCaseService.CategoryFilter(null, "L2", null), a2)
                .contains("category_l2 = ?"), "全选某二级");

        List<Object> a3 = new ArrayList<>();
        String w3 = GlobalCaseService.categoryWhere(
                new GlobalCaseService.CategoryFilter("L1", null, 5L), a3);
        assertTrue(w3.contains("test_set_id = ?"), "跨表交集");
        assertTrue(w3.contains("category_l1 = ?"));
        assertEquals(2, a3.size());
    }
}
