package com.qa.autotest.application.system;

import com.qa.autotest.application.ai.AiBundledAgent;
import com.qa.autotest.application.ai.AiCliAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 构建信息与环境自检（NAV-03 / FR-v2.0-11）。
 *
 * <p>版本 / commit / 构建时间<b>必须来自构建产物</b>：{@code scripts/build.sh} 在打包前
 * 生成 {@code build-info.properties}（git sha + UTC 时间 + 依赖清单）打进 jar。
 * 直接手写进 Java 常量会重演「关于页显示旧版本」的老问题。
 *
 * <p>开发期没跑过 build.sh 时该文件不存在，此时 {@code source=runtime}、依赖清单标注为
 * 运行时兜底，页面必须把这个状态显示出来，不许伪装成构建产物。
 */
@Service
public class BuildInfoService {

    private static final String RESOURCE = "/build-info.properties";

    /** 运行时兜底清单：只在没有构建产物时出现，明确标注来源，不冒充真实版本。 */
    private static final List<String> FALLBACK_DEPS = List.of(
            "Spring Boot:runtime", "Spring JDBC:runtime", "MariaDB Java Client:runtime",
            "Flyway:runtime", "Vue:runtime", "Vue Router:runtime", "Pinia:runtime",
            "vue-i18n:runtime", "Naive UI:runtime", "Vite:runtime", "Vitest:runtime",
            "Robot Framework:runtime", "pytest:runtime");

    private final Properties build = new Properties();
    private final boolean fromBuild;

    @Value("${platform.entry-port:8001}")
    private String entryPort = "8001";

    public BuildInfoService() {
        boolean loaded = false;
        try (InputStream in = BuildInfoService.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                build.load(in);
                loaded = build.containsKey("app.version");
            }
        } catch (IOException ignored) {
            // 构建产物损坏时按「无产物」处理，不让启动失败
            loaded = false;
        }
        this.fromBuild = loaded;
    }

    /** 关于页主体：版本、commit、构建时间、依赖清单、许可证与端口矩阵。 */
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app", "robot-autotest");
        result.put("version", version());
        result.put("versionSource", versionSource());
        result.put("gitCommit", prop("git.commit", "unknown"));
        result.put("buildTime", prop("build.time", "unknown"));
        result.put("source", fromBuild ? "build" : "runtime");
        result.put("entryPort", entryPort);
        result.put("backendPort", prop("backend.port", "8081"));
        result.put("devPort", prop("dev.port", "5173"));
        result.put("license", "Apache-2.0（见仓库 LICENSE / NOTICE）");
        result.put("dependencies", dependencies());
        return result;
    }

    /**
     * 版本号：**运行期可覆盖**，不必重建 jar。
     *
     * <p>发布时 `scripts/gen_build_info.py` 读仓库根 `VERSION` 写进产物；运维若只换了
     * 一份构建产物（或灰度时想在不重建的前提下改口径），设 `APP_VERSION` 环境变量、
     * 重启服务即可生效。关于页把来源一并显示，避免「到底是哪个值」说不清。
     */
    String version() {
        String env = runtimeVersion();
        return env != null ? env : prop("app.version", "unknown");
    }

    /** env / file / pom / runtime：与 `gen_build_info.py` 的取值链一一对应。 */
    String versionSource() {
        if (runtimeVersion() != null) {
            return "env";
        }
        String declared = build.getProperty("app.version.source", "").trim();
        if (!declared.isEmpty()) {
            return declared;
        }
        return fromBuild ? "build" : "runtime";
    }

    private static String runtimeVersion() {
        String value = System.getenv("APP_VERSION");
        if (value == null) {
            value = System.getProperty("APP_VERSION");
        }
        if (value == null) {
            value = System.getenv("PLATFORM_VERSION");
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 自检四项：DB / 执行器 / 内核 / AI CLI。探测失败不算服务不可用，只如实标状态。 */
    public Map<String, Object> selfCheck(JdbcTemplate jdbc) {
        Map<String, Object> checks = new LinkedHashMap<>();
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            checks.put("database", one != null && one == 1 ? "OK" : "DOWN");
        } catch (RuntimeException ex) {
            checks.put("database", "DOWN");
        }
        checks.put("executor", onPath("robot") ? "OK" : "NOT_INSTALLED");
        checks.put("kernel", onPath("pytest") || onPath("robot") ? "OK" : "NOT_INSTALLED");
        checks.put("aiCli", anyAiCli() ? "OK" : "NOT_INSTALLED");
        return checks;
    }

    /** AI CLI 自检必须覆盖全部候选（含内置兜底），只查老四条会漏报可用状态。 */
    private static boolean anyAiCli() {
        for (String name : AiCliAdapter.CANDIDATES) {
            if (onPath(name)) {
                return true;
            }
        }
        return AiBundledAgent.available();
    }

    private List<Map<String, String>> dependencies() {
        List<Map<String, String>> result = new ArrayList<>();
        String raw = build.getProperty("dependencies", "");
        List<String> entries = raw.isBlank()
                ? FALLBACK_DEPS
                : List.of(raw.split("\\|"));
        for (String entry : entries) {
            int at = entry.lastIndexOf(':');
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", at > 0 ? entry.substring(0, at) : entry);
            item.put("version", at > 0 ? entry.substring(at + 1) : "unknown");
            item.put("source", fromBuild ? "build" : "runtime");
            result.add(item);
        }
        return result;
    }

    private String prop(String key, String fallback) {
        String value = build.getProperty(key);
        return value == null || value.isBlank() || value.startsWith("${") ? fallback : value;
    }

    /** 只查 PATH，不执行命令：自检不能因为某个 CLI 卡住而拖慢整个页面。 */
    private static boolean onPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String name : names) {
                if (Files.isExecutable(Path.of(dir, name))) {
                    return true;
                }
            }
        }
        return false;
    }
}
