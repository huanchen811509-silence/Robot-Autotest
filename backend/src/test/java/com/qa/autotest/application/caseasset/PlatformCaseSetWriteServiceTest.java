package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W57-06：平台用例集可写的纯 Mockito 单测（不依赖 Spring 上下文 / 真实 DB）。
 *
 * <p>只 mock 仓储与 AccessControlService / AuditService；鉴权统一落到
 * {@code access.require(CASE, UPDATE)}（case:write 的代理动作）。
 */
class PlatformCaseSetWriteServiceTest {

    private TestSetRepository sets;
    private AccessControlService access;
    private TestSetService service;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetRepository.class);
        access = mock(AccessControlService.class);
        service = new TestSetService(sets, mock(TestCaseRepository.class), access,
                mock(AuditService.class), mock(AssetVersionService.class));
    }

    @Test
    @DisplayName("创建：setCode 不重复时写入并返回新建集")
    void 创建() {
        when(sets.platformCodeExists("PS-1", null)).thenReturn(false);
        when(sets.insertPlatform(eq("PS-1"), eq("平台集"), eq("描述"), isNull(), any()))
                .thenReturn(7L);
        when(sets.findPlatform(7L)).thenReturn(Optional.of(set(7L)));

        TestSet result = service.createPlatform("PS-1", "平台集", "描述");
        assertEquals(7L, result.id());
        verify(sets).insertPlatform(eq("PS-1"), eq("平台集"), eq("描述"), isNull(), any());
    }

    @Test
    @DisplayName("创建重名：服务层自查 set_code 返回 409，且不落库")
    void 重名409() {
        when(sets.platformCodeExists("PS-1", null)).thenReturn(true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createPlatform("PS-1", "平台集", "描述"));
        assertEquals(ErrorCode.CONFLICT, e.errorCode());
        verify(sets, org.mockito.Mockito.never())
                .insertPlatform(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("改名 / 描述：调用仓储更新")
    void 改名() {
        when(sets.findPlatform(3L)).thenReturn(Optional.of(set(3L)));
        service.updatePlatform(3L, "新名", "新描述");
        verify(sets).updatePlatform(eq(3L), eq("新名"), eq("新描述"), any());
    }

    @Test
    @DisplayName("删除：连带删 item（仓储 deletePlatform 内部处理）")
    void 删除() {
        when(sets.findPlatform(3L)).thenReturn(Optional.of(set(3L)));
        service.deletePlatform(3L);
        verify(sets).deletePlatform(3L);
    }

    @Test
    @DisplayName("批量加：已是成员 / 重复 id 计 skipped，新成员计 added")
    void 批量加重复跳过() {
        when(sets.findPlatform(1L)).thenReturn(Optional.of(set(1L)));
        when(sets.platformItemIds(1L)).thenReturn(List.of(10L));
        when(sets.nextPlatformSeqNo(1L)).thenReturn(5);
        when(sets.addPlatformItemIfAbsent(eq(1L), eq(20L), anyInt(), any())).thenReturn(1);

        TestSetService.AddItemsResult result =
                service.addPlatformItems(1L, List.of(10L, 20L, 20L));
        assertEquals(1, result.added());
        assertEquals(2, result.skipped());
        verify(sets).addPlatformItemIfAbsent(eq(1L), eq(20L), anyInt(), any());
    }

    @Test
    @DisplayName("移除成员：调用仓储按 caseId 删除")
    void 移除() {
        when(sets.findPlatform(1L)).thenReturn(Optional.of(set(1L)));
        service.removePlatformItem(1L, 20L);
        verify(sets).removePlatformItem(1L, 20L);
    }

    @Test
    @DisplayName("排序：事务内更新顺序；空列表报 400")
    void 排序() {
        when(sets.findPlatform(1L)).thenReturn(Optional.of(set(1L)));
        service.reorderPlatform(1L, List.of(3L, 1L, 2L));
        verify(sets).reorderPlatform(eq(1L), eq(List.of(3L, 1L, 2L)), any());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.reorderPlatform(1L, List.of()));
        assertEquals(ErrorCode.INVALID_ARGUMENT, e.errorCode());
    }

    @Test
    @DisplayName("超长名称：按 DDL 宽度挡在入口返回 400，不落库（TC-v5.7-22）")
    void 超长名称400() {
        String tooLong = "名".repeat(129);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createPlatform("PS-1", tooLong, "描述"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, e.errorCode());
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("名称") && e.getMessage().contains("128"),
                "报错要点名字段与上限，不能只说服务内部错误：" + e.getMessage());
        verify(sets, org.mockito.Mockito.never())
                .insertPlatform(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("超长描述 / 超长编号：同样 400 而不是 500")
    void 超长描述与编号400() {
        when(sets.findPlatform(3L)).thenReturn(Optional.of(set(3L)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> service.updatePlatform(3L, "正常名", "描".repeat(513))).errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> service.createPlatform("C".repeat(65), "正常名", "描述")).errorCode());
    }

    @Test
    @DisplayName("无 case:write 权限：403，且不触发任何仓储写入")
    void 无权限403() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权限"))
                .when(access).require(PermissionResource.CASE, PermissionAction.UPDATE);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createPlatform("PS-1", "平台集", "描述"));
        assertEquals(ErrorCode.FORBIDDEN, e.errorCode());
        verifyNoInteractions(sets);
    }

    private static TestSet set(long id) {
        return new TestSet(id, 0L, "P-" + id, "平台集", "描述", 1L, "ACTIVE", 0);
    }
}
