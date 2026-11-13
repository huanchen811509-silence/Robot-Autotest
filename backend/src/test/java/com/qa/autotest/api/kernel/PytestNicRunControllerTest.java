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
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** EHT-14 / FR-v5.5-08：Fake 执行入口的三条硬判据（拒绝 real / 通过率不适用 / 配置缺失拒绝）。 */
class PytestNicRunControllerTest {

    private MockMvc mvc(PytestNicRunService runs, ProjectSettingRepository settings) {
        return MockMvcBuilders.standaloneSetup(
                        new PytestNicRunController(runs, settings, mock(RunRepository.class),
                                mock(AccessControlService.class)))
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

    @Test
    @DisplayName("G-1 未授权：real 模式必须 400，且不触发执行")
    void realModeRejected() throws Exception {
        PytestNicRunService runs = mock(PytestNicRunService.class);
        ProjectSettingRepository settings = mock(ProjectSettingRepository.class);
        mvc(runs, settings).perform(post("/api/projects/95/pytest/runs/fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"real\"}"))
                .andExpect(status().isBadRequest());
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
}
