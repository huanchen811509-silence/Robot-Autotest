package com.qa.autotest.application.execution;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨内核拆单（FR-v5.2-04 / 验收 B10/B11 / W52-05）。
 *
 * <p>这组用例盯的是三件「拆完之后还成立吗」的事：用例不能丢、不能重、
 * 组内顺序不能被拆乱。任何一条坏了，报告里的用例数与执行数就对不上，
 * 而这种不一致在报告层面看起来只是「少跑了几条」，极难追回。
 */
class RunSplitterTest {

    private static RunSplitter.PlannedCase rf(String code, int priority) {
        return new RunSplitter.PlannedCase(code, "robot", "suites/" + code + ".robot", priority);
    }

    private static RunSplitter.PlannedCase py(String code, int priority) {
        return new RunSplitter.PlannedCase(code, "pytest", "tests/" + code + ".py", priority);
    }

    @Test
    void 按内核拆成两单且用例不丢不重() {
        List<RunSplitter.PlannedCase> input = List.of(
                rf("IM-NET-001", 0), py("IM-NET-002", 0), rf("IM-NET-003", 0),
                py("IM-NET-004", 0));
        List<RunSplitter.SplitRun> runs = RunSplitter.split(input);

        assertEquals(2, runs.size());
        RunSplitter.verify(input, runs);
        assertTrue(runs.stream().allMatch(r -> r.caseCount() == 2));
        // 每个内核一单，且内核字段自洽
        assertEquals(1, runs.stream().filter(r -> r.kernel().equals("robot")).count());
        assertEquals(1, runs.stream().filter(r -> r.kernel().equals("pytest")).count());
    }

    @Test
    void 组内保持用例集顺序() {
        // 故意交错输入：拆单后 RF 单里必须是 001 → 003 的原始相对顺序
        List<RunSplitter.PlannedCase> input = List.of(
                rf("A-1", 0), py("P-1", 0), rf("A-2", 0), py("P-2", 0), rf("A-3", 0));
        List<RunSplitter.SplitRun> runs = RunSplitter.split(input);
        RunSplitter.SplitRun rfRun = runs.stream()
                .filter(r -> r.kernel().equals("robot")).findFirst().orElseThrow();
        assertEquals(List.of("A-1", "A-2", "A-3"),
                rfRun.cases().stream().map(RunSplitter.PlannedCase::caseCode).toList());
        assertEquals(List.of("suites/A-1.robot", "suites/A-2.robot", "suites/A-3.robot"),
                rfRun.suitePaths());
    }

    @Test
    void 同一文件多条用例只传一次文件路径() {
        List<RunSplitter.PlannedCase> input = List.of(
                new RunSplitter.PlannedCase("IM-1", "pytest", "tests/x/test_x.py", 0),
                new RunSplitter.PlannedCase("IM-2", "pytest", "tests/x/test_x.py", 0));
        RunSplitter.SplitRun run = RunSplitter.split(input).get(0);
        assertEquals(List.of("tests/x/test_x.py"), run.suitePaths(),
                "同一个文件传两遍会让 pytest 收集两次用例");
        assertEquals(2, run.caseCount());
    }

    @Test
    void 单内优先级取组内最高() {
        List<RunSplitter.SplitRun> runs = RunSplitter.split(
                List.of(rf("A", 1), rf("B", 5), py("C", 2)));
        assertEquals(5, runs.stream().filter(r -> r.kernel().equals("robot"))
                .findFirst().orElseThrow().priority());
        assertEquals(2, runs.stream().filter(r -> r.kernel().equals("pytest"))
                .findFirst().orElseThrow().priority());
    }

    @Test
    void 组间顺序稳定_两次拆分结果一致() {
        List<RunSplitter.PlannedCase> input = List.of(rf("A", 0), py("B", 0));
        List<String> first = RunSplitter.split(input).stream()
                .map(RunSplitter.SplitRun::kernel).toList();
        List<String> second = RunSplitter.split(input).stream()
                .map(RunSplitter.SplitRun::kernel).toList();
        assertEquals(first, second, "顺序抖动会让同一批用例两次执行的开始时间不同");
    }

    @Test
    void 内核为空按_robot_处理() {
        List<RunSplitter.SplitRun> runs = RunSplitter.split(
                List.of(new RunSplitter.PlannedCase("X", null, "suites/x.robot", 0)));
        assertEquals(1, runs.size());
        assertEquals("robot", runs.get(0).kernel());
    }

    @Test
    void 空输入不产生空单() {
        assertTrue(RunSplitter.split(List.of()).isEmpty());
        assertTrue(RunSplitter.split(null).isEmpty());
    }

    @Test
    void 校验器能识别用例重复() {
        List<RunSplitter.PlannedCase> input = List.of(rf("A", 0));
        // 手工构造一个「同一条用例出现在两单里」的结果
        RunSplitter.SplitRun a = new RunSplitter.SplitRun("robot", "robot",
                List.of(rf("A", 0)), List.of("suites/A.robot"), 0);
        RunSplitter.SplitRun b = new RunSplitter.SplitRun("robot", "robot",
                List.of(rf("A", 0)), List.of("suites/A.robot"), 0);
        assertThrows(RunSplitter.SplitMismatchException.class,
                () -> RunSplitter.verify(input, List.of(a, b)));
    }

    @Test
    void 校验器能识别内核错位() {
        List<RunSplitter.PlannedCase> input = List.of(py("A", 0));
        RunSplitter.SplitRun wrong = new RunSplitter.SplitRun("robot", "robot",
                List.of(py("A", 0)), List.of("tests/A.py"), 0);
        RunSplitter.SplitMismatchException error = assertThrows(
                RunSplitter.SplitMismatchException.class,
                () -> RunSplitter.verify(input, List.of(wrong)));
        assertTrue(error.getMessage().contains("落进了"), error.getMessage());
    }

    @Test
    void 三内核并存也能各成一单() {
        List<RunSplitter.SplitRun> runs = RunSplitter.split(List.of(
                rf("A", 0), py("B", 0),
                new RunSplitter.PlannedCase("C", "junit", "tests/C.java", 0)));
        assertEquals(3, runs.size());
        assertFalse(runs.stream().anyMatch(r -> r.caseCount() == 0));
    }
}
