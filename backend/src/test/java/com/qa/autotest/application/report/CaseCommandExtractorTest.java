package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.domain.report.KeywordNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 用例级命令提取（FR-v3.9-02 / ADR-39-04）。
 *
 * <p>这一层的错误代价是**双向**的：漏识别 → 报告页显示「未下发」，评审以为没跑命令；
 * 误识别 → 把断言消息里的字串当成命令，报告变成假证据。所以两类用例都要有：
 * 该认的必须认出来（正向），不该认的必须留 {@code NONE}（负向）。
 */
class CaseCommandExtractorTest {

    private static KeywordNode keyword(String name, String... messages) {
        return new KeywordNode(name, "PASS", 10L, List.of(messages), List.of());
    }

    @Nested
    @DisplayName("RF 关键字取材")
    class RobotFramework {

        @Test
        @DisplayName("白名单关键字 + `$ 命令` 消息 → 提取原文并标 RF_KEYWORD")
        void extractsFromWhitelistedKeyword() {
            List<KeywordNode> keywords = List.of(
                    keyword("Setup", "初始化完毕"),
                    keyword("Execute Command", "$ ipmitool raw 0x30 0x93 0xdb 0x07 0x00 0x00"));

            CaseCommandExtractor.Extracted out = CaseCommandExtractor.fromRobotKeywords(keywords);

            assertTrue(out.hasCommand());
            assertEquals("ipmitool raw 0x30 0x93 0xdb 0x07 0x00 0x00", out.text());
            assertEquals(CaseCommandExtractor.SOURCE_RF_KEYWORD, out.source());
        }

        @Test
        @DisplayName("嵌套关键字里的命令也能拿到（递归，不是只看顶层）")
        void extractsFromNestedKeyword() {
            KeywordNode inner = keyword("ssh 执行命令", "命令: cat /proc/net/dev");
            KeywordNode outer = new KeywordNode("Run Smoke", "PASS", 80L, List.of(), List.of(inner));

            CaseCommandExtractor.Extracted out =
                    CaseCommandExtractor.fromRobotKeywords(List.of(outer));

            assertEquals("cat /proc/net/dev", out.text());
            assertEquals(CaseCommandExtractor.SOURCE_RF_KEYWORD, out.source());
        }

        @Test
        @DisplayName("命令写在子关键字 Log 的消息里也能取到（真实套件的常见写法）")
        void extractsFromNestedLogUnderCommandKeyword() {
            // 现场写法：包装关键字里先 Log 命令、再 Run Process、再 Log 退出码。
            // RF 会把这两个 Log 记成**子关键字**，命令原文不在父关键字的消息上。
            KeywordNode logCommand = new KeywordNode("Log", "PASS", 0L,
                    List.of("$ uname -r"), List.of());
            KeywordNode logExit = new KeywordNode("Log", "PASS", 0L,
                    List.of("exit=0"), List.of());
            KeywordNode wrapper = new KeywordNode("执行命令并记录原始输出", "PASS", 20L,
                    List.of(), List.of(logCommand, logExit));

            CaseCommandExtractor.Extracted out =
                    CaseCommandExtractor.fromRobotKeywords(List.of(wrapper));

            assertTrue(out.hasCommand(), "子关键字里的命令必须能取到，否则 RF 侧覆盖率会掉到 0");
            assertEquals("uname -r", out.text());
            assertEquals(CaseCommandExtractor.SOURCE_RF_KEYWORD, out.source());
            assertEquals(0, out.exitCode());
        }

        @Test
        @DisplayName("关键字名带参数（Execute Command  ssh ...）仍算白名单")
        void keywordNameWithArguments() {
            assertTrue(CaseCommandExtractor.isCommandKeyword("Execute Command    ssh root@dut"));
            assertTrue(CaseCommandExtractor.isCommandKeyword("  RUN PROCESS "));
        }

        @Test
        @DisplayName("不在白名单的关键字一律不认——避免从断言消息里盲扫出假命令")
        void ignoresNonWhitelistedKeyword() {
            List<KeywordNode> keywords = List.of(
                    keyword("Should Contain", "$ rm -rf /tmp/should-not-be-a-command"));

            CaseCommandExtractor.Extracted out = CaseCommandExtractor.fromRobotKeywords(keywords);

            assertFalse(out.hasCommand());
            assertEquals(CaseCommandExtractor.NONE, out.text());
            assertEquals(CaseCommandExtractor.SOURCE_NONE, out.source());
        }

        @Test
        @DisplayName("白名单关键字但消息里没有可识别命令行 → NONE（不硬凑）")
        void whitelistedButNoCommandLine() {
            List<KeywordNode> keywords = List.of(keyword("Run Process", "时间 2026-11-09 10:00:00"));

            CaseCommandExtractor.Extracted out = CaseCommandExtractor.fromRobotKeywords(keywords);

            assertFalse(out.hasCommand());
            assertEquals(CaseCommandExtractor.NONE, out.source());
        }

        @Test
        @DisplayName("空关键字树 → NONE，不抛异常")
        void emptyKeywords() {
            assertEquals(CaseCommandExtractor.NONE,
                    CaseCommandExtractor.fromRobotKeywords(List.of()).text());
            assertEquals(CaseCommandExtractor.NONE,
                    CaseCommandExtractor.fromRobotKeywords(null).text());
        }

