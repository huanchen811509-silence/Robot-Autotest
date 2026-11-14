package com.qa.autotest.application.iam;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * W57-14：向导内容应用扩 caseSetIds 的纯 Mockito 单测（不依赖 Spring 上下文 / 真实 DB）。
 *
 * <p>用 spy 覆盖 protected 的集合可用性与成员查询，聚焦「展开 / 去重 / 保序 / 指名报错」。
 */
class ProjectContentSetIdsTest {

    private JdbcTemplate jdbc;
    private ProjectTemplateService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ProjectTemplateService(jdbc, mock(AccessControlService.class),
                mock(AuditService.class), mock(ProjectAssetService.class),
                mock(ProjectAssetRepository.class), mock(ProjectService.class));
    }

    @Test
    @DisplayName("只传 caseIds：语义零改动，保持原顺序")
    void 只传caseIds() {
        assertEquals(List.of(3L, 1L, 2L),
                spy(service).resolveCaseIds(List.of(3L, 1L, 2L), null));
    }

    @Test
    @DisplayName("只传 setIds：展开集合成员")
    void 只传setIds() {
        ProjectTemplateService s = spy(service);
        doNothing().when(s).requireUsableSet(99L);
        doReturn(List.of(7L, 8L)).when(s).loadSetMembers(99L);
        assertEquals(List.of(7L, 8L), s.resolveCaseIds(null, List.of(99L)));
    }

    @Test
    @DisplayName("混合去重：caseIds 与集合成员重复 id 只保留首次")
    void 混合去重() {
        ProjectTemplateService s = spy(service);
        doNothing().when(s).requireUsableSet(99L);
        doReturn(List.of(2L, 3L)).when(s).loadSetMembers(99L);
        assertEquals(List.of(1L, 2L, 3L), s.resolveCaseIds(List.of(1L, 2L), List.of(99L)));
    }

    @Test
    @DisplayName("顺序保持：caseIds 在前，再按 setIds 顺序逐个追加成员")
    void 顺序保持() {
        ProjectTemplateService s = spy(service);
        doNothing().when(s).requireUsableSet(anyLong());
        doReturn(List.of(30L)).when(s).loadSetMembers(10L);
        doReturn(List.of(40L)).when(s).loadSetMembers(20L);
        assertEquals(List.of(1L, 2L, 30L, 40L),
                s.resolveCaseIds(List.of(1L, 2L), List.of(10L, 20L)));
    }

    @Test
    @DisplayName("集合不存在：指名报错（消息带 setId），错误码 NOT_FOUND")
    void 不存在报错() {
        when(jdbc.queryForList("SELECT set_status FROM test_set WHERE id = ?",
                String.class, 42L)).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireUsableSet(42L));
        assertEquals(ErrorCode.NOT_FOUND, e.errorCode());
        assertTrue(e.getMessage().contains("42"));
    }

    @Test
    @DisplayName("ContentRequest：3 参旧构造与 4 参新构造都可用")
    void 请求体兼容() {
        ProjectTemplateService.ContentRequest old =
                new ProjectTemplateService.ContentRequest(List.of(1L), List.of(), "名称");
        assertNull(old.caseSetIds());
        ProjectTemplateService.ContentRequest now =
                new ProjectTemplateService.ContentRequest(List.of(1L), List.of(), "名称", List.of(9L));
        assertEquals(List.of(9L), now.caseSetIds());
    }
}
