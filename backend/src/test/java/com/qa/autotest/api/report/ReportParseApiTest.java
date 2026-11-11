package com.qa.autotest.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.application.report.OutputXmlParser;
import com.qa.autotest.application.report.ReportIngestService;
import com.qa.autotest.application.report.ReportQueryService;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportRecord;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RPT-01 写路径接口层：真实 {@code output.xml} 经 HTTP 触发解析 → 门禁 → 入库。
 *
 * <p>此前 RPT-01 只有读路径走 MockMvc，写路径（{@code POST /api/reports/{runId}/parse}）
 * 只被服务层单测覆盖：控制器是否真的把显式路径透传、错误码是否按 400/404 返回、
 * 入库后的视图字段（exportable / metadataMissing / counts）是否与前端约定一致，都没钉住。
 * 这里保留真实解析器与真实编排，只把仓储换成 Mockito，入库参数用 Captor 反查。
 */
class ReportParseApiTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "PERF-IPERF-200G-001_20261031_141207";

    private ReportRepository reports;
    private final AtomicReference<ReportRecord> stored = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", PROJECT);
        RunRepository runs = mock(RunRepository.class);
        when(runs.findByRunId(PROJECT, RUN_ID)).thenReturn(Optional.of(finishedRun()));
        // 计划来源：手工运行没有 plan_id，不注入 PLAN_ID / TRIGGER_TYPE（PLN-01）
        when(runs.findOrigin(PROJECT, RUN_ID)).thenReturn(Optional.empty());
        RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
        when(artifacts.list(anyLong(), anyLong())).thenReturn(List.of());

        reports = mock(ReportRepository.class);
        // 用真实的 ParsedReport / GateResult 组装入库结果，等价于 JDBC 实现的落库后回读
        doAnswer(inv -> {
            ParsedReport parsed = inv.getArgument(2);
            GateResult gate = inv.getArgument(3);
            stored.set(new ReportRecord(PROJECT, RUN_ID, RunState.FINISHED.dbValue(),
                    gate.exportable(), gate.missing(), parsed.metadata(),
                    parsed.passCount(), parsed.failCount(), parsed.skipCount(),
                    inv.getArgument(4), null));
            return null;
        }).when(reports).replace(eq(PROJECT), eq(1L), any(ParsedReport.class),
                any(GateResult.class), any());
        when(reports.find(PROJECT, RUN_ID)).thenAnswer(inv -> Optional.ofNullable(stored.get()));

        ReportIngestService ingest = new ReportIngestService(runs, artifacts, reports,
                new AccessControlService(adminUsers(), emptyProjects()), new OutputXmlParser());
        mvc = MockMvcBuilders
                .standaloneSetup(new ReportController(ingest, mock(ReportQueryService.class),
                        new AccessControlService(adminUsers(), emptyProjects())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("完整 metadata：HTTP 解析入库后 exportable=true、计数与十项 metadata 一致")
    void parse_完整XML入库() throws Exception {
        mvc.perform(post("/api/reports/{runId}/parse", RUN_ID)
                        .param("projectId", Long.toString(PROJECT))
                        .param("outputXmlPath", fixture("pabot-merged.xml").toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value(RUN_ID))
                .andExpect(jsonPath("$.data.exportable").value(true))
                .andExpect(jsonPath("$.data.metadataMissing").isEmpty())
                .andExpect(jsonPath("$.data.metadata.DUT").value("GPU-01"))
                .andExpect(jsonPath("$.data.counts.pass").value(2))
                .andExpect(jsonPath("$.data.counts.fail").value(1))
                .andExpect(jsonPath("$.data.counts.skip").value(1));

        ArgumentCaptor<ParsedReport> captor = ArgumentCaptor.forClass(ParsedReport.class);
        verify(reports).replace(eq(PROJECT), eq(1L), captor.capture(), any(GateResult.class), any());
        ParsedReport parsed = captor.getValue();
        assertEquals(4, parsed.totalCount(), "4 条用例（2 pass / 1 fail / 1 skip）应全部入库");
        assertEquals(10, parsed.metadata().size(), "6 必填 + 4 推荐必须一起解析出来");
        assertThat(parsed.metadata()).containsKey("RUN_ID");
    }

    @Test
    @DisplayName("缺项 XML：HTTP 入库后 exportable=false，缺失项原样返回给前端")
    void parse_缺项XML不可导出() throws Exception {
        mvc.perform(post("/api/reports/{runId}/parse", RUN_ID)
                        .param("projectId", Long.toString(PROJECT))
                        .param("outputXmlPath", fixture("ok.xml").toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exportable").value(false))
                .andExpect(jsonPath("$.data.metadataMissing.length()").value(10));
    }

    @Test
    @DisplayName("显式路径不存在：400 + 中文说明，不能落成 500")
    void parse_路径不存在() throws Exception {
        mvc.perform(post("/api/reports/{runId}/parse", RUN_ID)
                        .param("projectId", Long.toString(PROJECT))
                        .param("outputXmlPath", "/tmp/definitely-not-here/output.xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("output.xml 不存在或不可读")));
    }

    @Test
    @DisplayName("运行不存在：404，而不是解析到一半才报空指针")
    void parse_运行不存在() throws Exception {
        mvc.perform(post("/api/reports/{runId}/parse", "NO-SUCH-RUN")
                        .param("projectId", Long.toString(PROJECT))
                        .param("outputXmlPath", fixture("ok.xml").toString()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 内部工具

    private static RunRecord finishedRun() {
        return new RunRecord(1L, RUN_ID, PROJECT, null, RunState.FINISHED,
                0, 1, 1, 0, 0, 0, null, null, null, null,
                Instant.parse("2026-10-31T05:00:00Z"), null, null, null, null);
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(ReportParseApiTest.class.getClassLoader()
                .getResource("fixtures/output-xml/" + name).toURI());
    }

    private static UserRepository adminUsers() {
        return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class}, (proxy, method, args) ->
                        "roleCodesOf".equals(method.getName())
                                ? List.of("admin") : defaultValue(method.getReturnType()));
    }

    private static ProjectRepository emptyProjects() {
        return (ProjectRepository) Proxy.newProxyInstance(ProjectRepository.class.getClassLoader(),
                new Class<?>[]{ProjectRepository.class}, (proxy, method, args) ->
                        defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }
}
