package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v5.10.0 / FR-v5.10-08 / W60-03：模板编码前缀唯一。
 *
 * <p>口径落地（与 PRD 判据②「未注册前缀被拒」的差异见 DEV-PLAN 推翻项 A1）：
 * 前缀**首次使用即登记**，**已被别的模板占用才硬拒**。理由写在
 * {@code ProjectTemplateService#requirePrefixFree} 的注释里——「未注册即拒」等于
 * 新业务线要发一版 SQL 才能用新前缀，是产品退化而不是治理。
 */
class ProjectTemplatePrefixTest {

    private JdbcTemplate jdbc;
    private ProjectService projectService;
    private ProjectTemplateService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        projectService = mock(ProjectService.class);
        service = new ProjectTemplateService(jdbc, mock(AccessControlService.class),
                mock(AuditService.class), mock(ProjectAssetService.class),
                mock(ProjectAssetRepository.class), projectService);
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ProjectTemplateService.TemplateRequest req(String name, String code) {
        return new ProjectTemplateService.TemplateRequest(name, code, "robot", "private", "说明");
    }

    @Test
    @DisplayName("前缀已被别的模板占用 → 400，且 message 里点出前缀与占用方")
    void 前缀重复被拒() {
        when(jdbc.queryForList(contains("FROM project_template WHERE code LIKE"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 3L, "name", "接口自动化模板")));

        BusinessException ex = catchThrowableOfType(
                () -> service.create(req("新模板", "API-RF-202611-009")), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).contains("API").contains("接口自动化模板");
    }

    @Test
    @DisplayName("前缀空着（自动编号）时不判唯一：内置 TPL 是兜底桶，不能被自己也占掉")
    void 自动编号不判唯一() {
        // 库里已经有一堆 TPL- 模板
        when(jdbc.queryForList(contains("FROM project_template WHERE code LIKE"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1L, "name", "老模板")));
        when(projectService.nextCode(anyString(), anyString())).thenReturn("TPL-RF-202611-009");

        Throwable ex = catchThrowable(() -> service.create(req("新模板", null)));

        // 后续 insert 在 mock 下会失败，那是测试脚手架的边界；这里只断言
        // **不是因为前缀重复被拒**——否则第二次「不填编号建模板」就必然失败。
        assertThat(ex == null || !String.valueOf(ex.getMessage()).contains("编码前缀已被模板")).isTrue();
    }

    @Test
    @DisplayName("首用前缀自动登记进注册表（INSERT IGNORE），并写审计")
    void 首用前缀自动登记() {
        when(jdbc.queryForList(contains("FROM project_template WHERE code LIKE"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("FROM project_code_prefix"), any(Class.class), anyString()))
                .thenReturn(0);

        catchThrowable(() -> service.create(req("端到端模板", "E2E-RF-202611-001")));

        verify(jdbc).update(contains("INSERT IGNORE INTO project_code_prefix"),
                any(Object[].class));
    }
}
