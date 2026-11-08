package com.qa.autotest.application.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 软件包内置兜底 agent（FR-AI-10）：pi coding agent 的本地副本解析。
 *
 * <p>动机（2026-11-08 大师要求「保底在软件包里集成一个 pi agent」）：本机可能一个外部
 * AI CLI 都没有——claude 被安全策略阻断、codex 缺凭据、gemini/aider 未装。此时 AI 面板
 * 只能停在 L1 静态建议。pi 是 MIT 许可、单二进制入口（{@code pi -p}）的最小 agent，
 * 随软件包安装后作为最后一位候选，保证 L2/L3 至少有一条可用路径。
 *
 * <p>解析顺序（只看文件，不跑进程、不联网）：
 * <ol>
 *   <li>{@code AI_BUNDLED_AGENT_DIR} 系统属性，其次同名环境变量；</li>
 *   <li>缺省 {@code $HOME/.local/share/robot-autotest/agents/pi}。</li>
 * </ol>
 * 目录内可执行文件固定为 {@code node_modules/.bin/pi}。任何异常一律返回 empty，
 * 绝不让「内置副本缺失」变成启动失败。
 */
public final class AiBundledAgent {

    /** 内置目录注入键：系统属性优先，其次同名环境变量；测试据此指向临时目录。 */
    public static final String DIR_PROPERTY = "AI_BUNDLED_AGENT_DIR";

    /** 内置副本里 pi 的相对路径（npm 全局式布局的 bin 软链）。 */
    static final String PI_RELATIVE = "node_modules/.bin/pi";

    /**
     * 优先使用的 wrapper 相对路径。
     *
     * <p>为什么需要它：{@code node_modules/.bin/pi} 的 shebang 是 {@code #!/usr/bin/env node}，
     * 只认 PATH 上的 node。2026-11-08 实测：后端 PATH 收窄为 {@code /usr/local/bin:/usr/bin:/bin}
     * 时，内置 pi 被选中却以 {@code exitCode=127 / /usr/bin/env: 'node': No such file or directory}
     * 降级——「兜底」在真实环境里直接失效。wrapper 用纯 sh 自己找 node 再 exec，
     * 不依赖调用方 PATH。
     */
    static final String PI_WRAPPER_RELATIVE = "bin/pi";

    /** 缺省安装根：相对 $HOME 的固定路径，与 tools/ai/ensure-pi-agent.sh 保持一致。 */
    static final String DEFAULT_RELATIVE_ROOT = ".local/share/robot-autotest/agents/pi";

    private AiBundledAgent() {
    }

    /** 内置 agent 根目录；不做存在性判断。 */
    public static Path root() {
        String injected = System.getProperty(DIR_PROPERTY);
        if (injected == null || injected.isBlank()) {
            injected = System.getenv(DIR_PROPERTY);
        }
        if (injected != null && !injected.isBlank()) {
            return Path.of(injected.trim());
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return Path.of(DEFAULT_RELATIVE_ROOT);
        }
        return Path.of(home, DEFAULT_RELATIVE_ROOT.split("/"));
    }

    /**
     * 内置 pi 可执行文件；不存在或不可执行时返回 empty，绝不抛。
     *
     * <p>顺序：wrapper（{@code bin/pi}，自带 node 解析）优先 → 原始 npm bin 软链。
     * wrapper 缺失时仍可用原始软链，只是要求调用方 PATH 上必须有 node。
     */
    public static Optional<Path> piExecutable() {
        for (String relative : new String[]{PI_WRAPPER_RELATIVE, PI_RELATIVE}) {
            Optional<Path> found = executableAt(relative);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableAt(String relative) {
        try {
            Path candidate = root().resolve(relative);
            return Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** 是否用的 wrapper（而非裸 npm 软链）：排障时区分「谁在跑」。 */
    public static boolean usingWrapper() {
        return executableAt(PI_WRAPPER_RELATIVE).isPresent();
    }

    public static boolean available() {
        return piExecutable().isPresent();
    }
}
