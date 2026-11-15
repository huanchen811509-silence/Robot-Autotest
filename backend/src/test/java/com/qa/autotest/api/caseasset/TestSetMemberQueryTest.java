package com.qa.autotest.api.caseasset;

import com.qa.autotest.application.caseasset.CapabilityService;
import com.qa.autotest.application.caseasset.CaseImportService;
import com.qa.autotest.application.caseasset.CaseListView;
import com.qa.autotest.application.caseasset.CaseScriptLookup;
import com.qa.autotest.application.caseasset.CaseScriptView;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.application.caseasset.TestSetService;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.common.PageResult;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W62-01：用例集成员查询与「用例清单」用**同一套脚本解析**。
 *
 * <p>DEV-PLAN 原写「在 {@code TestSetRepositoryJdbc} 补一段 LEFT JOIN 并抽公共常量」；
 * 实测 5.11 已把它做成 {@link CaseScriptLookup} 批量服务（一次 {@code IN} 查询，非逐行 join），
 * {@link TestSetController#items} 也已经返回 {@link CaseListView}。因此本版**不写第二份 join**，
 * 只把「成员响应确实带脚本三件套、且与列表端点逐字同源」钉成回归单测。
 *
 * <p>第 5 条把端点路径集合写死：给成员加字段可以，悄悄加端点不行。
 */
class TestSetMemberQueryTest {

    private static final TestCase ROW = new TestCase(101L, 7L, "TC-001", "登录成功",
            "冒烟", "P0", null, "登录成功", null, "APPROVED", "manual", null, null, "L0");
    private static final TestCase ROW2 = new TestCase(102L, 7L, "TC-002", "错误口令",
            "冒烟", "P1", null, "拒绝登录", null, "APPROVED", "manual", null, null, "L0");

    private TestSetService sets;
    private CaseScriptLookup scripts;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetService.class);
        scripts = mock(CaseScriptLookup.class);
    }

    private MockMvc mvc(boolean withLookup) {
        TestSetController controller = new TestSetController(sets);
        if (withLookup) {
            controller.setScriptLookup(scripts);
        }
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("成员响应带脚本三件套；一次 IN 查询覆盖整页，不是逐行 join")
    void items_脚本字段与一次批量查询() throws Exception {
        when(sets.items(7L, 3L)).thenReturn(List.of(ROW, ROW2));
        when(scripts.of(anyLong(), any())).thenReturn(Map.of(
                101L, CaseScriptView.bound("tests/cases/test_login.py", "node-1", true, "pytest", 1),
                102L, CaseScriptView.empty("pytest")));

        mvc(true).perform(get("/api/projects/7/case-sets/3/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].caseCode").value("TC-001"))
                .andExpect(jsonPath("$.data[0].scriptPath").value("tests/cases/test_login.py"))
                .andExpect(jsonPath("$.data[0].scriptName").value("test_login.py"))
                .andExpect(jsonPath("$.data[0].scriptNodeId").value("node-1"))
                .andExpect(jsonPath("$.data[0].bindingState").value("BOUND_VERIFIED"))
                .andExpect(jsonPath("$.data[0].otherScriptCount").value(1))
                // pytest 内核支持绑定但无记录 → UNBOUND；不能把「没绑」写成「不适用」
                .andExpect(jsonPath("$.data[1].bindingState").value("UNBOUND"));

        // 四态口径：支持绑定却没绑 = UNBOUND；内核不支持 = NOT_APPLICABLE（RF 不猜路径）
        assertEquals("UNBOUND", CaseScriptView.empty("pytest").bindingState());
        assertEquals("NOT_APPLICABLE", CaseScriptView.empty("robot").bindingState());

        // 关键是「一次」：逐行查会变成 N 次，200 条成员就是 200 个往返
        verify(scripts, times(1)).of(7L, List.of(101L, 102L));
    }

    @Test
    @DisplayName("三处同源：成员端点与用例清单端点的脚本字符串逐字相同")
    void 成员与用例清单同源() throws Exception {
        CaseScriptView view = CaseScriptView.bound("tests/cases/test_login.py", "node-1",
                true, "pytest", 0);
        when(scripts.of(anyLong(), any())).thenReturn(Map.of(101L, view));
        when(sets.items(7L, 3L)).thenReturn(List.of(ROW));

        TestCaseService cases = mock(TestCaseService.class);
        when(cases.page(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(ROW), 1L, 1, 20));
        CaseController listController = new CaseController(cases,
                mock(CapabilityService.class), mock(CaseImportService.class));
        listController.setScriptLookup(scripts);
        MockMvc listMvc = MockMvcBuilders.standaloneSetup(listController).build();

        String[] keys = {"scriptPath", "scriptName", "scriptNodeId", "bindingState", "scriptKernel"};
        for (String key : keys) {
            String member = mvc(true).perform(get("/api/projects/7/case-sets/3/items"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String list = listMvc.perform(get("/api/projects/7/cases"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            // 两个端点都走 CaseListView.of(row, 同一个 view)，字段必须逐字相同
            assertEquals(extract(member, "data[0]", key), extract(list, "data.items[0]", key),
                    "脚本字段 " + key + " 在成员端点与清单端点不一致");
        }
        assertEquals("test_login.py", CaseListView.of(ROW, view).scriptName(),
                "文件名只能由后端算，前端不得切路径");
    }

    @Test
    @DisplayName("老装配没有脚本服务时降级为 NOT_APPLICABLE，不 500")
    void items_无装配降级() throws Exception {
        when(sets.items(7L, 3L)).thenReturn(List.of(ROW));
        mvc(false).perform(get("/api/projects/7/case-sets/3/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bindingState").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data[0].scriptName").doesNotExist());
        verify(scripts, never()).of(anyLong(), any());
    }

    @Test
    @DisplayName("空成员不触发脚本查询")
    void items_空列表不查脚本() throws Exception {
        when(sets.items(7L, 3L)).thenReturn(List.of());
        mvc(true).perform(get("/api/projects/7/case-sets/3/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(scripts, never()).of(anyLong(), any());
    }

    @Test
    @DisplayName("成员脚本只扩响应体：TestSetController 端点路径集合未新增")
    void 未新增端点() {
        Set<String> paths = new HashSet<>();
        for (Method method : TestSetController.class.getDeclaredMethods()) {
            for (var annotation : Arrays.asList(
                    method.getAnnotation(GetMapping.class),
                    method.getAnnotation(PostMapping.class),
                    method.getAnnotation(PutMapping.class),
                    method.getAnnotation(DeleteMapping.class))) {
                if (annotation == null) continue;
                String[] values;
                if (annotation instanceof GetMapping a) values = a.value();
                else if (annotation instanceof PostMapping a) values = a.value();
                else if (annotation instanceof PutMapping a) values = a.value();
                else values = ((DeleteMapping) annotation).value();
                paths.addAll(Arrays.asList(values.length == 0 ? new String[]{""} : values));
            }
        }
        // W62-05 显式新增批量移出端点：不是「夹带」，是同版有据的授权（DEV-PLAN §8）
        assertEquals(Set.of("", "/{id}", "/{id}/items", "/{id}/items/{caseId}",
                "/{id}/items/batch-remove", "/{id}/order", "/{id}/items/category-filter",
                "/scenario-groups", "/scenario-groups/{scenarioId}/case-sets",
                "/scenario-groups/{scenarioId}"), paths,
                "新增端点必须同版更新本集合并在 DEV-PLAN 说明，不能靠扩字段夹带");
    }

    /** 极简 JSON 字段抽取：只取本测试关心的顶层字符串，避免引入额外依赖。 */
    private static String extract(String json, String prefix, String key) {
        String needle = "\"" + key + "\":";
        int from = json.indexOf(needle);
        if (from < 0) return "<missing>";
        int start = from + needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "<missing>";
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
    }
}
