package com.qa.autotest.application.ai;

import com.qa.autotest.application.kernel.EnvironmentProbe;
import java.nio.file.Path;
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

    /** 追加运行期参数键：系统属性优先，其次同名环境变量。 */
    public static final String EXTRA_ARGS_PROPERTY = "AI_CLI_ARGS";

    /**
     * 候选 CLI，顺序即优先级（与 config/ai-cli.yml 一致）。
     *
     * <p>2026-11-08 增补（FR-AI-09）：新增 hermes / dsh / pi 三条。hermes 与 dsh 排在最前，
     * 让大师点名新增的两条外部 CLI 优先生效；原先四条（claude → codex → gemini → aider）的
     * 相对顺序与各自 argv 模板一字未改，只是整体后移。
     *
     * <p>{@code pi} 排最后一位，是「软件包内置兜底 agent」——外部 CLI 一个都不可用时仍能作答，
     * 其可执行文件由 {@link AiBundledAgent} 解析（PATH 上没有 pi 就用内置副本）。
     */
    public static final List<String> CANDIDATES =
            List.of("hermes", "dsh", "claude", "codex", "gemini", "aider", "pi");

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
            // 2026-11-08 实测：hermes -z '文本' → rc=0，直接输出答复
            case "hermes" -> List.of("hermes", "-z", text);
            // 2026-11-08 实测：dsh headless '文本' → 单次任务、打印结果即退出（deepseek-harness）
            case "dsh" -> List.of("dsh", "headless", text);
            // 2026-11-08 实测：pi -p '文本' → 非交互打印模式（内置兜底 agent）
            case "pi" -> List.of("pi", "-p", text);
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

    /**
     * 供 ProcessBuilder 使用的完整命令。
     *
     * <p>可执行文件解析优先级：{@code AI_CLI_PATH} 注入 → 内置兜底（仅 pi，且 PATH 上没有 pi）
     * → argv[0] 原名（交给 PATH 解析）。第三条是既有的、也是绝大多数部署的路径。
     *
     * <p>末尾还会追加 {@code AI_CLI_ARGS}（空白分隔）——用于给某条 CLI 补运行期参数，
     * 例如 pi 需要 {@code --provider ark --model deepseek-v4-flash} 才能走本机可达的端点。
     * 只允许追加在末尾：这三条新候选（hermes / dsh / pi）的选项都允许出现在提示词之后。
     */
    public static List<String> command(String cli, String prompt) {
        List<String> base = new ArrayList<>(argvFor(cli, prompt));
        String executable = configuredExecutable();
        if (executable == null) {
            executable = bundledExecutable(cli);
        }
        if (executable != null) {
            base.set(0, executable);
        }
        base.addAll(extraArgs());
        return List.copyOf(base);
    }

    /** 运行期追加参数；非法 / 空白一律返回空列表，绝不让配置错误变成启动失败。 */
    static List<String> extraArgs() {
        String raw = System.getProperty(EXTRA_ARGS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(EXTRA_ARGS_PROPERTY);
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String part : raw.trim().split("\\s+")) {
            if (!part.isBlank()) {
                args.add(part);
            }
        }
        return List.copyOf(args);
    }

    /**
     * 内置兜底解析（FR-AI-10）：只有 pi 支持，且仅当 PATH 上找不到 pi 时启用。
     *
     * <p>PATH 优先是有意的：用户自己装了 pi 就应该用他自己的版本与凭据，
     * 内置副本只在「什么都没有」时兜底。
     */
    private static String bundledExecutable(String cli) {
        if (!"pi".equals(cli)) {
            return null;
        }
        if (EnvironmentProbe.onPath("pi")) {
            return null;
        }
        return AiBundledAgent.piExecutable().map(Path::toString).orElse(null);
    }
}
