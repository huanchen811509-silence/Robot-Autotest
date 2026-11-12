package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 步骤证据抽取（W51-12 / FR-v5.1-08）。
 *
 * <p>样例全部照抄框架 {@code tests/conftest.py} 的 {@code StepReporter.check} 与用例里的
 * 真实写法：带标签的 {@code [明细] 步骤1：取值}、不带标签的 {@code [明细] PCI BDF=%s}、
 * 判定三种形态、以及 Plan-only 的跳过话术。
 */
class PytestStepExtractorTest {

    @Test
    void 带标签的明细与三种判定都能认出来() {
        String captured = """
                2026-11-12 21:00:01 INFO     [root] [明细] 读取链路状态：LnkSta=8GT/s x8
                2026-11-12 21:00:01 INFO     [root] [判定] 读取链路状态：通过
                2026-11-12 21:00:02 INFO     [root] [判定] 校验速率：不通过（期望 16GT/s，实测 8GT/s；宽度不足）
                2026-11-12 21:00:02 INFO     [root] [判定] 准入检查：必需字段 3 项均有有效回显（bdf、vendor、driver）
                """.stripIndent();

        List<PytestStepExtractor.Step> steps = PytestStepExtractor.fromCaptured(captured);

        assertThat(steps).hasSize(4);
        assertThat(steps.get(0).kind()).isEqualTo(PytestStepExtractor.KIND_DETAIL);
        assertThat(steps.get(0).label()).isEqualTo("读取链路状态");
        assertThat(steps.get(0).content()).isEqualTo("LnkSta=8GT/s x8");
        assertThat(steps.get(0).status()).isNull();

        assertThat(steps.get(1).kind()).isEqualTo(PytestStepExtractor.KIND_VERDICT);
        assertThat(steps.get(1).status()).isEqualTo(PytestStepExtractor.STATUS_PASS);
        assertThat(steps.get(1).label()).isEqualTo("读取链路状态");

        assertThat(steps.get(2).status()).isEqualTo(PytestStepExtractor.STATUS_FAIL);
        assertThat(steps.get(2).content()).isEqualTo("期望 16GT/s，实测 8GT/s；宽度不足");

        assertThat(steps.get(3).status()).isEqualTo(PytestStepExtractor.STATUS_PASS);
        assertThat(steps.get(3).label()).isEqualTo("准入检查");
        assertThat(steps.get(3).seq()).isEqualTo(4);
    }

