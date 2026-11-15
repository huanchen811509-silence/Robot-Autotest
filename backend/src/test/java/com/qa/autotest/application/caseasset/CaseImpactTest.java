package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.TestCase;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.11.0 / W61-15：删除前的影响面预览（FR-v5.11-07 判据 1）。
 *
 * <p>三个数字必须是「真实聚合查询的结果」，不是前端估算：用户据此决定删不删，
 * 数字错了等于把风险藏起来。这里锁住「原样透传」与「用例不存在就 404」。
 */
class CaseImpactTest {

    private TestCaseRepository cases;
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        cases = mock(TestCaseRepository.class);
        service = new TestCaseService(cases, mock(AccessControlService.class),
                mock(AuditService.class));
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("三个数字原样透传（用例集引用 / 脚本绑定 / 执行结果）")
    void passesCountsThrough() {
        when(cases.find(anyLong(), eq(5L))).thenReturn(Optional.of(mock(TestCase.class)));
        when(cases.impactOf(7L, 5L)).thenReturn(new TestCaseRepository.ImpactCounts(3L, 2L, 41L));

        TestCaseService.CaseImpact impact = service.impact(7L, 5L);

        assertThat(impact.setRefs()).isEqualTo(3L);
        assertThat(impact.scriptBindings()).isEqualTo(2L);
        assertThat(impact.runResults()).isEqualTo(41L);
    }

    @Test
    @DisplayName("用例不存在：404，且不去查影响面")
    void notFoundWhenCaseMissing() {
        when(cases.find(anyLong(), eq(5L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.impact(7L, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
