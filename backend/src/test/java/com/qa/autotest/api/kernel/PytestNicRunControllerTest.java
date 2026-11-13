package com.qa.autotest.api.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.execution.ProcessRegistry;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestReadinessService;
import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.application.kernel.pytest.PytestRunProgressRegistry;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * EHT 执行入口的硬判据：fake 端点不静默降级、real 需显式授权 + 安全闸、通过率口径。
 *
 * <p>安全闸用例是关键：LX（安全级未判定）与 L2（高危）必须拒跑，且报错要带用例编号，
 * 否则「为什么跑不了」又要靠人去猜。
 */
class PytestNicRunControllerTest {

    private final RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
    private final CaseScriptBindingRepository bindings = mock(CaseScriptBindingRepository.class);
    private final TestCaseRepository cases = mock(TestCaseRepository.class);

    /** 控制器依赖在 v5.6.0 从 8 个涨到 13 个（进度 / 就绪度 / 设备 / 进程表 / 入库器），统一在这里拼。 */
    private PytestNicRunController controller(PytestNicRunService runs,
                                              ProjectSettingRepository settings,
                                              RunRepository runRepo,
                                              PytestRunProgressRegistry progress) {
        return controller(runs, settings, runRepo, progress, null);
    }

    private PytestNicRunController controller(PytestNicRunService runs,
                                              ProjectSettingRepository settings,
                                              RunRepository runRepo,
                                              PytestRunProgressRegistry progress,
                                              com.qa.autotest.application.report.ReportIngestService ingest) {
        return new PytestNicRunController(runs, settings, runRepo, artifacts,
                mock(RunMetadataRepository.class), bindings, cases,
                progress, mock(PytestReadinessService.class), mock(DeviceRepository.class),
                mock(ProcessRegistry.class), mock(AccessControlService.class),
                new com.qa.autotest.application.report.PytestCommandEnricher(), ingest);
    }

    private MockMvc mvc(PytestNicRunService runs, ProjectSettingRepository settings) {
        return mvc(runs, settings, mock(RunRepository.class), new PytestRunProgressRegistry());
    }

