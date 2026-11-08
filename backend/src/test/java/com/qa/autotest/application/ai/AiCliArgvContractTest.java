package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-AI-02：4 个候选 CLI 的 argv 契约；产品 argv 零 prompt 文件开关。 */
class AiCliArgvContractTest {

    private static final String BANNED = "--" + "prompt" + "-file";

    @Test
    void claudeUsesDashPAndTextOutput() {
        List<String> argv = AiCliAdapter.argvFor("claude", "hello 中文");
        assertThat(argv).startsWith("claude", "-p");
        assertThat(argv).contains("hello 中文", "--output-format", "text");
        assertThat(argv).noneMatch(arg -> arg.startsWith("--prompt"));
    }

    @Test
    void fourCandidatesEachHaveTheirOwnTemplate() {
        assertThat(AiCliAdapter.CANDIDATES).containsExactly("claude", "codex", "gemini", "aider");
        for (String cli : AiCliAdapter.CANDIDATES) {
            List<String> argv = AiCliAdapter.argvFor(cli, "P");
            assertThat(argv).as("CLI=" + cli).startsWith(cli);
            assertThat(argv).as("CLI=" + cli).contains("P");
            assertThat(argv).as("CLI=" + cli).noneMatch(arg -> arg.startsWith("--prompt"));
        }
    }

    @Test
    void noTemplateContainsBannedPromptFileSwitch() {
        for (String cli : AiCliAdapter.CANDIDATES) {
            assertThat(AiCliAdapter.argvFor(cli, "x")).doesNotContain(BANNED);
        }
        assertThat(new AiProcessManager().buildCommand("claude", "/tmp/prompt.txt"))
                .doesNotContain(BANNED);
    }

    @Test
    void injectedExecutableReplacesArgvZero() {
        Runnable restore = AiTestSupport.applyProperties(
                Map.of("AI_CLI_PATH", "/opt/fake-cli.sh"));
        try {
            assertThat(AiCliAdapter.command("claude", "Q"))
                    .startsWith("/opt/fake-cli.sh", "-p", "Q");
            assertThat(AiCliAdapter.hasConfiguredExecutable()).isTrue();
        } finally {
            restore.run();
        }
    }

    @Test
    void argvForIsAPureFunction() {
        assertThat(AiCliAdapter.argvFor("claude", "same"))
                .isEqualTo(AiCliAdapter.argvFor("claude", "same"));
        assertThat(AiCliAdapter.argvFor("claude", "same"))
                .isNotEqualTo(AiCliAdapter.argvFor("codex", "same"));
    }
}
