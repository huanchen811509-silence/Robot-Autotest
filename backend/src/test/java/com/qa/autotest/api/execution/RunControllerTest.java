package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.CancelService;
import com.qa.autotest.application.execution.CaseRunService;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.LeaseWaitRegistry;
import com.qa.autotest.application.execution.QuickRunService;
import com.qa.autotest.application.execution.RunDispatcher;
import com.qa.autotest.application.execution.RunLogFileService;
import com.qa.autotest.application.execution.RunLogService;
import com.qa.autotest.application.execution.RunProgressRegistry;
import com.qa.autotest.application.execution.RunService;
import com.qa.autotest.application.execution.RunService.CreateRunCommand;
import com.qa.autotest.application.execution.TestSetRunService;
import com.qa.autotest.application.repository.RunRetryOrigin;
import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.RunOrigin;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EXEC-01 接口层：路由、状态码映射、body → command 透传、CI 端点派发。
 */
class RunControllerTest {

    private RunService runs;
    private QuickRunService quickRuns;
    private TestSetRunService testSetRuns;
    private CaseRunService caseRuns;
    private RunLogFileService logFiles;
    private RunDispatcher dispatcher;
    private LeaseWaitRegistry leaseWaits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runs = mock(RunService.class);
        quickRuns = mock(QuickRunService.class);
        testSetRuns = mock(TestSetRunService.class);
        caseRuns = mock(CaseRunService.class);
        logFiles = mock(RunLogFileService.class);
        dispatcher = mock(RunDispatcher.class);
        leaseWaits = mock(LeaseWaitRegistry.class);
        RunController controller = new RunController(runs, mock(CancelService.class),
                mock(RunLogService.class), new ExecutionProperties(), quickRuns, testSetRuns,
                caseRuns, logFiles, dispatcher, leaseWaits, mock(RunProgressRegistry.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("quick-run：参数错误 400、越权 403，错误码原样返回")
    void quickRun_状态码映射() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "至少选择一个套件路径"))
                .when(quickRuns).trigger(any());
        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":7,\"suitePaths\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("至少选择一个套件路径"));

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "缺少权限 EXECUTION:EXECUTE"))
                .when(quickRuns).trigger(any());
        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":7,\"suitePaths\":[\"suites/a.robot\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codeName").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("quick-run：来源页面与套件路径原样透传")
    void quickRun_参数透传() throws Exception {
        when(quickRuns.trigger(any())).thenReturn(run("RUN-1"));

        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":7,"suitePaths":["suites/a.robot"],
                                 "concurrency":2,"timeoutSeconds":600,"source":"runs-view"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-1"));

        ArgumentCaptor<QuickRunService.QuickRunCommand> captor =
                ArgumentCaptor.forClass(QuickRunService.QuickRunCommand.class);
        verify(quickRuns).trigger(captor.capture());
        assertEquals(List.of("suites/a.robot"), captor.getValue().suitePaths());
        assertEquals("runs-view", captor.getValue().source());
        assertEquals(2, captor.getValue().concurrency());
    }

    @Test
    @DisplayName("幂等键：Idempotency-Key 优先于 body，两次提交透传同一请求号")
    void quickRun_幂等键优先与重复提交() throws Exception {
        when(quickRuns.trigger(any())).thenReturn(run("RUN-1"));
        String body = """
                {"projectId":7,"suitePaths":["suites/a.robot"],"runRequestId":"BODY-1"}
                """;

        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "IDEM-1").content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "IDEM-1").content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<QuickRunService.QuickRunCommand> captor =
                ArgumentCaptor.forClass(QuickRunService.QuickRunCommand.class);
        verify(quickRuns, times(2)).trigger(captor.capture());
        assertEquals(List.of("IDEM-1", "IDEM-1"),
                captor.getAllValues().stream().map(QuickRunService.QuickRunCommand::runRequestId).toList());
    }

    @Test
    @DisplayName("无 Header 时回落到 body runRequestId")
    void quickRun_body请求号兜底() throws Exception {
        when(quickRuns.trigger(any())).thenReturn(run("RUN-2"));

        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":7,"suitePaths":["suites/a.robot"],"runRequestId":"BODY-2"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<QuickRunService.QuickRunCommand> captor =
                ArgumentCaptor.forClass(QuickRunService.QuickRunCommand.class);
        verify(quickRuns).trigger(captor.capture());
        assertEquals("BODY-2", captor.getValue().runRequestId());
    }

    @Test
    @DisplayName("非法 Idempotency-Key：400 且不触发执行")
    void quickRun_非法幂等键() throws Exception {
        mvc.perform(post("/api/execution/quick-run").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "bad key!")
                        .content("{\"projectId\":7,\"suitePaths\":[\"suites/a.robot\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"));
        verify(quickRuns, times(0)).trigger(any());
    }

    @Test
    @DisplayName("用例集立即执行：不存在 → 404")
    void runTestSet_不存在404() throws Exception {
        when(testSetRuns.run(any())).thenThrow(
                new BusinessException(ErrorCode.NOT_FOUND, "用例集不存在: 99"));

        mvc.perform(post("/api/execution/test-sets/99/run?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CI 直建端点：落库后交给派发器，origin=CI，plan 为空")
    void create_落库即派发() throws Exception {
        when(runs.create(any(), any())).thenReturn(run("RUN-CI"));

        mvc.perform(post("/api/execution/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":7,\"suitePaths\":[\"suites/a.robot\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-CI"));

        ArgumentCaptor<RunOrigin> origin = ArgumentCaptor.forClass(RunOrigin.class);
        ArgumentCaptor<CreateRunCommand> command = ArgumentCaptor.forClass(CreateRunCommand.class);
        verify(runs).create(command.capture(), origin.capture());
        assertEquals(RunOrigin.CI, origin.getValue().triggerType());
        assertNull(origin.getValue().planId());
        verify(quickRuns).dispatch(any(RunRecord.class), any(CreateRunCommand.class));
    }

    @Test
    @DisplayName("pending-activity：派发器有位次时原样返回；查不到时按 DB 状态兜底")
    void pendingActivity_位次与兜底() throws Exception {
        when(runs.get(1L, "RUN-1")).thenReturn(run("RUN-1"));
        when(dispatcher.status("RUN-1")).thenReturn(Optional.of(
                new RunDispatcher.QueueStatus("RUN-1", "queued", 2, 3, 1, 4)));

        mvc.perform(get("/api/execution/runs/RUN-1/pending-activity").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("queued"))
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.waiting").value(3))
                .andExpect(jsonPath("$.data.running").value(1))
                .andExpect(jsonPath("$.data.maxParallel").value(4));

        when(dispatcher.status("RUN-1")).thenReturn(Optional.empty());
        mvc.perform(get("/api/execution/runs/RUN-1/pending-activity").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("queued"))
                .andExpect(jsonPath("$.data.position").value(0));
    }

    @Test
    @DisplayName("pending-activity：等租约时返回 waiting，优先于派发器的 running（FR-08）")
    void pendingActivity_等待资源优先返回waiting() throws Exception {
        when(runs.get(1L, "RUN-1")).thenReturn(run("RUN-1"));
        when(leaseWaits.waiting("RUN-1")).thenReturn(true);
        when(dispatcher.status("RUN-1")).thenReturn(Optional.of(
                new RunDispatcher.QueueStatus("RUN-1", "running", 0, 0, 1, 4)));

        mvc.perform(get("/api/execution/runs/RUN-1/pending-activity").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("waiting"))
                .andExpect(jsonPath("$.data.maxParallel").value(4));
    }

    @Test
    @DisplayName("lease-wait：等待中给出实时耗时、总耗时为空；拿到租约后回落到落库值（FR-08）")
    void leaseWait_耗时回读() throws Exception {
        when(runs.leaseWaitTotalMs(1L, "RUN-1")).thenReturn(null);
        when(leaseWaits.waiting("RUN-1")).thenReturn(true);
        when(leaseWaits.elapsed("RUN-1")).thenReturn(4200L);

        mvc.perform(get("/api/execution/runs/RUN-1/lease-wait").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("waiting"))
                .andExpect(jsonPath("$.data.elapsedMs").value(4200))
                .andExpect(jsonPath("$.data.totalMs").value(nullValue()));

        when(leaseWaits.waiting("RUN-1")).thenReturn(false);
        when(runs.leaseWaitTotalMs(1L, "RUN-1")).thenReturn(6500L);
        mvc.perform(get("/api/execution/runs/RUN-1/lease-wait").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("settled"))
                .andExpect(jsonPath("$.data.elapsedMs").value(6500))
                .andExpect(jsonPath("$.data.totalMs").value(6500));
    }

    @Test
    @DisplayName("retry-origin：带出上一次尝试的失败结论；首次尝试 data 为 null（FR-v0.4-29）")
    void retryOrigin_上一次尝试() throws Exception {
        when(runs.retryOrigin(1L, "RUN-2")).thenReturn(new RunRetryOrigin("RUN-1", RunState.FAILED,
                "ENV", "dut_unreachable", "SSH 连接超时",
                Instant.parse("2026-11-05T00:50:00Z")));

        mvc.perform(get("/api/execution/runs/RUN-2/retry-origin").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.failureClass").value("ENV"))
                .andExpect(jsonPath("$.data.failureReason").value("dut_unreachable"))
                .andExpect(jsonPath("$.data.failureMessage").value("SSH 连接超时"));

        // 首次尝试没有上一条记录：200 + data=null，调用方按「没有上一次」处理即可
        when(runs.retryOrigin(1L, "RUN-1")).thenReturn(null);
        mvc.perform(get("/api/execution/runs/RUN-1/retry-origin").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("勾选用例执行：整单参数原样透传；未绑定 400、越权 403")
    void runCases_参数与状态码() throws Exception {
        when(caseRuns.run(any())).thenReturn(run("RUN-CASE"));

        mvc.perform(post("/api/execution/cases/run?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseIds":[11,12],"environmentId":3,"concurrency":2,
                                 "timeoutSeconds":600,"includeTag":"smoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("RUN-CASE"));

        ArgumentCaptor<CaseRunService.CaseRunCommand> captor =
                ArgumentCaptor.forClass(CaseRunService.CaseRunCommand.class);
        verify(caseRuns).run(captor.capture());
        assertEquals(List.of(11L, 12L), captor.getValue().caseIds());
        assertEquals(3L, captor.getValue().environmentId());
        assertEquals(2, captor.getValue().concurrency());
        assertEquals("smoke", captor.getValue().includeTag());

        doThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "存在未绑定脚本的用例"))
                .when(caseRuns).run(any());
        mvc.perform(post("/api/execution/cases/run?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caseIds\":[11]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"));

        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "缺少权限 EXECUTION:EXECUTE"))
                .when(caseRuns).run(any());
        mvc.perform(post("/api/execution/cases/run?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caseIds\":[11]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("安全级预检：透传 caseIds，返回被拦编号与安全级；空选 400")
    void precheckCases_参数与结果() throws Exception {
        when(caseRuns.precheck(7L, List.of(11L, 12L))).thenReturn(
                new CaseRunService.SafetyPrecheck(2, false,
                        List.of(new CaseRunService.BlockedCase(12L, "DESTRUCT-001", "L2"))));

        mvc.perform(post("/api/execution/cases/safety-precheck?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[11,12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.blocked[0].caseId").value(12))
                .andExpect(jsonPath("$.data.blocked[0].caseCode").value("DESTRUCT-001"))
                .andExpect(jsonPath("$.data.blocked[0].safetyLevel").value("L2"));

        when(caseRuns.precheck(7L, null))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "至少选择一条用例"));
        mvc.perform(post("/api/execution/cases/safety-precheck?projectId=7")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codeName").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("完整日志下载：attachment + text/plain；文件缺失 404")
    void downloadLog_响应头与404() throws Exception {
        RunRecord record = run("RUN-1");
        when(runs.get(7L, "RUN-1")).thenReturn(record);
        when(logFiles.download(record))
                .thenReturn(new ByteArrayResource("line1\n".getBytes()));

        mvc.perform(get("/api/execution/runs/RUN-1/log/download?projectId=7"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("run-RUN-1.log")))
                .andExpect(content().string("line1\n"));

        when(logFiles.download(record))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "还没有完整日志"));
        mvc.perform(get("/api/execution/runs/RUN-1/log/download?projectId=7"))
                .andExpect(status().isNotFound());
    }

    private static RunRecord run(String runId) {
        return new RunRecord(1L, runId, 7L, null, RunState.QUEUED, 0, 1, 0, 0, 0, 0,
                null, null, null, null, Instant.EPOCH, null, null, null, null);
    }
}
