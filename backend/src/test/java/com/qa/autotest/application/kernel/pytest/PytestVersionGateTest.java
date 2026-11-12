package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.execution.ExecutionPrecheck;
import com.qa.autotest.application.execution.FileSystemProbe;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解释器版本门槛（FR-v5.1-12 / 验收 A28 / TC-v5.1-15）。
 *
 * <p>用例库 {@code pyproject.toml} 写的是 {@code requires-python >= "3.11"}。
 * 低于这条线的解释器必须在**执行前**被拒，而不是跑到一半抛语法错。
 *
 * <p>版本号比较必须是逐段数值比较：按字典序会得出 {@code "3.9" > "3.11"}，
 * 那正好把最常见的「老环境 3.9」放过去——这是本组用例存在的主要理由。
 */
class PytestVersionGateTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void 逐段数值比较而不是字典序() {
        // 字典序下 "3.9" > "3.11"，数值比较下必须反过来
        assertFalse(PytestContractProbe.atLeast("3.9.18", "3.11"), "3.9 应低于 3.11");
        assertTrue(PytestContractProbe.atLeast("3.11.0", "3.11"));
        assertTrue(PytestContractProbe.atLeast("3.11", "3.11"), "同版本视为达标");
        assertTrue(PytestContractProbe.atLeast("3.12.4", "3.11"));
        assertFalse(PytestContractProbe.atLeast("3.10.12", "3.11"));
        assertFalse(PytestContractProbe.atLeast("2.7.18", "3.11"));
    }

    @Test
    void 版本取不到时不拦截() {
        // 探测不到版本就拦，会变成新的误拦来源；交给 pytest --help 的实际结果说话
        assertTrue(PytestContractProbe.atLeast("未知", "3.11"));
        assertTrue(PytestContractProbe.atLeast(null, "3.11"));
    }

    @Test
    void 三点十给出带修复建议的说明() {
        PytestContractProbe.ProbeResult result = new PytestContractProbe.ProbeResult(
                PytestNicKernelCommand.Contract.minimal(), "8.0.0", "3.10.12", List.of(), 12, null);
        assertTrue(result.healthy(), "版本低不等于探测失败，两件事要分开");
        String problem = result.versionProblem();
        assertNotNull(problem);
        assertTrue(problem.contains("3.10.12"), "说明里要点出实际版本：" + problem);
        assertTrue(problem.contains("3.11"), "说明里要点出要求版本：" + problem);
        assertTrue(problem.contains("python_bin"), "说明里要给修复落点：" + problem);
    }

    @Test
    void 三点十一不报版本问题() {
        PytestContractProbe.ProbeResult result = new PytestContractProbe.ProbeResult(
                PytestNicKernelCommand.Contract.minimal(), "8.0.0", "3.11.9", List.of(), 12, null);
        assertNull(result.versionProblem());
    }

    @Test
    void pytest_内核下版本过低报_PYTHON_TOO_OLD_且不查_pabot() {
        PytestContractProbe.ProbeResult probe = new PytestContractProbe.ProbeResult(
                PytestNicKernelCommand.Contract.minimal(), "8.0.0", "3.10.12", List.of(), 12, null);
        List<ExecutionPrecheck.Violation> violations =
                new ExecutionPrecheck(new OkFs(), p -> Optional.empty()).check(
                        new ExecutionPrecheck.PrecheckRequest(
                                Path.of("/venv-rf/bin"), Path.of("/artifacts"), List.of("a.py"),
                                1, 32, 5 * GIB, "5.2", List.of(), "pytest", Path.of("/bin/python3"),
                                List.of(), List.of(), probe.versionProblem()));
        List<String> codes = violations.stream().map(ExecutionPrecheck.Violation::code).toList();
        assertTrue(codes.contains("PYTHON_TOO_OLD"), "应报版本过低：" + codes);
        assertFalse(codes.contains("PABOT_NOT_FOUND"),
                "pytest 工程没有 pabot 是正常的，不许因此误拦：" + codes);
    }

    @Test
    void 版本达标时_pytest_预检无违规() {
        List<ExecutionPrecheck.Violation> violations =
                new ExecutionPrecheck(new OkFs(), p -> Optional.empty()).check(
                        new ExecutionPrecheck.PrecheckRequest(
                                Path.of("/venv-rf/bin"), Path.of("/artifacts"), List.of("a.py"),
                                1, 32, 5 * GIB, "5.2", List.of(), "pytest", Path.of("/bin/python3"),
                                List.of(), List.of(), null));
        assertEquals(List.of(), violations);
    }

    private static final class OkFs implements FileSystemProbe {

        @Override
        public boolean exists(Path path) {
            return true;
        }

        @Override
        public boolean executable(Path path) {
            return true;
        }

        @Override
        public boolean writable(Path path) {
            return true;
        }

        @Override
        public long usableSpace(Path path) {
            return Long.MAX_VALUE;
        }
    }
}
