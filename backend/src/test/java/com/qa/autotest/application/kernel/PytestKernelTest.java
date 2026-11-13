package com.qa.autotest.application.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.KernelRequest;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PytestKernelTest {

    private final PytestKernel kernel = new PytestKernel(new ObjectMapper(),
            new com.qa.autotest.application.execution.ExecutionProperties());

    @Test
    void jsonReportMapsAllSixStatuses(@TempDir Path dir) throws Exception {
        String json = """
                {"tests":[
                  {"nodeid":"t.py::test_p[1]","outcome":"passed","duration":0.1},
                  {"nodeid":"t.py::test_p[2]","outcome":"passed","duration":0.2},
                  {"nodeid":"t.py::test_f","outcome":"failed","duration":0.3},
                  {"nodeid":"t.py::test_s","outcome":"skipped"},
                  {"nodeid":"t.py::test_x","outcome":"xfailed"},
                  {"nodeid":"t.py::test_xp","outcome":"xpassed"},
                  {"nodeid":"t.py::test_e","outcome":"error"}
                ]}""";
        Files.writeString(dir.resolve("pytest-report.json"), json);
        NormalizedRun run = kernel.normalize(dir, "R1");
        assertEquals(7, run.total());
        assertEquals(2, run.counts().get("PASS"));
        assertEquals(1, run.counts().get("XFAIL"));
        assertEquals(1, run.counts().get("ERROR"));
        assertEquals(3, run.failures());
        assertTrue(run.cases().get(0).nodeId().contains("[1]"));
    }

    @Test
    void junitFallbackDistinguishesFailureErrorAndSkipped(@TempDir Path dir) throws Exception {
        String xml = """
                <testsuite name="s" tests="4">
                  <testcase classname="t" name="ok" time="0.1"/>
                  <testcase classname="t" name="bad" time="0.2"><failure message="boom"/></testcase>
                  <testcase classname="t" name="err"><error message="setup"/></testcase>
                  <testcase classname="t" name="skip"><skipped/></testcase>
                </testsuite>""";
        Files.writeString(dir.resolve("junit.xml"), xml);
        NormalizedRun run = kernel.normalize(dir, "R2");
        assertEquals(4, run.total());
        assertEquals(CaseStatus.PASS, run.cases().get(0).status());
        assertEquals(CaseStatus.FAIL, run.cases().get(1).status());
        assertEquals(CaseStatus.ERROR, run.cases().get(2).status());
        assertEquals(CaseStatus.SKIP, run.cases().get(3).status());
    }

    @Test
    void junitFailureMessageKeepsReasonInsteadOfStatusName(@TempDir Path dir) throws Exception {
        String xml = """
                <testsuite name="s" tests="2">
                  <testcase classname="t" name="bad" time="0.2">
                    <failure message="AssertionError: PFC 使能优先级不符">traceback here</failure>
                  </testcase>
                  <testcase classname="t" name="err"><error>KeyError: '资源定位不唯一'</error></testcase>
                </testsuite>""";
        Files.writeString(dir.resolve("junit.xml"), xml);
        NormalizedRun run = kernel.normalize(dir, "R5");
        assertEquals("AssertionError: PFC 使能优先级不符", run.cases().get(0).message(),
                "失败消息必须是 junit 的 message 属性，不能退化成状态名 FAIL");
        assertEquals("KeyError: '资源定位不唯一'", run.cases().get(1).message(),
                "缺 message 属性时应取正文首个非空行");
    }

    @Test
    void missingArtifactsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> kernel.normalize(dir, "R3"));
    }

    @Test
    void commandUsesBothReportChannels() {
        KernelRequest request = new KernelRequest(1L, "R4", 2, 4,
                List.of("tests/"), List.of("tests/test_a.py::test_ok"), null, "preview");
        List<String> argv = kernel.command(request, Path.of("artifacts"));
        assertTrue(argv.get(0).endsWith("pytest"), "argv[0] 应指向 venv pytest: " + argv.get(0));
        assertTrue(argv.stream().anyMatch(a -> a.startsWith("--json-report-file=")));
        assertTrue(argv.stream().anyMatch(a -> a.startsWith("--junitxml=")));
        assertTrue(argv.contains("tests/test_a.py::test_ok"));
    }

    @Test
    void commandUsesXdistOnlyWhenParallel() {
        KernelRequest serial = new KernelRequest(1L, "R5", 0, 1,
                List.of("tests/"), List.of(), null, "serial");
        assertTrue(!kernel.command(serial, Path.of("a")).contains("-n"), "串行不得带 -n");
        KernelRequest parallel = new KernelRequest(1L, "R6", 0, 4,
                List.of("tests/"), List.of(), null, "parallel");
        List<String> argv = kernel.command(parallel, Path.of("a"));
        int at = argv.indexOf("-n");
        assertTrue(at > 0 && "4".equals(argv.get(at + 1)), "并行应带 -n 4: " + argv);
    }

    // ---- N3：pytest metadata 落 metadata 字段并过 MetadataGate ----

    @Test
    void jsonReportMetadataPassesGate(@TempDir Path dir) throws Exception {
        String json = """
                {"metadata":{
                  "case_id":"PY-B2-001","qa_project":"robot-autotest","dut":"GPU-01",
                  "link":"GPU-01 eth0 -> SW-01","sw_build":"Ubuntu 24.04 / pytest 8.2",
                  "baseline":"< 5 min","operator":"大师","started_at":"2026-11-07T02:00:00Z",
                  "case_kind":"pytest-regression","Python":"3.10.12","Platform":"linux"
                },"tests":[{"nodeid":"t.py::test_ok","outcome":"passed"}]}""";
        Files.writeString(dir.resolve("pytest-report.json"), json);
        NormalizedRun run = kernel.normalize(dir, "RUN-PY-1");
        assertTrue(run.exportable(), "十项齐全应可导出: " + run.metadataGate());
        assertEquals("PY-B2-001", run.metadata().get("CASE_ID"));
        assertEquals("RUN-PY-1", run.metadata().get("RUN_ID"));
        assertEquals(10, run.metadata().size(), "非门禁键应被丢弃: " + run.metadata());
    }

    @Test
    void jsonReportMissingDutIsNotExportable(@TempDir Path dir) throws Exception {
        String json = """
                {"metadata":{"case_id":"PY-B2-002","qa_project":"robot-autotest"},
                 "tests":[{"nodeid":"t.py::test_ok","outcome":"passed"}]}""";
        Files.writeString(dir.resolve("pytest-report.json"), json);
        NormalizedRun run = kernel.normalize(dir, "RUN-PY-2");
        assertTrue(!run.exportable());
        assertTrue(run.metadataGate().missing().contains("DUT"));
        assertEquals("RUN-PY-2", run.metadata().get("RUN_ID"), "RUN_ID 由内核补齐");
    }

    @Test
    void junitPropertiesCarryMetadata(@TempDir Path dir) throws Exception {
        String xml = """
                <testsuite name="s" tests="1">
                  <properties>
                    <property name="case_id" value="PY-B2-003"/>
                    <property name="qa_project" value="robot-autotest"/>
                  </properties>
                  <testcase classname="t" name="ok" time="0.1"/>
                </testsuite>""";
        Files.writeString(dir.resolve("junit.xml"), xml);
        NormalizedRun run = kernel.normalize(dir, "RUN-PY-3");
        assertEquals("PY-B2-003", run.metadata().get("CASE_ID"));
        assertTrue(!run.exportable());
    }

    /**
     * FR-v3.9-02：命令从 **stage** 里的 stdout 取，不是用例顶层。
     *
     * json-report 把输出放在 setup/call/teardown 三个 stage 对象里，只读顶层会得到空串；
     * 这条用例把命令刻意放在 call 段，就是钉住「必须递归到 stage」这一点。
     */
    @Test
    void commandExtractedFromStageStdout(@TempDir Path dir) throws Exception {
        String json = """
                {"tests":[
                  {"nodeid":"t.py::test_cmd","outcome":"passed","duration":0.1,
                   "call":{"stdout":"$ iperf3 -c 192.0.2.10 -t 5\\nexit=0\\n","stderr":""}},
                  {"nodeid":"t.py::test_plain","outcome":"passed","duration":0.1,
                   "call":{"stdout":"no command here\\n"}}
                ]}""";
        Files.writeString(dir.resolve("pytest-report.json"), json);
        NormalizedRun run = kernel.normalize(dir, "RUN-PY-CMD");

        assertEquals("iperf3 -c 192.0.2.10 -t 5", run.cases().get(0).commandText());
        assertEquals("STDOUT_EXTRACT", run.cases().get(0).commandSource());
        assertEquals(0, run.cases().get(0).commandExitCode(), "exit=0 是有效退出码，不是未知");
        assertEquals("NONE", run.cases().get(1).commandText(), "无命令必须显式 NONE");
        assertEquals("NONE", run.cases().get(1).commandSource());
        assertNull(run.cases().get(1).commandExitCode());
    }

    /**
     * FR-v5.6-24：报告入库是**重新解析产物**的独立链路。这里不认复测产物，
     * 就会出现「执行页通过（含抖动）、报告页失败」的撕裂。
     */
    @Test
    void retryJunitIsMergedIntoFlakyResult(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("junit.xml"), """
                <testsuites><testsuite name="pytest" tests="2">
                  <testcase classname="tests.tar" name="test_a" time="1.0">
                    <failure message="PFC 使能优先级不对">trace-a</failure>
                  </testcase>
                  <testcase classname="tests.tar" name="test_b" time="0.5"/>
                </testsuite></testsuites>
                """);
        Files.writeString(dir.resolve("junit-retry-1.xml"), """
                <testsuites><testsuite name="pytest" tests="1">
                  <testcase classname="tests.tar" name="test_a" time="0.9"/>
                </testsuite></testsuites>
                """);

        NormalizedRun run = kernel.normalize(dir, "RUN-RETRY-1");

        assertEquals(2, run.total(), "复测不能把用例数翻倍");
        assertEquals(CaseStatus.PASS, run.cases().get(0).status());
        assertTrue(run.cases().get(0).flaky(), "首轮失败复测通过必须标抖动");
        assertEquals(1, run.cases().get(0).retryCount());
        assertEquals(CaseStatus.PASS, run.cases().get(1).status());
        assertTrue(!run.cases().get(1).flaky(), "首轮就通过的不是抖动");
        assertEquals(0, run.failures(), "抖动不计入对外失败数");
    }

    @Test
    void retryFileNameRoundParsing() {
        assertEquals(1, PytestKernel.retryRoundOf("junit-retry-1.xml"));
        assertEquals(2, PytestKernel.retryRoundOf("junit-retry-2-0.xml"));
        assertEquals(-1, PytestKernel.retryRoundOf("junit.xml"));
        assertEquals(-1, PytestKernel.retryRoundOf("junit-retry-x.xml"));
    }
}
