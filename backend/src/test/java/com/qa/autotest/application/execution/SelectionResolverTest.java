package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.execution.SelectionResolver.CaseScopeQuery;
import com.qa.autotest.application.execution.SelectionResolver.Resolution;
import com.qa.autotest.application.execution.SelectionResolver.SelectionMode;
import com.qa.autotest.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W57-27：四模式解析、MIXED 并集去重、>10000 拒绝、两项目隔离。 */
class SelectionResolverTest {

    private static final long PROJECT = 1L;

    private CaseScopeQuery scope;
    private AccessControlService access;
    private SelectionResolver resolver;

    @BeforeEach
    void setUp() {
        scope = mock(CaseScopeQuery.class);
        access = mock(AccessControlService.class);
        resolver = new SelectionResolver(scope, access);
        when(scope.allCaseIds(anyLong(), anyMap())).thenReturn(List.of());
        when(scope.caseIdsOfSets(anyLong(), anyList())).thenReturn(List.of());
        when(scope.retainProjectCaseIds(anyLong(), anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("ALL 模式命中项目全部用例，结果升序去重")
    void all模式() {
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(List.of(3L, 1L, 2L, 2L));
        Resolution r = resolver.resolve(PROJECT, SelectionMode.ALL, null, null, Map.of());
        assertEquals(List.of(1L, 2L, 3L), r.caseIds());
        assertEquals(3, r.count());
        verify(access).bindProject(PROJECT);
    }

    @Test
    @DisplayName("SET 模式返回用例集成员")
    void set模式() {
        when(scope.caseIdsOfSets(eq(PROJECT), eq(List.of(10L))))
                .thenReturn(List.of(5L, 6L));
        Resolution r = resolver.resolve(PROJECT, SelectionMode.SET, List.of(10L), null, null);
        assertEquals(List.of(5L, 6L), r.caseIds());
    }

    @Test
    @DisplayName("CASE 模式只保留项目作用域内的用例 id")
    void case模式() {
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(1L, 2L, 999L))))
                .thenReturn(List.of(1L, 2L));
        Resolution r = resolver.resolve(PROJECT, SelectionMode.CASE, null,
                List.of(1L, 2L, 999L), null);
        assertEquals(List.of(1L, 2L), r.caseIds());
    }

    @Test
    @DisplayName("MIXED = 集合成员 ∪ 单选用例，去重后升序")
    void mixed并集去重() {
        when(scope.caseIdsOfSets(eq(PROJECT), eq(List.of(10L))))
                .thenReturn(List.of(3L, 4L));
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(4L, 5L))))
                .thenReturn(List.of(4L, 5L));
        Resolution r = resolver.resolve(PROJECT, SelectionMode.MIXED,
                List.of(10L), List.of(4L, 5L), null);
        assertEquals(List.of(3L, 4L, 5L), r.caseIds());
    }

    @Test
    @DisplayName("输入顺序不同但集合相同 → 解析结果相同（同输入同摘要的前提）")
    void 顺序无关() {
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(3L, 1L, 2L))))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scope.retainProjectCaseIds(eq(PROJECT), eq(List.of(2L, 3L, 1L))))
                .thenReturn(List.of(2L, 3L, 1L));
        Resolution a = resolver.resolve(PROJECT, SelectionMode.CASE, null, List.of(3L, 1L, 2L), null);
        Resolution b = resolver.resolve(PROJECT, SelectionMode.CASE, null, List.of(2L, 3L, 1L), null);
        assertEquals(a.caseIds(), b.caseIds());
    }

    @Test
    @DisplayName("命中 > 10000 条直接拒绝")
    void 超限拒绝() {
        List<Long> huge = new ArrayList<>();
        for (long i = 1; i <= SelectionResolver.MAX_CASES + 1L; i++) {
            huge.add(i);
        }
        when(scope.allCaseIds(eq(PROJECT), anyMap())).thenReturn(huge);
        assertThrows(BusinessException.class,
                () -> resolver.resolve(PROJECT, SelectionMode.ALL, null, null, null));
    }

    @Test
    @DisplayName("两项目隔离：项目 B 解析项目 A 的用例 id 全部被剔除")
    void 两项目隔离() {
        when(scope.retainProjectCaseIds(eq(2L), anyList())).thenReturn(List.of());
        Resolution r = resolver.resolve(2L, SelectionMode.CASE, null, List.of(1L, 2L, 3L), null);
        assertTrue(r.caseIds().isEmpty());
        assertEquals(0, r.count());
        verify(access).bindProject(2L);
    }

    @Test
    @DisplayName("ALL 模式空输入返回空解析结果（不报错）")
    void 空解析() {
        Resolution r = resolver.resolve(PROJECT, SelectionMode.ALL, null, null, null);
        assertTrue(r.caseIds().isEmpty());
    }

    @Test
    @DisplayName("count 走项目作用域查询")
    void 计数() {
        when(scope.count(eq(PROJECT), anyMap())).thenReturn(42L);
        assertEquals(42L, resolver.count(PROJECT, Map.of("domain", "rdma")));
    }

    @Test
    @DisplayName("parseMode 大小写不敏感，非法值 400")
    void 模式解析() {
        assertEquals(SelectionMode.MIXED, SelectionResolver.parseMode("mixed"));
        assertEquals(SelectionMode.ALL, SelectionResolver.parseMode(" ALL "));
        assertThrows(BusinessException.class, () -> SelectionResolver.parseMode("BOGUS"));
        assertThrows(BusinessException.class, () -> SelectionResolver.parseMode(null));
    }
}
