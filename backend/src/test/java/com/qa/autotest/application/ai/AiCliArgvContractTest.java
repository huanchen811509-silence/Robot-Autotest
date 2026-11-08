package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** TC-AI-02 / TC-AI-12：7 个候选 CLI 的 argv 契约；产品 argv 零 prompt 文件开关。 */
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
    void sevenCandidatesEachHaveTheirOwnTemplate() {
        assertThat(AiCliAdapter.CANDIDATES).containsExactly(
                "hermes", "dsh", "claude", "codex", "gemini", "aider", "pi");
        for (String cli : AiCliAdapter.CANDIDATES) {
            List<String> argv = AiCliAdapter.argvFor(cli, "P");
            assertThat(argv).as("CLI=" + cli).startsWith(cli);
            assertThat(argv).as("CLI=" + cli).contains("P");
            assertThat(argv).as("CLI=" + cli).noneMatch(arg -> arg.startsWith("--prompt"));
        }
    }

    /** 三条新候选的模板必须是 2026-11-08 真机实测过的那一种写法，不许「看着像」。 */
    @Test
    void newCandidatesUseVerifiedNonInteractiveTemplates() {
        assertThat(AiCliAdapter.argvFor("hermes", "P")).containsExactly("hermes", "-z", "P");
        assertThat(AiCliAdapter.argvFor("dsh", "P")).containsExactly("dsh", "headless", "P");
        assertThat(AiCliAdapter.argvFor("pi", "P")).containsExactly("pi", "-p", "P");
    }

    /** 老四条的相对顺序不许因新增而打乱（部署选型会漂移）。 */
    @Test
    void legacyFourKeepTheirRelativeOrder() {
        List<String> legacy = AiCliAdapter.CANDIDATES.stream()
                .filter(name -> Set.of("claude", "codex", "gemini", "aider").contains(name))
                .toList();
        assertThat(legacy).containsExactly("claude", "codex", "gemini", "aider");
    }

    /** pi 必须是最后一位：外部 CLI 全不可用时才轮到内置兜底。 */
    @Test
    void piIsTheLastCandidateAsBundledFallback() {
        assertThat(AiCliAdapter.CANDIDATES.get(AiCliAdapter.CANDIDATES.size() - 1))
                .isEqualTo("pi");
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

    /** 注入优先级高于内置：AI_CLI_PATH 存在时，pi 也不该被内置路径顶掉。 */
    @Test
    void injectedPathWinsOverBundledAgent() {
        Runnable restore = AiTestSupport.applyProperties(
                Map.of("AI_CLI_PATH", "/opt/fake-cli.sh"));
        try {
            assertThat(AiCliAdapter.command("pi", "Q"))
                    .startsWith("/opt/fake-cli.sh", "-p", "Q");
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

    /** AI_CLI_ARGS 追加在末尾，用于给 pi 补 --provider / --model 这类运行期参数。 */
    @Test
    void extraArgsAreAppendedAtTheEnd() {
        Runnable restore = AiTestSupport.applyProperties(Map.of(
                AiCliAdapter.EXTRA_ARGS_PROPERTY, "--provider ark --model deepseek-v4-flash"));
        try {
            List<String> argv = AiCliAdapter.command("pi", "Q");
            // argv[0] 可能是裸名 pi，也可能被内置兜底换成绝对路径，两种都合法
            assertThat(argv.get(0)).endsWith("pi");
            assertThat(argv).containsSubsequence("-p", "Q");
            assertThat(argv).endsWith("--provider", "ark", "--model", "deepseek-v4-flash");
        } finally {
            restore.run();
        }
    }

    /** 未配置时不许凭空多出参数（否则既有 4 条 CLI 的 argv 全变）。 */
    @Test
    void extraArgsAreEmptyWhenUnset() {
        assertThat(AiCliAdapter.extraArgs()).isEmpty();
        assertThat(AiCliAdapter.command("claude", "Q"))
                .isEqualTo(AiCliAdapter.argvFor("claude", "Q"));
    }

    // ---------- 内置兜底 agent（FR-AI-10） ----------

    @Test
    void bundledAgentResolvesExecutableUnderInjectedDir() throws IOException {
        Path dir = Files.createTempDirectory("ai-bundled-");
        Path bin = dir.resolve(AiBundledAgent.PI_RELATIVE);
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "#!/bin/sh\necho 已连通\n");
        assertThat(bin.toFile().setExecutable(true)).isTrue();

        Runnable restore = AiTestSupport.applyProperties(
                Map.of(AiBundledAgent.DIR_PROPERTY, dir.toString()));
        try {
            assertThat(AiBundledAgent.available()).isTrue();
            assertThat(AiBundledAgent.piExecutable()).contains(bin);
            assertThat(AiBundledAgent.root()).isEqualTo(dir);
        } finally {
            restore.run();
        }
    }

    @Test
    void bundledAgentIsEmptyWhenDirectoryHasNoExecutable() throws IOException {
        Path dir = Files.createTempDirectory("ai-bundled-empty-");
        Runnable restore = AiTestSupport.applyProperties(
                Map.of(AiBundledAgent.DIR_PROPERTY, dir.toString()));
        try {
            assertThat(AiBundledAgent.available()).isFalse();
            assertThat(AiBundledAgent.piExecutable()).isEmpty();
            assertThat(AiBundledAgent.usingWrapper()).isFalse();
        } finally {
            restore.run();
        }
    }

    /**
     * wrapper 必须优先于裸 npm bin。
     *
     * <p>2026-11-08 实测缺陷：裸 bin 的 shebang 是 {@code #!/usr/bin/env node}，
     * 后端 PATH 不含 node 时 exitCode=127，兜底失效。wrapper 自带 node 解析，必须排在前面。
     */
    @Test
    void wrapperWinsOverRawNpmBin() throws IOException {
        Path dir = Files.createTempDirectory("ai-bundled-wrapper-");
        Path raw = dir.resolve(AiBundledAgent.PI_RELATIVE);
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "#!/bin/sh\nexit 127\n");
        assertThat(raw.toFile().setExecutable(true)).isTrue();

        Path wrapper = dir.resolve(AiBundledAgent.PI_WRAPPER_RELATIVE);
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "#!/bin/sh\necho wrapped\n");
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();

        Runnable restore = AiTestSupport.applyProperties(
                Map.of(AiBundledAgent.DIR_PROPERTY, dir.toString()));
        try {
            assertThat(AiBundledAgent.usingWrapper()).isTrue();
            assertThat(AiBundledAgent.piExecutable()).contains(wrapper);
        } finally {
            restore.run();
        }
    }

    /** wrapper 缺失时必须回退到裸 npm bin，不许直接判「不可用」。 */
    @Test
    void rawNpmBinIsUsedWhenWrapperMissing() throws IOException {
        Path dir = Files.createTempDirectory("ai-bundled-nowrap-");
        Path raw = dir.resolve(AiBundledAgent.PI_RELATIVE);
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "#!/bin/sh\necho raw\n");
        assertThat(raw.toFile().setExecutable(true)).isTrue();

        Runnable restore = AiTestSupport.applyProperties(
                Map.of(AiBundledAgent.DIR_PROPERTY, dir.toString()));
        try {
            assertThat(AiBundledAgent.usingWrapper()).isFalse();
            assertThat(AiBundledAgent.piExecutable()).contains(raw);
        } finally {
            restore.run();
        }
    }

    /** 内置副本缺席时，pi 的 argv[0] 必须仍是裸名 pi，交给 PATH 解析，不许拼出坏路径。 */
    @Test
    void missingBundledAgentLeavesBarePiName() throws IOException {
        Path dir = Files.createTempDirectory("ai-bundled-missing-");
        Runnable restore = AiTestSupport.applyProperties(
                Map.of(AiBundledAgent.DIR_PROPERTY, dir.toString()));
        try {
            List<String> argv = AiCliAdapter.command("pi", "Q");
            assertThat(argv).startsWith("pi", "-p", "Q");
        } finally {
            restore.run();
        }
    }
}