        @Test
        @DisplayName("退出码：命令同一行的 exit=1 优先，取不到再在同关键字消息里找")
        void exitCode() {
            List<KeywordNode> sameLine = List.of(
                    keyword("Execute Command", "$ ethtool -S eth0 exit=0"));
            assertEquals(0, CaseCommandExtractor.fromRobotKeywords(sameLine).exitCode());

            List<KeywordNode> otherLine = List.of(
                    keyword("Execute Command", "$ ethtool -S eth0", "退出码: 2"));
            assertEquals(2, CaseCommandExtractor.fromRobotKeywords(otherLine).exitCode());
        }

        @Test
        @DisplayName("没有退出码时是 null 而不是 0——0 是「命令成功」的有效取值")
        void exitCodeUnknownIsNull() {
            List<KeywordNode> keywords = List.of(keyword("Execute Command", "$ ls /tmp"));
            assertNull(CaseCommandExtractor.fromRobotKeywords(keywords).exitCode());
        }
    }

    @Nested
    @DisplayName("pytest 取材")
    class Pytest {

        @Test
        @DisplayName("stdout 的 `$ 命令` 行 → STDOUT_EXTRACT")
        void extractsFromStdout() {
            String stdout = String.join("\n",
                    "case setup start",
                    "$ iperf3 -c 192.0.2.10 -t 5",
                    "exit=0",
                    "teardown done");

            CaseCommandExtractor.Extracted out = CaseCommandExtractor.fromPytestStdout(stdout);

            assertTrue(out.hasCommand());
            assertEquals("iperf3 -c 192.0.2.10 -t 5", out.text());
            assertEquals(CaseCommandExtractor.SOURCE_STDOUT, out.source());
            assertEquals(0, out.exitCode());
        }

        @Test
        @DisplayName("stdout 里没有约定行 → NONE（pytest 侧不猜）")
        void noConventionLine() {
            CaseCommandExtractor.Extracted out =
                    CaseCommandExtractor.fromPytestStdout("assert 190 == 120");
            assertFalse(out.hasCommand());
            assertEquals(CaseCommandExtractor.NONE, out.text());
        }

        @Test
        @DisplayName("显式标记 @pytest.mark.commandline → PYTEST_MARK 且不带退出码")
        void explicitMark() {
            CaseCommandExtractor.Extracted out =
                    CaseCommandExtractor.fromPytestMark("ping -c 3 192.0.2.10");
            assertEquals("ping -c 3 192.0.2.10", out.text());
            assertEquals(CaseCommandExtractor.SOURCE_PYTEST_MARK, out.source());
            assertNull(out.exitCode());
        }

        @Test
        @DisplayName("空 stdout / 空标记 → NONE，不抛异常")
        void blankInputs() {
            assertEquals(CaseCommandExtractor.NONE, CaseCommandExtractor.fromPytestStdout("").text());
            assertEquals(CaseCommandExtractor.NONE, CaseCommandExtractor.fromPytestStdout(null).text());
            assertEquals(CaseCommandExtractor.NONE, CaseCommandExtractor.fromPytestMark(" ").text());
        }
    }

    @Nested
    @DisplayName("脱敏与截断")
    class Sanitize {

        @Test
        @DisplayName("命令里的口令必须被抹掉（与日志同一条 scrub 规则）")
        void scrubsCredentials() {
            // 规则来自 EvidenceLogWriter.SECRET：`password=` / `token:` 这类键值片段。
            // 注意 `-u user:pass` 这种位置式写法不在这条规则内（遗留 L-39-03，另议）。
            String command = "curl --noproxy '*' --password=Passw0rd! http://192.0.2.10/api";
            String text = CaseCommandExtractor.fromPytestMark(command).text();
            assertFalse(text.contains("Passw0rd!"), "口令泄漏进库: " + text);
            assertTrue(text.contains("<redacted>"));
            assertTrue(text.contains("curl"));
        }

        @Test
        @DisplayName("超过 4KB 截断并留标记，不做静默裁剪")
        void truncatesAndMarks() {
            String command = "echo " + "a".repeat(CaseCommandExtractor.MAX_COMMAND_LENGTH + 100);
            String text = CaseCommandExtractor.fromPytestMark(command).text();
            assertTrue(text.endsWith("…（已截断）"));
            assertEquals(CaseCommandExtractor.MAX_COMMAND_LENGTH + "…（已截断）".length(),
                    text.length());
        }

        @Test
        @DisplayName("来源清单与门禁 28 步共用同一份，五个值缺一不可")
        void allowedSources() {
            assertEquals(List.of("RF_KEYWORD", "PYTEST_MARK", "STDOUT_EXTRACT",
                            "COMMAND_INDEX", "NONE"),
                    CaseCommandExtractor.allowedSources());
        }

        @Test
        @DisplayName("命令索引来源：原文不需要认，直接落库并标 COMMAND_INDEX")
        void commandIndex() {
            CaseCommandExtractor.Extracted found =
                    CaseCommandExtractor.fromCommandIndex("ethtool -k ens17np0");
            assertEquals("ethtool -k ens17np0", found.text());
            assertEquals(CaseCommandExtractor.SOURCE_COMMAND_INDEX, found.source());
            assertTrue(found.hasCommand());
        }

        @Test
        @DisplayName("命令索引为空时不造命令：显式 NONE + 来源 NONE")
        void commandIndexBlank() {
            CaseCommandExtractor.Extracted found = CaseCommandExtractor.fromCommandIndex("  ");
            assertEquals(CaseCommandExtractor.NONE, found.text());
            assertEquals(CaseCommandExtractor.SOURCE_NONE, found.source());
            assertFalse(found.hasCommand());
        }
    }
}
