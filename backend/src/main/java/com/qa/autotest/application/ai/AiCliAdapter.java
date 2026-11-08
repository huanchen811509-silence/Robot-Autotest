package com.qa.autotest.application.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI argv 适配（FR-AI-02 / G-A9-2）：按 CLI 名构造非交互 argv 的纯函数。
 *
 * <p>真实 claude CLI 不接受「提示词文件开关」（会 rc=1 unknown option），
 * 可用写法是 {@code claude -p '<prompt>' --output-format text}。因此本类所有模板
 * 一律把提示词作为 {@code -p} / {@code --message} 的参数值随 argv 传入，
 * 绝不拼任何 prompt 文件开关。
 *
 * <p>{@link #command(String, String)} 允许用系统属性或环境变量 {@code AI_CLI_PATH}
 * 替换 argv[0]，测试据此注入 fixtures/ai/fake-cli.sh，绝不触碰真实 CLI。
 */
public final class AiCliAdapter {

    /** CLI 可执行路径注入键：系统属性优先，其次同名环境变量。 */
    public static final String CLI_PATH_PROPERTY = "AI_CLI_PATH";

    /** 候选 CLI，顺序与 config/ai-cli.yml 一致。 */
    public static final List<String> CANDIDATES = List.of("claude", "codex", "gemini", "aider");

    private AiCliAdapter() {
    }

    /**
     * 按 CLI 名构造 argv 的纯函数：同样的入参永远得到同样的 argv，不查 PATH、不读文件。
     * 任何分支都不得出现 prompt 文件开关。
     */
    public static List<String> argvFor(String cli, String prompt) {
        String name = cli == null || cli.isBlank() ? "claude" : cli;
        String text = prompt == null ? "" : prompt;
        return switch (name) {
            case "claude" -> List.of("claude", "-p", text, "--output-format", "text");
            case "codex" -> List.of("codex", "exec", "--skip-git-repo-check", text);
            case "gemini" -> List.of("gemini", "-p", text, "--output-format", "text");
            case "aider" -> List.of("aider", "--message", text,
                    "--no-auto-commits", "--no-check-update");
            default -> List.of(name, "-p", text, "--output-format", "text");
        };
    }

    /** 读取注入的可执行路径；未注入时返回 null。 */
    public static String configuredExecutable() {
        String value = System.getProperty(CLI_PATH_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(CLI_PATH_PROPERTY);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static boolean hasConfiguredExecutable() {
        return configuredExecutable() != null;
    }

    /** 供 ProcessBuilder 使用的完整命令；AI_CLI_PATH 注入时替换 argv[0]。 */
    public static List<String> command(String cli, String prompt) {
        List<String> base = argvFor(cli, prompt);
        String executable = configuredExecutable();
        if (executable == null) {
            return base;
        }
        List<String> result = new ArrayList<>(base);
        result.set(0, executable);
        return List.copyOf(result);
    }
}
