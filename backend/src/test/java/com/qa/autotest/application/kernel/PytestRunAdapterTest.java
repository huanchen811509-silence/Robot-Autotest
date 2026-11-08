package com.qa.autotest.application.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.PabotCommandBuilder;
import com.qa.autotest.application.execution.PabotOrchestrator;
import com.qa.autotest.application.execution.ProcessLauncher;
import com.qa.autotest.application.execution.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.7 TC-01：pytest 执行适配器——argv 指向 venv pytest、env 四件套与 pabot 同规则、
 * 产物可归一即成功；TC-02 的 RF 逐字等价由 PabotCommandBuilderTest 继续锁死。
 */
class PytestRunAdapterTest {

    @TempDir
    Path dir;

    @Test
    void buildUsesVenvPytestAndFourEnvKeys() {
        ExecutionProperties props = new ExecutionProperties();
        PytestRunAdapter adapter = adapter(props, null, 0);
        PabotCommandBuilder.PabotCommand command = adapter.build(
                1L, "RUNX", List.of("tests/"), 1, dir, dir);

        assertTrue(command.argv().get(0).endsWith("/pytest"),
                "argv[0] 应为 venv 绝对路径: " + command.argv().get(0));
        assertTrue(command.argv().stream().anyMatch(a -> a.startsWith("--json-report-file=")));
        assertTrue(command.argv().stream().anyMatch(a -> a.startsWith("--junitxml=")));
        assertTrue(command.argv().contains("--metadata"));
        int at = command.argv().indexOf("--metadata");
        assertEquals("RUN_ID", command.argv().get(at + 1));
        assertEquals("RUNX", command.argv().get(at + 2));
        assertTrue(command.argv().contains("tests/"));
        // env 四件套与 PabotCommandBuilder 同规则
        assertTrue(command.environment().get("PATH").contains(".venv/bin"));
        assertEquals("Asia/Shanghai", command.environment().get("TZ"));
        assertEquals("C.UTF-8", command.environment().get("LANG"));
        assertEquals("1", command.environment().get("PYTHONUNBUFFERED"));
        assertEquals(dir.resolve("kernel-pytest-0"), command.outputDir());
    }

    @Test
    void runSucceedsWhenArtifactNormalizable() throws Exception {
        Path outputDir = dir.resolve("kernel-pytest-0");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("pytest-report.json"), """
                {"tests":[
                  {"nodeid":"tests/test_a.py::test_ok","outcome":"passed","duration":0.01},
                  {"nodeid":"tests/test_a.py::test_xfail","outcome":"xfailed","duration":0.0}
                ]}""");
        PytestRunAdapter adapter = adapter(new ExecutionProperties(), null, 0);
        PabotOrchestrator.ShardResult result = adapter.run("RUNY",
                adapter.build(1L, "RUNY", List.of("tests/"), 1, dir, dir), Duration.ofSeconds(30));

        assertTrue(result.success());
        assertEquals(2, result.totalTests());
        assertEquals(0, result.exitCode());
    }

    @Test
    void runFailsWhenArtifactsMissing() {
        PytestRunAdapter adapter = adapter(new ExecutionProperties(), null, 0);
        PabotOrchestrator.ShardResult result = adapter.run("RUNZ",
                adapter.build(1L, "RUNZ", List.of("tests/"), 1, dir, dir), Duration.ofSeconds(30));
        assertFalse(result.success());
        assertTrue(result.reason().contains("pytest_artifact_invalid"));
    }

    @Test
    void runFailsOnNonZeroExitEvenWithArtifacts() throws Exception {
        Path outputDir = dir.resolve("kernel-pytest-0");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("pytest-report.json"), """
                {"tests":[{"nodeid":"t::a","outcome":"failed","duration":0.01}]}""");
        PytestRunAdapter adapter = adapter(new ExecutionProperties(), null, 1);
        PabotOrchestrator.ShardResult result = adapter.run("RUN1",
                adapter.build(1L, "RUN1", List.of("tests/"), 1, dir, dir), Duration.ofSeconds(30));
        assertFalse(result.success());
        assertTrue(result.reason().contains("pytest_exit_"));
        assertEquals(1, result.totalTests());
    }

    /** 假启动器：不真跑进程，退出码可注入；stdout/stderr 给空流。 */
    private static PytestRunAdapter adapter(ExecutionProperties props,
                                            AtomicReference<String> captured, int exitCode) {
        ProcessLauncher launcher = command -> new ProcessLauncher.ExecutionHandle() {
            @Override
            public long pid() {
                return 4242;
            }

            @Override
            public int waitFor(Duration timeout) {
                if (captured != null) {
                    captured.set(String.join(" ", command.argv()));
                }
                return exitCode;
            }

            @Override
            public InputStream stdout() {
                return InputStream.nullInputStream();
            }

            @Override
            public InputStream stderr() {
                return InputStream.nullInputStream();
            }

            @Override
            public Process process() {
                return null;
            }
        };
        return new PytestRunAdapter(
                new PytestKernel(new ObjectMapper(), props), props, launcher,
                new ProcessRegistry(), Clock.systemUTC());
    }
}
