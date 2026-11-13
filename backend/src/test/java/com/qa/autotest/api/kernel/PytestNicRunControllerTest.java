package com.qa.autotest.api.kernel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.autotest.api.GlobalExceptionHandler;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.PytestNicRunService;
import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunArtifactRepository;
import com.qa.autotest.application.repository.RunMetadataRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
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

    private MockMvc mvc(PytestNicRunService runs, ProjectSettingRepository settings) {
        return MockMvcBuilders.standaloneSetup(
                        new PytestNicRunController(runs, settings, mock(RunRepository.class),
                                artifacts, mock(com.qa.autotest.application.repository.RunMetadataRepository.class),
                                bindings, cases, mock(AccessControlService.class)))
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
}
