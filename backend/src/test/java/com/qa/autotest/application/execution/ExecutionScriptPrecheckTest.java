package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.iam.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * FR-READY-07/10/11：执行前统一脚本预检（软校验清单）。
 *
 * <p>每条断言对应确认面板上的一个可见后果：清单里多一条/少一条，用户就会误判能不能跑。
 */
@ExtendWith(MockitoExtension.class)
class ExecutionScriptPrecheckTest {

    @Mock
    private TestCaseRepository cases;
    @Mock
    private CaseScriptBindingRepository bindings;
    @Mock
    private ProjectSettingRepository settings;
    @Mock
    private ProjectRepository projects;

    @TempDir
    Path tempDir;

    private ExecutionScriptPrecheck precheck() {
        return new ExecutionScriptPrecheck(cases, bindings, settings, projects);
    }

    private static Project project(long id, String kernel) {
        return new Project(id, "EHT", "EHT 工程", null, true, 1L, "private", kernel,
                "pytest-suite", null);
    }

    private static TestCase testCase(long id, String code) {
        return new TestCase(id, 1L, code, "title", "auto", "P1", null, null, null,
                "待评审", null, null, null, "L0");
    }

    @Test
    void robotKernelAllNotApplicable() {
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L, "robot")));

        ExecutionScriptPrecheck.ScriptPrecheck r = precheck().check(1L, List.of(1L, 2L, 3L));

        assertThat(r.boundCount()).isZero();
        assertThat(r.unbound()).isEmpty();
        assertThat(r.notApplicableCount()).isEqualTo(3);
    }

    @Test
    void pytestBoundCaseCounted() throws Exception {
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L, "pytest")));
        Path script = tempDir.resolve("tests/test_a.py");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "def test_a(): pass\n");
        stubRepoPath(tempDir.toString());
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(testCase(1L, "IM-FUN-001")));
        when(bindings.findByCase(1L, 1L, "pytest")).thenReturn(Optional.of(
                binding(1L, "tests/test_a.py", "tests/test_a.py::test_a")));

        ExecutionScriptPrecheck.ScriptPrecheck r = precheck().check(1L, List.of(1L));

        assertThat(r.boundCount()).isEqualTo(1);
        assertThat(r.unbound()).isEmpty();
    }

    @Test
    void unboundCaseListedWithReason() {
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L, "pytest")));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(testCase(2L, "IM-FUN-002")));
        when(bindings.findByCase(1L, 2L, "pytest")).thenReturn(Optional.empty());

        ExecutionScriptPrecheck.ScriptPrecheck r = precheck().check(1L, List.of(2L));

        assertThat(r.boundCount()).isZero();
        assertThat(r.unbound()).hasSize(1);
        assertThat(r.unbound().get(0).caseCode()).isEqualTo("IM-FUN-002");
        assertThat(r.unbound().get(0).reason())
                .isEqualTo(ExecutionScriptPrecheck.REASON_UNBOUND);
    }

    @Test
    void emptyNodeIdIsNodeIdEmpty() {
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L, "pytest")));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(testCase(3L, "IM-FUN-003")));
        when(bindings.findByCase(1L, 3L, "pytest")).thenReturn(Optional.of(
                binding(3L, "tests/test_c.py", "  ")));

        ExecutionScriptPrecheck.ScriptPrecheck r = precheck().check(1L, List.of(3L));

        assertThat(r.unbound()).hasSize(1);
        assertThat(r.unbound().get(0).reason())
                .isEqualTo(ExecutionScriptPrecheck.REASON_NODEID_EMPTY);
    }

    @Test
    void missingScriptFileIsStale() {
        when(projects.findById(1L)).thenReturn(Optional.of(project(1L, "pytest")));
        when(cases.find(anyLong(), anyLong())).thenReturn(Optional.of(testCase(4L, "IM-FUN-004")));
        when(bindings.findByCase(1L, 4L, "pytest")).thenReturn(Optional.of(
                binding(4L, "tests/gone.py", "tests/gone.py::test_gone")));
        stubRepoPath(tempDir.toString());

        ExecutionScriptPrecheck.ScriptPrecheck r = precheck().check(1L, List.of(4L));

        assertThat(r.unbound()).hasSize(1);
        assertThat(r.unbound().get(0).reason())
                .isEqualTo(ExecutionScriptPrecheck.REASON_BINDING_STALE);
    }

    private void stubRepoPath(String repoPath) {
        when(settings.findAll(anyLong(), anyString()))
                .thenReturn(Map.of("repo_path", repoPath));
    }

    private static CaseScriptBinding binding(long caseId, String scriptPath, String nodeId) {
        return new CaseScriptBinding(1L, 1L, caseId, "pytest", scriptPath, nodeId,
                "abc", CaseScriptBinding.SOURCE_DOCSTRING, true);
    }
}
