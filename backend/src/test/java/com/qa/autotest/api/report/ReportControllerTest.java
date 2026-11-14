package com.qa.autotest.api.report;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.CompareService;
import com.qa.autotest.application.report.ReportIngestService;
import com.qa.autotest.application.report.ReportLogService;
import com.qa.autotest.application.report.ReportQueryService;
import com.qa.autotest.application.report.ReportTreeService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RPT-01 接口层：报告读路径的项目绑定、分页上限、视图字段与错误码映射。
 *
 * <p>此前 RPT-01 只有服务层单测，控制器是薄壳但有三处约定值得钉死：
 * 每个端点先 {@code access.bindProject(projectId)}、列表 size 上限 100、
 * 详情视图字段名与前端一致。
 */
class ReportControllerTest {

    private ReportIngestService ingest;
    private ReportQueryService query;
    private ReportTreeService tree;
    private ReportLogService logs;
    private AccessControlService access;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingest = mock(ReportIngestService.class);
        query = mock(ReportQueryService.class);
        tree = mock(ReportTreeService.class);
        logs = mock(ReportLogService.class);
        access = mock(AccessControlService.class);
        ReportController controller = new ReportController(ingest, query, tree, logs,
                mock(CompareService.class), access);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("列表：size 超过 100 被夹到 100，且先绑定项目")
    void page_capsSizeAndBindsProject() throws Exception {
        when(query.page(7L, 2, 100)).thenReturn(new PageResult<>(List.of(record()), 1, 2, 100));

        mvc.perform(get("/api/reports").param("projectId", "7")
                        .param("page", "2").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.items[0].counts.pass").value(2))
                .andExpect(jsonPath("$.data.items[0].counts.fail").value(1));

        verify(access).bindProject(7L);
        verify(query).page(7L, 2, 100);
    }

    @Test
    @DisplayName("详情：metadata / metadataMissing / exportable 原样出现在 data 里")
    void detail_viewFields() throws Exception {
        when(query.detail(7L, "RUN-1")).thenReturn(record());

        mvc.perform(get("/api/reports/RUN-1").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.projectId").value(7))
                .andExpect(jsonPath("$.data.state").value("FINISHED"))
                .andExpect(jsonPath("$.data.exportable").value(true))
                .andExpect(jsonPath("$.data.metadata.DUT").value("GPU-01"))
                .andExpect(jsonPath("$.data.metadataMissing[0]").value("LINK"))
                .andExpect(jsonPath("$.data.counts.skip").value(0));

        verify(access).bindProject(7L);
    }

    @Test
    @DisplayName("详情：不存在 → 404，错误码名原样返回")
    void detail_notFound() throws Exception {
        when(query.detail(7L, "RUN-404"))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "报告不存在: RUN-404"));

        mvc.perform(get("/api/reports/RUN-404").param("projectId", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codeName").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("越权：bindProject 抛 FORBIDDEN → 403，且不进入查询服务")
    void forbiddenBeforeQuery() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权访问该项目"))
                .when(access).bindProject(7L);

        mvc.perform(get("/api/reports/RUN-1").param("projectId", "7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codeName").value("FORBIDDEN"));

        verify(query, never()).detail(anyLong(), anyString());
    }

    @Test
    @DisplayName("缺 projectId：400 + INVALID_ARGUMENT（不是 500），不会走到服务层")
    void missingProjectIdRejected() throws Exception {
        mvc.perform(get("/api/reports/RUN-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("缺少参数：projectId"));

        verify(access, never()).bindProject(anyLong());
        verify(query, never()).detail(anyLong(), anyString());
    }

    @Test
    @DisplayName("参数类型不对（page=abc）：400 + INVALID_ARGUMENT（不是 500）")
    void badParamTypeRejected() throws Exception {
        mvc.perform(get("/api/reports").param("projectId", "7").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("参数类型不正确：page"));

        verify(query, never()).page(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("解析入库：outputXmlPath 原样透传")
    void parse_passesOutputPath() throws Exception {
        when(ingest.parseAndIngest(7L, "RUN-1", "/tmp/output.xml")).thenReturn(record());

        mvc.perform(post("/api/reports/RUN-1/parse").param("projectId", "7")
                        .param("outputXmlPath", "/tmp/output.xml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-1"));

        verify(ingest).parseAndIngest(7L, "RUN-1", "/tmp/output.xml");
    }

    @Test
    @DisplayName("套件树与日志：默认分页参数是 offset=0 / limit=200")
    void treeAndLogs_defaultPaging() throws Exception {
        when(tree.tree(7L, "RUN-1"))
                .thenReturn(new ReportTreeService.TreeSuite("Suite", "PASS", List.of(), List.of()));
        when(logs.logs(7L, "RUN-1", "case.1", 0, 200))
                .thenReturn(new ReportLogService.LogPage(List.of(), 0, null));

        mvc.perform(get("/api/reports/RUN-1/tree").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Suite"))
                .andExpect(jsonPath("$.data.status").value("PASS"));

        mvc.perform(get("/api/reports/RUN-1/logs").param("projectId", "7")
                        .param("testKey", "case.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.nextOffset").doesNotExist());

        verify(logs).logs(7L, "RUN-1", "case.1", 0, 200);
    }

    @Test
    @DisplayName("详情：判据结论原样透出（PASS），带评估数与缺失数")
    void detail_criteriaConclusion_pass() throws Exception {
        when(query.detail(7L, "RUN-1")).thenReturn(withCriteria("PASS", 5, 0));

        mvc.perform(get("/api/reports/RUN-1").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteriaConclusion").value("PASS"))
                .andExpect(jsonPath("$.data.criteriaEvaluated").value(5))
                .andExpect(jsonPath("$.data.criteriaMissingCount").value(0));
    }

    @Test
    @DisplayName("详情：未声明判据 → UNDETERMINED，失败数为 0 也不判 PASS")
    void detail_criteriaConclusion_undetermined() throws Exception {
        // 11 参兼容构造：没有判据结论；passCount=9 / failCount=0 是最容易被误判成 PASS 的形状
        when(query.detail(7L, "RUN-1")).thenReturn(new ReportRecord(7L, "RUN-1", "FINISHED", true,
                List.of(), Map.of(), 9, 0, 0,
                Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-06T00:01:00Z")));

        mvc.perform(get("/api/reports/RUN-1").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteriaConclusion").value("UNDETERMINED"))
                .andExpect(jsonPath("$.data.criteriaEvaluated").value(0));
    }

    @Test
    @DisplayName("详情：判据判 FAIL 时，即使失败计数为 0 结论也是 FAIL")
    void detail_criteriaConclusion_failWins() throws Exception {
        when(query.detail(7L, "RUN-1")).thenReturn(withCriteria("FAIL", 3, 1));

        mvc.perform(get("/api/reports/RUN-1").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteriaConclusion").value("FAIL"))
                .andExpect(jsonPath("$.data.criteriaMissingCount").value(1));
    }

    /** 带判据结论的记录：结论由 test_result.criteria_verdict 聚合而来，与计数解耦。 */
    private static ReportRecord withCriteria(String conclusion, int evaluated, int missing) {
        return new ReportRecord(7L, "RUN-1", "FINISHED", true, List.of("LINK"),
                Map.of("DUT", "GPU-01"), 2, 1, 0,
                Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-06T00:01:00Z"),
                conclusion, evaluated, missing);
    }

    private static ReportRecord record() {
        return new ReportRecord(7L, "RUN-1", "FINISHED", true, List.of("LINK"),
                Map.of("DUT", "GPU-01"), 2, 1, 0,
                Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-06T00:01:00Z"));
    }
}
