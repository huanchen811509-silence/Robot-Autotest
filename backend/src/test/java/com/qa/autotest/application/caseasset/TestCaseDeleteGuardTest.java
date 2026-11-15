package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.TestCase;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.9.0 / W59-20：用例删除的**引用守卫**。
 *
 * <p>「删不掉」比「删掉一半」好，但只说「删除失败」等于把排查丢给用户。
 * 这里锁两件事：① 有引用就不许删（不能留孤儿绑定）；② 报错必须点名引用去向与条数。
 */
class TestCaseDeleteGuardTest {

    private TestCaseRepository cases;
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        cases = mock(TestCaseRepository.class);
        service = new TestCaseService(cases, mock(AccessControlService.class),
                mock(AuditService.class));
        TenantContext.set(1L, "tester", null);
        when(cases.find(anyLong(), eq(5L))).thenReturn(Optional.of(mock(TestCase.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("被脚本绑定引用：409 且点名「脚本绑定 2 条」，一行都不删")
    void refusesWhenBoundToScript() {
        when(cases.caseReferences(7L, 5L)).thenReturn(Map.of("脚本绑定", 2L));

        assertThatThrownBy(() -> service.delete(7L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("脚本绑定 2 条")
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(cases, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("无引用：正常删除")
    void deletesWhenNoReference() {
        when(cases.caseReferences(7L, 5L)).thenReturn(Map.of());

        service.delete(7L, 5L);

        verify(cases).delete(7L, 5L);
    }

    @Test
    @DisplayName("仓储返回 null（旧假实现）：按无引用处理，不因新增检查误伤")
    void treatsNullAsNoReference() {
        when(cases.caseReferences(7L, 5L)).thenReturn(null);

        service.delete(7L, 5L);

        verify(cases).delete(7L, 5L);
        assertThat(true).isTrue();
    }
}
