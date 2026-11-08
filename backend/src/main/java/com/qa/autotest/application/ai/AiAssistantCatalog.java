package com.qa.autotest.application.ai;

import java.util.List;

/**
 * AI 助手的静态目录（v2.5 / FR-AI-10）。
 *
 * <p>这里只放**不随部署变化**的事实：CLI 标识、显示名、非交互模板、以及它能接受哪些
 * 环境变量 / 参数来覆盖端点、凭据与模型。端点、密钥、模型的实际取值在
 * {@code ai_assistant_config} 表里，由 {@link AiAssistantConfigService} 合并。
 *
 * <p>{@code credentialEnv} / {@code baseUrlEnv} / {@code modelFlag} 为 {@code null}
 * 表示**这个 CLI 不接受该注入**（例如 Hermes 走自己的配置文件）。这种情况宁可显式
 * 标注「仅记录、不注入」，也不能假装配置生效——否则用户在设置页填了 key，真实调用
 * 却仍走旧配置，属于最难排查的一类问题。
 */
public final class AiAssistantCatalog {

    /**
     * @param key            与 {@link AiCliAdapter#CANDIDATES} 对齐的标识
     * @param name           展示名
     * @param command        非交互调用模板（用于设置页展示，不参与拼装）
     * @param credentialEnv  注入 API Key 的环境变量名（可多个，空列表=不可注入）
     * @param baseUrlEnv     注入自定义端点的环境变量名；null=不可注入
     * @param modelFlag      注入模型参数使用的开关；null=不接受
     * @param credentialHint 凭据从哪来的一句话说明（中文，直接展示）
     */
    public record Entry(
            String key,
            String name,
            String command,
            List<String> credentialEnv,
            String baseUrlEnv,
            String modelFlag,
            String credentialHint,
            List<String> models) {

        /** 是否能通过本平台的配置真正覆盖运行时行为。 */
        public boolean injectable() {
            return !credentialEnv.isEmpty() || baseUrlEnv != null || modelFlag != null;
        }
    }

    /**
     * 常见模型只是**建议值**（设置页做下拉提示），可自由填写：
     * 各家模型迭代很快，把清单当白名单会让用户换不了新模型。
     */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("hermes", "Hermes Agent", "hermes -z '<text>'",
                    List.of(), null, null, "以自身配置为准（~/.hermes），设置页仅记录",
                    List.of()),
            new Entry("dsh", "DeepSeek Harness", "dsh headless '<text>'",
                    List.of("DEEPSEEK_API_KEY"), null, "--model",
                    "需要 DeepSeek 官方 Key（sk- 开头），ARK Key 会被判 401",
                    List.of("deepseek-chat", "deepseek-reasoner")),
            new Entry("claude", "Claude Code", "claude -p '<text>'",
                    List.of("ANTHROPIC_API_KEY"), "ANTHROPIC_BASE_URL", "--model",
                    "Anthropic Key；本机若被程序黑名单拦截则无法真实调用",
                    List.of("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5")),
            new Entry("codex", "Codex CLI", "codex exec '<text>'",
                    List.of("OPENAI_API_KEY"), "OPENAI_BASE_URL", "--model",
                    "OpenAI 兼容端点；默认读 ~/.codex/config.toml",
                    List.of("gpt-5", "gpt-5-codex", "deepseek-v4-flash")),
            new Entry("gemini", "Gemini CLI", "gemini -p '<text>'",
                    List.of("GEMINI_API_KEY"), null, "--model",
                    "Google AI Studio Key",
                    List.of("gemini-2.5-pro", "gemini-2.5-flash")),
            new Entry("aider", "Aider", "aider --message '<text>'",
                    List.of("OPENAI_API_KEY"), "OPENAI_API_BASE", "--model",
                    "OpenAI 兼容 Key（注意端点变量名是 OPENAI_API_BASE）",
                    List.of("gpt-5", "deepseek-v4-flash")),
            new Entry("pi", "pi coding agent（内置兜底）", "pi -p '<text>'",
                    List.of("OPENAI_API_KEY", "ARK_API_KEY"), "OPENAI_BASE_URL", "--model",
                    "开源 MIT；随软件包安装。模型可写 provider/id（如 ark/deepseek-v4-flash）",
                    List.of("deepseek-v4-flash", "gpt-4.1")));

    private AiAssistantCatalog() {
    }

    /** 按 key 找条目；未知 key 返回 null（调用方负责报 400）。 */
    public static Entry find(String key) {
        if (key == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.key().equalsIgnoreCase(key.trim())) {
                return entry;
            }
        }
        return null;
    }
}