    private MockMvc mvc(PytestNicRunService runs, ProjectSettingRepository settings,
                        RunRepository runRepo, PytestRunProgressRegistry progress) {
        return MockMvcBuilders.standaloneSetup(controller(runs, settings, runRepo, progress))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private PytestNicRunService.ExecuteResult fakeResult() {
        NormalizedCaseResult one = new NormalizedCaseResult(
                "test_demo.py::test_a", "CASE-1", CaseStatus.SKIP, 0.02, "plan only");
        NormalizedRun run = new NormalizedRun("pytest", "EHT-FAKE-TEST",
                List.of(one), Map.of("RUN_ID", "EHT-FAKE-TEST"));
        PytestResultNormalizer.NormalizeResult normalized =
                new PytestResultNormalizer.NormalizeResult(run, List.of(), 0, true, null);
        return new PytestNicRunService.ExecuteResult(true, "ok", run, null, normalized,
                1, 0, Duration.ofMillis(5), List.of(), null);
    }

    private static TestCase testCase(long id, String code, String safetyLevel) {
        return new TestCase(id, 95L, code, "标题", "以太网", "T0", null, null,
                "lab", "APPROVED", "import", "nic", null, safetyLevel);
    }

    private static CaseScriptBinding binding(long caseId, String nodeId) {
        return new CaseScriptBinding(1L, 95L, caseId, "pytest", "tests/x.py",
                nodeId, "sha", CaseScriptBinding.SOURCE_DOCSTRING, false);
    }

    @Test
    @DisplayName("fake 端点收到 real 必须 400：不静默降级，避免「以为跑了真机」")
    void realOnFakeEndpointRejected() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\"}"))
                .andExpect(status().isBadRequest());
        verify(runs, never()).execute(any());
    }

    @Test
    @DisplayName("real 未带 confirmReal 必须 400，且不触发执行")
    void realWithoutConfirmRejected() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\"}"))
                .andExpect(status().isBadRequest());
        verify(runs, never()).execute(any());
    }

    @Test
    @DisplayName("ADR-0013：LX 未判定的用例在 real 下必须拒跑，并点名用例编号")
    void realBlocksUndecidedSafetyLevel() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(bindings.findByNodeId(anyLong(), any(), any()))
                .thenReturn(Optional.of(binding(11L, "tests/x.py::test_a")));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(
                testCase(11L, "IM-NET-999", "LX")));
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\",\"confirmReal\":true,"
                                + "\"nodeIds\":[\"tests/x.py::test_a\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("IM-NET-999")));
        verify(runs, never()).execute(any());
    }

    @Test
    @DisplayName("安全级 L0 且 confirmReal=true 时放行到执行层")
    void realAllowedWhenSafetyDecided() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(bindings.findByNodeId(anyLong(), any(), any()))
                .thenReturn(Optional.of(binding(11L, "tests/x.py::test_a")));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(
                testCase(11L, "IM-NET-001", "L0")));
        when(runs.execute(any())).thenReturn(fakeResult());
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\",\"confirmReal\":true,"
                                + "\"nodeIds\":[\"tests/x.py::test_a\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("real"))
                .andExpect(jsonPath("$.data.planOnly").value(false));
        verify(runs).execute(any());
    }

    @Test
    @DisplayName("fail-closed：real 点名未绑定的 nodeId 必须拒跑，不能绕过安全闸")
    void realRejectsUnmappedNode() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(bindings.findByNodeId(anyLong(), any(), any())).thenReturn(Optional.empty());
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\",\"confirmReal\":true,"
                                + "\"nodeIds\":[\"tests/x.py::test_typo\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("test_typo")));
        verify(runs, never()).execute(any());
    }

    /** 带报告入库器的 MockMvc（构造器注入，测试里直接给桩）。 */
    private MockMvc mvcWithIngest(PytestNicRunService runs, ProjectSettingRepository settings,
                                  com.qa.autotest.application.report.ReportIngestService ingest) {
        PytestNicRunController built = controller(runs, settings, mock(RunRepository.class),
                new PytestRunProgressRegistry(), ingest);
        return MockMvcBuilders.standaloneSetup(built)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("跑完即入库：报告页读的是 test_result，不入库就是「跑完但报告空白」")
    void runIngestsReport() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(runs.execute(any())).thenReturn(fakeResult());
        com.qa.autotest.application.report.ReportIngestService ingest =
                mock(com.qa.autotest.application.report.ReportIngestService.class);
        mvcWithIngest(runs, settings, ingest)
                .perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-INGEST-1\"}"))
                .andExpect(status().isOk());
        verify(ingest).parseAndIngest(95L, "EHT-INGEST-1", null);
    }

    @Test
    @DisplayName("入库失败不改判执行结果：先保住执行事实，让人手工补 parse")
    void ingestFailureKeepsRunVerdict() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(runs.execute(any())).thenReturn(fakeResult());
        com.qa.autotest.application.report.ReportIngestService ingest =
                mock(com.qa.autotest.application.report.ReportIngestService.class);
        when(ingest.parseAndIngest(anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("产物目录不存在"));
        mvcWithIngest(runs, settings, ingest)
                .perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-INGEST-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("fake"));
    }

    @Test
    @DisplayName("重复 runId 返回 409，不把唯一键冲突暴露成 500")
    void duplicateRunIdConflicts() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        RunRepository runRepo = mock(RunRepository.class);
        when(runRepo.findByRunId(anyLong(), any()))
                .thenReturn(Optional.of(mock(com.qa.autotest.domain.execution.RunRecord.class)));
        MockMvc mvc = mvc(runs, settings, runRepo, new PytestRunProgressRegistry());
        mvc.perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-DUP-1\"}"))
                .andExpect(status().isConflict());
        verify(runs, never()).execute(any());
    }

    @Test
    @DisplayName("缺 pytest.repo_path 必须 400，不拿空路径硬跑")
    void missingRepoPathRejected() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of());
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(runs, never()).execute(any());
    }

    @Test
    @DisplayName("Fake 混合结果（含 FAIL/ERROR）时通过率仍「不适用」，非 SKIP 数如实暴露")
    void mixedFakeResultsStillNotApplicable() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any()))
                .thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        NormalizedCaseResult skipped = new NormalizedCaseResult(
                "test_demo.py::test_a", "CASE-1", CaseStatus.SKIP, 0.02, "plan only");
        NormalizedCaseResult errored = new NormalizedCaseResult(
                "test_demo.py::test_b", "CASE-2", CaseStatus.FAIL, 0.03, "资源池缺项");
        NormalizedRun run = new NormalizedRun("pytest", "EHT-FAKE-TEST",
                List.of(skipped, errored), Map.of("RUN_ID", "EHT-FAKE-TEST"));
        PytestResultNormalizer.NormalizeResult normalized =
                new PytestResultNormalizer.NormalizeResult(run, List.of(), 0, false, null);
        when(runs.execute(any())).thenReturn(new PytestNicRunService.ExecuteResult(
                false, "pytest_exit_1", run, null, normalized,
                1, 1, Duration.ofMillis(5), List.of(), null));
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-FAKE-TEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passRateApplicable").value(false))
                .andExpect(jsonPath("$.data.passRateDisplay").value("不适用"))
                .andExpect(jsonPath("$.data.nonSkippedCases").value(1))
                .andExpect(jsonPath("$.data.planOnly").value(true));
    }

    @Test
    @DisplayName("全 SKIP 时通过率显示「不适用」，三条件从 run.log 现场判定而非写死")
    void fakeRunReportsNotApplicable() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any()))
                .thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        when(runs.execute(any())).thenReturn(fakeResult());
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-FAKE-TEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCases").value(1))
                .andExpect(jsonPath("$.data.statusCounts.SKIP").value(1))
                .andExpect(jsonPath("$.data.passRateApplicable").value(false))
                .andExpect(jsonPath("$.data.passRateDisplay").value("不适用"))
                .andExpect(jsonPath("$.data.junitConditions['--capture=fd']").value(false));
        verify(runs).execute(any());
    }

    // ------------------------------------------------------------------
    // v5.6.0 / FR-v5.6-11~14：异步、进度、取消、预检
    // ------------------------------------------------------------------

    private static RunRecord finishedRecord(String runId) {
        Instant at = Instant.parse("2026-11-13T10:00:00Z");
        return new RunRecord(1L, runId, 95L, null, RunState.FINISHED, 0, 1, 1,
                59, 57, 2, null, null, null, "/tmp/eht", at, at, at, null, null);
    }

    @Test
    @DisplayName("异步端点返 202 + runId，并在后台跑完后进度落终态")
    void asyncReturnsAcceptedAndFinishes() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        when(runs.execute(any(), any())).thenReturn(fakeResult());
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        RunRepository runRepo = mock(RunRepository.class);
        when(runRepo.findByRunId(anyLong(), any())).thenReturn(Optional.empty());
        PytestRunProgressRegistry progress = new PytestRunProgressRegistry();
        MockMvc mvc = mvc(runs, settings, runRepo, progress);

        mvc.perform(post("/api/projects/95/pytest/runs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"EHT-ASYNC-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("EHT-ASYNC-1"));

        // 后台线程：轮询到终态为止（最多 3 秒）。这不是「等一会儿再断言」，
        // 而是异步接口的判据本身就是「终态会出现」。
        for (int i = 0; i < 60 && !progress.snapshot("EHT-ASYNC-1")
                .map(PytestRunProgressRegistry.Snapshot::terminal).orElse(false); i++) {
            Thread.sleep(50);
        }
        PytestRunProgressRegistry.Snapshot snapshot =
                progress.snapshot("EHT-ASYNC-1").orElseThrow();
        assertEquals(true, snapshot.terminal(), "异步执行必须自己落终态，否则页面永远转圈");
        assertEquals("FINISHED", snapshot.stage());
    }

    @Test
    @DisplayName("异步端点同样受安全闸约束：real 缺 confirmReal 仍然 400")
    void asyncStillRequiresConfirmReal() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\"}"))
                .andExpect(status().isBadRequest());
        verify(runs, never()).execute(any());
        verify(runs, never()).execute(any(), any());
    }

    @Test
    @DisplayName("取消后进度落 CANCELED，且已完成的批次计数不被清空")
    void cancelMarksCanceled() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        RunRepository runRepo = mock(RunRepository.class);
        when(runRepo.findByRunId(anyLong(), any())).thenReturn(Optional.empty());
        PytestRunProgressRegistry progress = new PytestRunProgressRegistry();
        progress.begin("EHT-CANCEL-1", 59);
        progress.batchDone("EHT-CANCEL-1", 20, 1, 21);

        mvc(runs, settings, runRepo, progress)
                .perform(post("/api/projects/95/pytest/runs/EHT-CANCEL-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canceled").value(true));

        PytestRunProgressRegistry.Snapshot snapshot =
                progress.snapshot("EHT-CANCEL-1").orElseThrow();
        assertEquals("CANCELED", snapshot.stage());
        assertEquals(21, snapshot.finished(), "取消不清已完成计数——那不是「没跑过」");
        assertEquals(20, snapshot.passed());
    }

    @Test
    @DisplayName("预检把台架排在第一位，缺项计入 blocking")
    void precheckPutsBenchFirst() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        when(settings.findAll(anyLong(), any())).thenReturn(Map.of("repo_path", "/tmp/eht-repo"));
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.listAll(anyLong())).thenReturn(List.of());
        PytestReadinessService readiness = mock(PytestReadinessService.class);
        when(readiness.readiness(anyLong())).thenReturn(new PytestReadinessService.Readiness(
                false,
                List.of(new PytestReadinessService.Item("devices", false, 0),
                        new PytestReadinessService.Item("credentials", false, 0),
                        new PytestReadinessService.Item("environment", false, 0),
                        new PytestReadinessService.Item("bindings", true, 61)),
                List.of("devices", "credentials", "environment")));

        PytestNicRunController controller = new PytestNicRunController(runs, settings,
                mock(RunRepository.class), artifacts, mock(RunMetadataRepository.class),
                bindings, cases, new PytestRunProgressRegistry(), readiness, devices,
                mock(ProcessRegistry.class), mock(AccessControlService.class),
                new com.qa.autotest.application.report.PytestCommandEnricher(), null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/projects/95/pytest/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.checks[0].key").value("bench"))
                .andExpect(jsonPath("$.data.checks[0].ok").value(false))
                .andExpect(jsonPath("$.data.blocking").value(3));
    }

    @Test
    @DisplayName("进程重启后内存表为空，进度回退到 run 表读终态而不是 404")
    void progressFallsBackToDb() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        RunRepository runRepo = mock(RunRepository.class);
        when(runRepo.findByRunId(anyLong(), any()))
                .thenReturn(Optional.of(finishedRecord("EHT-DONE-1")));

        mvc(runs, settings, runRepo, new PytestRunProgressRegistry())
                .perform(get("/api/projects/95/pytest/runs/EHT-DONE-1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("FINISHED"))
                .andExpect(jsonPath("$.data.source").value("db"))
                .andExpect(jsonPath("$.data.total").value(59));
    }

    @Test
    @DisplayName("既不在内存表也不在 run 表时给 404，不编造进度")
    void progressUnknownRunIs404() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        RunRepository runRepo = mock(RunRepository.class);
        when(runRepo.findByRunId(anyLong(), any())).thenReturn(Optional.empty());
        mvc(runs, settings, runRepo, new PytestRunProgressRegistry())
                .perform(get("/api/projects/95/pytest/runs/NOPE/progress"))
                .andExpect(status().isNotFound());
    }
}
