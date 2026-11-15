package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.AssetVersionService;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** W62-05：批量移出成员 —— 序号连续、只认集合内 id、一次版本与一条审计。 */
class CaseSetBatchTest {

    private static final TestSet SET = new TestSet(3L, 7L, "REG", "回归集", null, 1L, "ACTIVE", 3);
    private static final TestCase C101 = row(101L, "TC-001");
    private static final TestCase C102 = row(102L, "TC-002");
    private static final TestCase C103 = row(103L, "TC-003");

    private TestSetRepository sets;
    private TestSetService service;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetRepository.class);
        service = new TestSetService(sets, mock(TestCaseRepository.class),
                mock(AccessControlService.class), mock(AuditService.class),
                mock(AssetVersionService.class));
        when(sets.find(7L, 3L)).thenReturn(Optional.of(SET));
    }

    private static TestCase row(long id, String code) {
        return new TestCase(id, 7L, code, code, "冒烟", "P0", null, null, null,
                "APPROVED", "manual", null, null, "L0");
    }

    @Test
    @DisplayName("移出 2 条：返回 2，剩余成员整体重排且序号从 1 连续")
    void 批量移出后序号连续() {
        when(sets.items(7L, 3L)).thenReturn(List.of(C101, C102, C103));

        assertEquals(2, service.removeItems(7L, 3L, List.of(101L, 102L)));

        verify(sets).removeItem(7L, 3L, 101L);
        verify(sets).removeItem(7L, 3L, 102L);
        // 关键：剩余只有 103，reorder 收到 [103]，seq_no 才会回到 1
        verify(sets).reorder(eq(7L), eq(3L), eq(List.of(103L)), any());
    }

    @Test
    @DisplayName("空列表 / 全 null 都拒绝，不产生任何写")
    void 空列表判红() {
        assertThrows(BusinessException.class,
                () -> service.removeItems(7L, 3L, List.of()));
        assertThrows(BusinessException.class,
                () -> service.removeItems(7L, 3L, java.util.Arrays.asList(null, null)));
        verify(sets, never()).removeItem(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("重复 id 去重；不在集合里的 id 不算移出、不进重排列表")
    void 去重与只认集合内成员() {
        when(sets.items(7L, 3L)).thenReturn(List.of(C101, C103));

        assertEquals(1, service.removeItems(7L, 3L, List.of(101L, 101L, 999L)));
        verify(sets, times(1)).removeItem(7L, 3L, 101L);
        verify(sets).reorder(eq(7L), eq(3L), eq(List.of(103L)), any());
    }

    @Test
    @DisplayName("全部移出：不调 reorder（空集合没有顺序可排）")
    void 全部移出() {
        when(sets.items(7L, 3L)).thenReturn(List.of(C101, C102));

        assertEquals(2, service.removeItems(7L, 3L, List.of(101L, 102L)));
        verify(sets, never()).reorder(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    @DisplayName("端点：POST /items/batch-remove 原样透传 caseIds 并返回 removed")
    void 端点透传() throws Exception {
        TestSetService mocked = mock(TestSetService.class);
        when(mocked.removeItems(7L, 3L, List.of(101L, 102L))).thenReturn(2);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestSetController(mocked)).build();

        mvc.perform(post("/api/projects/7/case-sets/3/items/batch-remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[101,102]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(2));
        verify(mocked).removeItems(7L, 3L, List.of(101L, 102L));
    }
}
