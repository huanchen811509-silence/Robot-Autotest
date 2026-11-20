package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * junit 判据提取（W57-50 / TC-v5.7-257）。
 *
 * <p>只认 {@code <testcase>} 内的 {@code <system-out>}：testsuite 级的输出是所有用例混在
 * 一起的，硬归属等于编造事实，因此计入 unassigned 不猜。
 */
class PytestCriteriaExtractorTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String xml) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("按用例名取到 system-out，多文件合并")
    void extractPerCaseOutput() throws Exception {
        write("junit-1.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest">
                    <testcase classname="tests.test_net" name="test_bw" time="0.2">
                      <system-out>noise line
                {"criteria":[{"metric":"bw","actual":"95","passed":true}]}
                      </system-out>
                    </testcase>
                    <testcase classname="tests.test_net" name="test_lat[1]" time="0.1"/>
                  </testsuite>
                </testsuites>
                """);
        write("junit-2.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuite name="pytest">
                  <testcase name="test_other" time="0.1">
                    <system-out>{"criteria":[{"metric":"x","actual":"1","passed":false}]}</system-out>
                  </testcase>
                </testsuite>
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.files()).isEqualTo(2);
        assertThat(result.byCaseName()).containsKeys("test_bw", "test_other");
        assertThat(result.byCaseName().get("test_bw")).contains("\"bw\"");
        // 没有 system-out 的用例不产生条目：不能凭空生成空字符串。
        assertThat(result.byCaseName()).doesNotContainKey("test_lat[1]");
    }

    @Test
    @DisplayName("system-err 里的 [明细] 也能提取（logging 默认写 stderr）")
    void extractsFromSystemErr() throws Exception {
        write("junit.xml", """
                <testsuite name="pytest">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020_pci_device_info">
                    <system-out>--------------------------------- Captured Out ---------------------------------

                </system-out>
                    <system-err>2026-11-19 INFO [root] [明细] 步骤1：Plan-only 无真实回显</system-err>
                  </testcase>
                </testsuite>
                """);
        PytestCriteriaExtractor.Extraction r = PytestCriteriaExtractor.scan(tempDir);
        assertThat(r.byCaseName()).containsKey("test_im_fun_020_pci_device_info");
        assertThat(r.byCaseName().get("test_im_fun_020_pci_device_info")).contains("[明细]");
    }

    @Test
    @DisplayName("[判定] 行转结构化判据；Plan-only 无判定时返回 null 不伪造")
    void structuredFromVerdictLines() {
        String text = """
                2026-11-19 INFO [root] [明细] 步骤1：读取 PCI 地址
                [判定] 步骤1：通过
                [判定] 步骤2：不通过（速率 25GT/s 低于基线 32GT/s）
                """;
        String json = PytestCriteriaExtractor.toStructured(text);
        assertThat(json).contains("\"criteria\"")
                .contains("\"metric\":\"步骤1\"").contains("\"passed\":true")
                .contains("\"passed\":false");
        // 只有 [明细]、没有 [判定]：未声明判据，不得伪造通过
        assertThat(PytestCriteriaExtractor.toStructured(
                "[明细] 步骤1：Plan-only 无真实回显，本步骤取值打印与判定跳过")).isNull();
    }

    @Test
    @DisplayName("[判定] 合成的结构化判据能被 CriteriaEvaluation 判 PASS")
    void structuredVerdictFeedsEvaluationPass() {
        String json = PytestCriteriaExtractor.toStructured(
                "[判定] 步骤1：通过\n[判定] 步骤2：通过");
        var result = com.qa.autotest.application.execution.CriteriaEvaluation.evaluate(json);
        assertThat(result.json()).isNotNull();
        assertThat(result.verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("[判定] 含不通过 → CriteriaEvaluation 判 FAIL")
    void structuredVerdictFeedsEvaluationFail() {
        String json = PytestCriteriaExtractor.toStructured(
                "[判定] 步骤1：通过\n[判定] 步骤2：不通过（速率 25GT/s 低于基线）");
        var result = com.qa.autotest.application.execution.CriteriaEvaluation.evaluate(json);
        assertThat(result.verdict()).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("Plan-only 无判定 → 判据为空，评测结果未声明（诚实，不伪造 PASS）")
    void planOnlyHasNoStructuredCriteria() {
        String json = PytestCriteriaExtractor.toStructured(
                "[明细] 步骤1：Plan-only 无真实回显，本步骤取值打印与判定跳过");
        assertThat(json).isNull();
        assertThat(com.qa.autotest.application.execution.CriteriaEvaluation.evaluate(json)
                .verdict()).isNull();
    }

    @Test
    @DisplayName("CRITERIA_JSON 哨兵原样取出，不做二次加工")
    void structuredFromSentinel() {
        String text = "[明细] x\nCRITERIA_JSON "
                + "{\"criteria\":[{\"metric\":\"m\",\"actual\":\"1\",\"passed\":true}]}";
        assertThat(PytestCriteriaExtractor.toStructured(text))
                .isEqualTo("{\"criteria\":[{\"metric\":\"m\",\"actual\":\"1\",\"passed\":true}]}");
    }

    @Test
    @DisplayName("testsuite 级 system-out 不归属给任何用例")
    void suiteLevelOutputIsNotAssigned() throws Exception {
        write("junit.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuite name="pytest">
                  <testcase name="test_a" time="0.1"/>
                  <system-out>summary of everything</system-out>
                </testsuite>
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.byCaseName()).isEmpty();
        assertThat(result.unassigned()).isEqualTo(1);
    }

    @Test
    @DisplayName("坏文件只跳过自己，不影响其它文件")
    void brokenFileDoesNotBreakOthers() throws Exception {
        write("bad.xml", "<testsuite><testcase name=\"x\">");
        write("good.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuite name="pytest">
                  <testcase name="test_ok" time="0.1">
                    <system-out>{"criteria":[]}</system-out>
                  </testcase>
                </testsuite>
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.byCaseName()).containsKey("test_ok");
        assertThat(result.files()).isEqualTo(1);
    }

    @Test
    @DisplayName("目录不存在返回空结果，不抛异常")
    void missingDirectoryIsSafe() {
        PytestCriteriaExtractor.Extraction result =
                PytestCriteriaExtractor.scan(tempDir.resolve("nope"));
        assertThat(result.byCaseName()).isEmpty();
        assertThat(result.files()).isZero();
    }

    // ---- v5.8.0 / W58-13：json-report 主通道 ----

    private void writeJson(String json) throws Exception {
        Files.writeString(tempDir.resolve(PytestCriteriaExtractor.JSON_REPORT), json,
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("json-report：按 nodeid 取到 call.stdout 里的判据原文")
    void jsonReportOutputIsExtracted() throws Exception {
        writeJson("""
                {"tests":[
                  {"nodeid":"tests/nic/test_net.py::test_bw",
                   "call":{"stdout":"CRITERIA_JSON {\\"criteria\\":[{\\"metric\\":\\"bw\\",\\"actual\\":\\"95\\",\\"passed\\":true}]}\\n"}},
                  {"nodeid":"tests/nic/test_net.py::test_silent","call":{"stdout":""}}
                ]}
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.jsonFiles()).isEqualTo(1);
        assertThat(result.byNodeId()).containsOnlyKeys("tests/nic/test_net.py::test_bw");
        assertThat(result.byNodeId().get("tests/nic/test_net.py::test_bw")).contains("\"bw\"");
        // junit 侧一个文件都没有：这条用例的判据只能来自 json-report。
        assertThat(result.files()).isZero();
        assertThat(result.byCaseName()).isEmpty();
    }

    @Test
    @DisplayName("json-report：setup 与 call 的 stdout 按顺序拼接")
    void jsonReportStagesAreMerged() throws Exception {
        writeJson("""
                {"tests":[
                  {"nodeid":"tests/test_x.py::test_a",
                   "setup":{"stdout":"fixture says hello"},
                   "call":{"stdout":"{\\"criteria\\":[]}"},
                   "teardown":{"stdout":"  "}}
                ]}
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        String text = result.byNodeId().get("tests/test_x.py::test_a");
        assertThat(text).startsWith("fixture says hello").contains("criteria");
        // teardown 是空白，不该多出空行/空段。
        assertThat(text).doesNotEndWith("\n");
        assertThat(text.split("\n")).hasSize(2);
    }

    @Test
    @DisplayName("json-report 坏掉时退回 junit，不抛异常")
    void brokenJsonFallsBackToJunit() throws Exception {
        writeJson("{\"tests\": [not-json");
        write("junit.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuite name="pytest">
                  <testcase name="test_ok" time="0.1">
                    <system-out>{"criteria":[]}</system-out>
                  </testcase>
                </testsuite>
                """);

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.jsonFiles()).isZero();
        // v5.24.0：junit 现在也产 classname::name 索引；该用例无 classname，key 退化为 name。
        // 坏 json-report 不能再让 byNodeId 全空（那会让判据匹配只能靠脆弱的 fallback）。
        assertThat(result.byNodeId()).containsKey("test_ok");
        assertThat(result.byCaseName()).containsKey("test_ok");
        assertThat(result.files()).isEqualTo(1);
    }

    @Test
    @DisplayName("json-report 没有 tests 数组：视作无输出，不抛异常")
    void jsonReportWithoutTestsIsEmpty() throws Exception {
        writeJson("{\"summary\":{\"passed\":3}}");

        PytestCriteriaExtractor.Extraction result = PytestCriteriaExtractor.scan(tempDir);
        assertThat(result.byNodeId()).isEmpty();
        assertThat(result.jsonFiles()).isZero();
    }
}