    @Test
    void 没有标签的明细整行当取值而不是把前半截切成假标签() {
        String captured = "[明细] PCI BDF=0000:31:00.0\n"
                + "[明细] Vendor/Device ID=1af4:1000（规格基线 1af4:1000）\n";

        List<PytestStepExtractor.Step> steps = PytestStepExtractor.fromCaptured(captured);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).label()).isEmpty();
        assertThat(steps.get(0).content()).isEqualTo("PCI BDF=0000:31:00.0");
        assertThat(steps.get(1).label()).isEmpty();
        assertThat(steps.get(1).content()).startsWith("Vendor/Device ID=1af4:1000");
    }

    @Test
    void Plan_only记SKIP不记PASS() {
        String captured = "[明细] 读取温度：Plan-only 无真实回显，本步骤取值打印与判定跳过\n";

        List<PytestStepExtractor.Step> steps = PytestStepExtractor.fromCaptured(captured);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).status()).isEqualTo(PytestStepExtractor.STATUS_SKIP);
        assertThat(steps.get(0).label()).isEqualTo("读取温度");
    }

    @Test
    void 非标记行一律忽略且行号仍指向原文() {
        String captured = """
                采集开始
                [明细] 步骤一：值a

                连接建立
                [明细] 步骤二：值b
                """.stripIndent();

        List<PytestStepExtractor.Step> steps = PytestStepExtractor.fromCaptured(captured);

        assertThat(steps).hasSize(2);
        // 捕获文本第 2 行与第 5 行（1 起）。
        assertThat(steps.get(0).logLine()).isEqualTo(2);
        assertThat(steps.get(1).logLine()).isEqualTo(5);
    }

    @Test
    void 空白与空文本返回空表不抛() {
        assertThat(PytestStepExtractor.fromCaptured(null)).isEmpty();
        assertThat(PytestStepExtractor.fromCaptured("   \n\n")).isEmpty();
        assertThat(PytestStepExtractor.fromCaptured("[明细]   ")).isEmpty();
    }

    @Test
    void junit里system_err的日志也要读到_框架默认写stderr(@TempDir Path dir) throws IOException {
        // 这条是最容易踩的坑：logging.StreamHandler() 默认写 stderr，而 pytest 在 xunit2 下
        // 把 capstderr 放进 <system-err>。只读 <system-out> 会一条明细都拿不到。
        Path junit = dir.resolve("junit.xml");
        Files.writeString(junit, """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="1">
                    <testcase classname="tests.nic.base.test_im_fun_020" name="test_im_fun_020" time="1.2">
                      <system-out>nothing here</system-out>
                      <system-err>[明细] 读取链路状态：LnkSta=8GT/s x8
                [判定] 读取链路状态：通过</system-err>
                    </testcase>
                  </testsuite>
                </testsuites>
                """, StandardCharsets.UTF_8);

        List<PytestStepExtractor.CaseSteps> cases =
                new PytestStepExtractor().fromJunit(List.of(junit));

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).captured()).isTrue();
        assertThat(cases.get(0).matchKey()).isEqualTo("test_im_fun_020::test_im_fun_020");
        assertThat(cases.get(0).steps()).hasSize(2);
        assertThat(cases.get(0).failCount()).isZero();
    }

    @Test
    void 没有捕获文本时如实报captured为false而不是编空树(@TempDir Path dir) throws IOException {
        Path junit = dir.resolve("junit-0.xml");
        Files.writeString(junit, """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="1">
                    <testcase classname="tests.nic.base.test_im_fun_020" name="test_im_fun_020[param]" time="0.9"/>
                  </testsuite>
                </testsuites>
                """, StandardCharsets.UTF_8);

        List<PytestStepExtractor.CaseSteps> cases =
                new PytestStepExtractor().fromJunit(List.of(junit));

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).captured()).isFalse();
        assertThat(cases.get(0).steps()).isEmpty();
        // 参数化实例剥掉 [param]，与绑定表的基础 nodeid 对齐。
        assertThat(cases.get(0).matchKey()).isEqualTo("test_im_fun_020::test_im_fun_020");
    }

    @Test
    void 同一用例两个阶段的相同捕获块只算一次(@TempDir Path dir) throws IOException {
        // 实测形态：一个在 call 阶段被 skip 的用例，pytest 会为 setup 与 call 各写一份
        // <system-err>，内容逐字相同。不去重就是「3 条明细变 6 条」。
        String block = """
                --------------------------------- Captured Err ---------------------------------
                [明细] 步骤1：Plan-only 无真实回显，本步骤取值打印与判定跳过
                [明细] 步骤2：Plan-only 无真实回显，本步骤取值打印与判定跳过""";
        Path junit = dir.resolve("junit.xml");
        Files.writeString(junit, """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="1" skipped="1">
                    <testcase classname="tests.nic.base.test_im_fun_020" name="test_im_fun_020" time="0.02">
                      <system-out>--------------------------------- Captured Out ---------------------------------
                </system-out>
                      <system-err>%s</system-err>
                      <system-out>--------------------------------- Captured Out ---------------------------------
                </system-out>
                      <system-err>%s</system-err>
                      <skipped message="Plan-only"/>
                    </testcase>
                  </testsuite>
                </testsuites>
                """.formatted(block, block), StandardCharsets.UTF_8);

        List<PytestStepExtractor.CaseSteps> cases =
                new PytestStepExtractor().fromJunit(List.of(junit));

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).steps()).hasSize(2);
        assertThat(cases.get(0).steps().get(0).status()).isEqualTo(PytestStepExtractor.STATUS_SKIP);
    }

    @Test
    void 断言失败也要成为一步否则看不出为什么红(@TempDir Path dir) throws IOException {
        Path junit = dir.resolve("junit.xml");
        Files.writeString(junit, """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="1">
                    <testcase classname="tests.nic.ethernet.test_im_net_131" name="test_im_net_131" time="2.0">
                      <system-err>[判定] 校验速率：不通过（实测 8GT/s）</system-err>
                      <failure message="校验速率：实测 8GT/s">traceback...</failure>
                    </testcase>
                  </testsuite>
                </testsuites>
                """, StandardCharsets.UTF_8);

        List<PytestStepExtractor.CaseSteps> cases =
                new PytestStepExtractor().fromJunit(List.of(junit));

        assertThat(cases.get(0).steps()).hasSize(2);
        assertThat(cases.get(0).steps().get(1).kind()).isEqualTo(PytestStepExtractor.KIND_FAILURE);
        assertThat(cases.get(0).steps().get(1).content()).isEqualTo("校验速率：实测 8GT/s");
        assertThat(cases.get(0).failCount()).isEqualTo(2);
    }

    @Test
    void 不存在的junit文件跳过而不是整轮失败(@TempDir Path dir) {
        assertThat(new PytestStepExtractor().fromJunit(List.of(dir.resolve("nope.xml")))).isEmpty();
    }

    @Test
    void 命令证据读jsonl且stdout只留相对路径(@TempDir Path dir) throws IOException {
        Path raw = Files.createDirectories(dir.resolve("cases/x/raw"));
        Files.createDirectories(raw.resolve("commands"));
        Files.writeString(raw.resolve("gpu01_10.202.54.101.ens17np0.commands.jsonl"),
                "{\"command_id\":\"c1\",\"command\":\"ethtool ens17np0\",\"purpose\":\"读取链路\","
                        + "\"stdout_file\":\"c1.stdout.txt\",\"stderr_file\":\"c1.stderr.txt\"}\n",
                StandardCharsets.UTF_8);

        List<PytestStepExtractor.Step> steps = new PytestStepExtractor().commandsOf(raw, 11);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).seq()).isEqualTo(11);
        assertThat(steps.get(0).kind()).isEqualTo(PytestStepExtractor.KIND_COMMAND);
        assertThat(steps.get(0).label()).isEqualTo("c1");
        assertThat(steps.get(0).content()).isEqualTo("读取链路 :: ethtool ens17np0");
        assertThat(steps.get(0).attachment()).isEqualTo("commands/c1.stdout.txt");
    }

    @Test
    void 目录不存在时命令证据返回空表(@TempDir Path dir) {
        assertThat(new PytestStepExtractor().commandsOf(dir.resolve("none"), 1)).isEmpty();
    }

    @Test
    void nodeid目录名复刻框架的sanitize规则() {
        // Python 侧是 re.sub(r"[^A-Za-z0-9_.-]+", "_", nodeid)：连续的非法字符压成一个下划线，
        // 所以 `::` 变成单个 `_` 而不是两个。
        assertThat(PytestEvidenceService.sanitize(
                "tests/nic/base/test_im_fun_020.py::test_im_fun_020"))
                .isEqualTo("tests_nic_base_test_im_fun_020.py_test_im_fun_020");
    }
}
