package com.qa.autotest.api.contract;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.api.caseasset.CaseController;
import com.qa.autotest.api.execution.RunController;
import com.qa.autotest.api.iam.AuditController;
import com.qa.autotest.api.report.TestReportController;
import com.qa.autotest.application.caseasset.CaseImportService;
import com.qa.autotest.application.caseasset.CapabilityService;
import com.qa.autotest.application.caseasset.TestCaseService;
import com.qa.autotest.application.execution.CaseRunService;
import com.qa.autotest.application.execution.CancelService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.LeaseWaitRegistry;
import com.qa.autotest.application.execution.QuickRunService;
import com.qa.autotest.application.execution.RunDispatcher;
import com.qa.autotest.application.execution.RunLogFileService;
import com.qa.autotest.application.execution.RunLogService;
import com.qa.autotest.application.execution.RunProgressRegistry;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.execution.TestSetRunService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditQuery;
import com.qa.autotest.application.iam.AuditQueryService;
import com.qa.autotest.application.report.TestReportService;
import com.qa.autotest.common.query.PageQuery;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.report.TestReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EW32-08 · 8 个核心端点的统一契约测试（TC-3.2-11）。
 *
 * <p>只验证「信封 / 分页字段 / 错误码 / 参数白名单」这些跨端点一致的部分；
 * 业务正确性由各自的 Controller/Service 测试负责。快照 diff 由
 * {@code tools/gen_api_contract.py --check} 在门禁里执行。
 */
class CoreApiContractTest {

    private MockMvc caseMvc(TestCaseService cases) {
        CapabilityService capabilities = mock(CapabilityService.class);
        CaseImportService importer = mock(CaseImportService.class);
        return MockMvcBuilders.standaloneSetup(new CaseController(cases, capabilities, importer))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @SuppressWarnings("unchecked")
    private RunController runController(RunService runs) {
        return new RunController(runs,
                mock(CancelService.class),
                mock(RunLogService.class),
                new ExecutionProperties(),
                mock(QuickRunService.class),
                mock(TestSetRunService.class),
                mock(CaseRunService.class),
                mock(RunLogFileService.class),
                mock(RunDispatcher.class),
                mock(LeaseWaitRegistry.class),
                mock(RunProgressRegistry.class));
    }

    @Test
    @DisplayName("1. 用例列表：统一信封 + 分页四字段")
    void casesPageEnvelope() throws Exception {
        TestCaseService cases = mock(TestCaseService.class);
        when(cases.page(anyLong(), any(), any(), any(), any(), any(), any(), any(PageQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

        caseMvc(cases).perform(get("/api/projects/29/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.codeName").value("OK"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("2. 用例列表：size 超过 100 归一化为 100（不是在每个仓库各写一遍）")
    void casesSizeClamped() throws Exception {
        TestCaseService cases = mock(TestCaseService.class);
        when(cases.page(anyLong(), any(), any(), any(), any(), any(), any(), any(PageQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 100));

        caseMvc(cases).perform(get("/api/projects/29/cases").param("size", "1000"))
                .andExpect(status().isOk());

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(cases).page(anyLong(), any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("3. 用例列表：未登记排序字段返回 400 INVALID_ARGUMENT，不回退到默认排序")
    void casesUnknownSortRejected() throws Exception {
        caseMvc(mock(TestCaseService.class))
                .perform(get("/api/projects/29/cases").param("sort", "secret_column"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不支持的排序字段")));
    }

    @Test
    @DisplayName("4. 用例列表：beforeId 进入键集模式（page 固定 1、offset 0、id DESC）")
    void casesKeysetMode() throws Exception {
        TestCaseService cases = mock(TestCaseService.class);
        when(cases.page(anyLong(), any(), any(), any(), any(), any(), any(), any(PageQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

        caseMvc(cases).perform(get("/api/projects/29/cases").param("beforeId", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        verify(cases).page(anyLong(), any(), any(), any(), any(), any(), any(), captor.capture());
        PageQuery paging = captor.getValue();
        assertThat(paging.keysetMode()).isTrue();
        assertThat(paging.beforeId()).isEqualTo(5000L);
        assertThat(paging.offset()).isZero();
        assertThat(paging.orderBy()).isEqualTo("id DESC");
    }

    @Test
    @DisplayName("5. 运行列表：统一信封 + 分页四字段")
    void runsPageEnvelope() throws Exception {
        RunService runs = mock(RunService.class);
        when(runs.page(eq(29L), isNull(), any(PageQuery.class)))
                .thenReturn(new PageResult<RunRecord>(List.of(), 0, 1, 20));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(runController(runs))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/execution/runs").param("projectId", "29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("6. 报告列表：统一信封（data 为数组，不是裸列表）")
    void reportsListEnvelope() throws Exception {
        TestReportService reports = mock(TestReportService.class);
        when(reports.list(29L)).thenReturn(List.<TestReport>of());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new TestReportController(reports, mock(AccessControlService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/test-reports").param("projectId", "29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("7. 审计列表：统一信封 + 分页四字段")
    void auditPageEnvelope() throws Exception {
        AuditQueryService audits = mock(AuditQueryService.class);
        when(audits.page(any(AuditQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditController(audits))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/audit").param("projectId", "29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("8. 审计列表：非法 cursor 返回 400 INVALID_ARGUMENT，而不是静默忽略")
    void auditBadCursorRejected() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new AuditController(mock(AuditQueryService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/audit").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cursor 格式非法")));
    }
}
