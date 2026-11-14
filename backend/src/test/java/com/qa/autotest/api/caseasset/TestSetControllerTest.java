package com.qa.autotest.api.caseasset;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CAS-02 接口层：用例集 CRUD 的路由、body → 参数透传，以及 403 / 404 / 409 的状态码映射。
 *
 * <p>用 standaloneSetup + 真实 {@link GlobalExceptionHandler}，所以断言的是**线上同一套**
 * 异常映射，而不是另写一份规则。
 */
class TestSetControllerTest {

    private TestSetService sets;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TestSetController(sets))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("列表：默认 size 上限 200，itemCount / setStatus 原样返回")
    void page_默认参数与字段() throws Exception {
        when(sets.page(7L, "reg", null, null, 1, 200)).thenReturn(new PageResult<>(
                List.of(new TestSet(3L, 7L, "REG", "回归集", "每晚跑", 1L, "ACTIVE", 12)), 1L, 1, 200));

        mvc.perform(get("/api/projects/7/case-sets?keyword=reg&size=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].setCode").value("REG"))
                .andExpect(jsonPath("$.data.items[0].itemCount").value(12))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("列级筛选：setCode / name 透传到服务端，不再由前端只筛当前页")
    void page_列级筛选透传() throws Exception {
        when(sets.page(7L, null, "REG", "回归", 2, 50)).thenReturn(new PageResult<>(
                List.of(new TestSet(3L, 7L, "REG-001", "夜间回归", null, null, "ACTIVE", 4)),
                1L, 2, 50));

        mvc.perform(get("/api/projects/7/case-sets?setCode=REG&name=回归&page=2&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(2));

        // 关键断言：两个筛选值是真的进了服务端查询，而不是被 Controller 丢掉
        verify(sets).page(7L, null, "REG", "回归", 2, 50);
    }

    @Test
    @DisplayName("403 / 404 / 409 三个越权与冲突口径")
    void 状态码映射() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权限：需要 CASE:WRITE"))
                .when(sets).create(eq(7L), eq("REG"), anyString(), any());
        mvc.perform(post("/api/projects/7/case-sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setCode\":\"REG\",\"name\":\"回归集\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codeName").value("FORBIDDEN"));

        when(sets.get(7L, 99L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "用例集不存在: 99"));
        mvc.perform(get("/api/projects/7/case-sets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codeName").value("NOT_FOUND"));

        doThrow(new BusinessException(ErrorCode.CONFLICT, "用例集编号已存在: REG"))
                .when(sets).create(eq(7L), eq("REG"), anyString(), any());
        mvc.perform(post("/api/projects/7/case-sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setCode\":\"REG\",\"name\":\"重复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codeName").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("用例集编号已存在: REG"));
    }

    @Test
    @DisplayName("成员与排序：addItem 透传 caseId，reorder 按请求顺序整表提交")
    void 成员与排序透传() throws Exception {
        mvc.perform(post("/api/projects/7/case-sets/3/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":41}"))
                .andExpect(status().isOk());
        verify(sets).addItem(7L, 3L, 41L);

        mvc.perform(delete("/api/projects/7/case-sets/3/items/41"))
                .andExpect(status().isOk());
        verify(sets).removeItem(7L, 3L, 41L);

        mvc.perform(put("/api/projects/7/case-sets/3/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[41,42,43]}"))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> order = ArgumentCaptor.forClass(List.class);
        verify(sets).reorder(eq(7L), eq(3L), order.capture());
        // 顺序是用户拖出来的结果，不能重排也不能丢
        assertEquals(List.of(41L, 42L, 43L), order.getValue());

        mvc.perform(delete("/api/projects/7/case-sets/3")).andExpect(status().isOk());
        verify(sets).delete(7L, 3L);
    }

    @Test
    @DisplayName("成员增删：用例不在本项目时 404 原样返回")
    void 成员404() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "用例不存在: 41"))
                .when(sets).addItem(anyLong(), anyLong(), anyLong());
        mvc.perform(post("/api/projects/7/case-sets/3/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":41}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codeName").value("NOT_FOUND"));
    }
}
