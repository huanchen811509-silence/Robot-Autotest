package com.qa.autotest.application.kernel.pytest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * pytest 契约探测（FR-v5.1-07 / 验收 A16）。
 *
 * <p>平台**不假设**用例库装了哪些插件——直接问解释器：跑 {@code python -m pytest --help}，
 * 从帮助输出里认参数名。认到就用，认不到就不加。
 *
 * <p>这条设计的直接来源是一次实测：平台旧命令写死 {@code --json-report}，
 * 而用例库 {@code requirements.txt} 只有 pytest / paramiko / PyYAML，真实执行报
 * {@code error: unrecognized arguments: --json-report}，整条链路起不来。
 * 硬编码插件假设 = 换一个用例库就死一次。
 *
 * <p>缓存 24 小时：{@code --help} 要起一个 Python 进程（约 200–400ms），
 * 每次执行都探一次会拖慢预检。解释器升级后由界面「重新探测」按钮或 24h 过期刷新。
 */
@Service
public class PytestContractProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long TTL_MILLIS = Duration.ofHours(24).toMillis();

    /**
     * 最低解释器版本。来源不是拍脑袋：用例库 {@code pyproject.toml} 写的是
     * {@code requires-python = ">=3.11"}。低于这条线时装不上框架，
     * 装上了也跑不了 —— 与其让执行期炸一半，不如预检就拒。
     */
    public static final String MIN_PYTHON = "3.11";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 缓存整份快照（不只契约）：否则缓存命中时页面上的版本号会变空。 */
    private record CacheEntry(PytestNicKernelCommand.Contract contract,
                              String pytestVersion,
                              String pythonVersion,
                              List<String> foundOptions,
                              long at) {
    }

    /** 探测结果 + 原始帮助文本摘要，供 {@code /kernels} 页面展示「凭什么这么判定」。 */
    public record ProbeResult(PytestNicKernelCommand.Contract contract,
                              String pytestVersion,
                              String pythonVersion,
                              List<String> foundOptions,
                              long elapsedMillis,
                              String failureReason) {

        /** 兼容 5 参形态（不带解释器版本）。 */
        public ProbeResult(PytestNicKernelCommand.Contract contract, String pytestVersion,
                           List<String> foundOptions, long elapsedMillis, String failureReason) {
            this(contract, pytestVersion, null, foundOptions, elapsedMillis, failureReason);
        }

        public boolean healthy() {
            return failureReason == null;
        }

        /** 版本不达标时的说明文字；达标或版本未知时返回 {@code null}。 */
        public String versionProblem() {
            if (pythonVersion == null || pythonVersion.isBlank()) {
                return null;
            }
            if (atLeast(pythonVersion, MIN_PYTHON)) {
                return null;
            }
            return "解释器版本 " + pythonVersion + " 低于用例库要求的最低版本 " + MIN_PYTHON
                    + "（pyproject.toml requires-python），请改用 " + MIN_PYTHON
                    + "+ 的虚拟环境并在「工程配置」更新 pytest.python_bin";
        }
    }

    /**
     * 探测指定解释器。失败（解释器不存在 / 退出码非 0 / 超时）时返回 {@code healthy()==false}
     * 并带上原因，**不回退到乐观默认值**——乐观默认会让预检放过一个根本跑不起来的工程。
     */
    public ProbeResult probe(String pythonBin, boolean refresh) {
        if (pythonBin == null || pythonBin.isBlank()) {
            return new ProbeResult(PytestNicKernelCommand.Contract.minimal(), null, null, List.of(), 0,
                    "未配置 python_bin");
        }
        long now = System.currentTimeMillis();
        if (!refresh) {
            CacheEntry cached = cache.get(pythonBin);
            if (cached != null && now - cached.at() < TTL_MILLIS) {
                return new ProbeResult(cached.contract(), cached.pytestVersion(),
                        cached.pythonVersion(), cached.foundOptions(), 0, null);
            }
        }
        long started = System.nanoTime();
        String pythonVersion = runPythonVersion(pythonBin);
        String help;
        try {
            help = runHelp(pythonBin);
        } catch (Exception e) {
            return new ProbeResult(PytestNicKernelCommand.Contract.minimal(), null, pythonVersion,
                    List.of(), (System.nanoTime() - started) / 1_000_000, e.getMessage());
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        if (help == null) {
            return new ProbeResult(PytestNicKernelCommand.Contract.minimal(), null, pythonVersion,
                    List.of(), elapsed, "pytest --help 执行超时或被中断");
        }
        List<String> found = new java.util.ArrayList<>();
        for (String option : List.of("--json-report", "--metadata", "--numprocesses",
                "--env_config", "--mode", "--junitxml")) {
            if (help.contains(option)) {
                found.add(option);
            }
        }
        PytestNicKernelCommand.Contract contract = new PytestNicKernelCommand.Contract(
                found.contains("--mode"),
                found.contains("--env_config"),
                found.contains("--junitxml"),
                found.contains("--json-report"),
                found.contains("--metadata"),
                found.contains("--numprocesses"));
        String pytestVersion = version(help);
        cache.put(pythonBin, new CacheEntry(contract, pytestVersion, pythonVersion,
                List.copyOf(found), now));
        return new ProbeResult(contract, pytestVersion, pythonVersion, List.copyOf(found), elapsed,
                null);
    }

    /**
     * 取解释器版本（{@code 3.11.9} 形态）。取不到返回 {@code null}——
     * 版本未知时不拦截，交给 {@code pytest --help} 的实际结果说话，
     * 避免「探测不到版本」变成新的误拦来源。
     */
    public static String runPythonVersion(String pythonBin) {
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonBin, "-c",
                    "import sys;print('%d.%d.%d' % sys.version_info[:3])");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(out);
            if (!matcher.find()) {
                return null;
            }
            String patch = matcher.group(3) == null ? "0" : matcher.group(3);
            return matcher.group(1) + "." + matcher.group(2) + "." + patch;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** 逐段数值比较，不按字典序（否则 {@code 3.9 > 3.11}）。 */
    public static boolean atLeast(String version, String minimum) {
        int[] a = parts(version);
        int[] b = parts(minimum);
        if (a == null || b == null) {
            return true;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return true;
    }

    private static int[] parts(String version) {
        if (version == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\s*(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(version);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 清缓存（解释器升级 / 手工重探）。 */
    public void invalidate(String pythonBin) {
        if (pythonBin == null) {
            cache.clear();
        } else {
            cache.remove(pythonBin);
        }
    }

    /**
     * 跑 {@code python -m pytest --help}。返回 null 表示超时。
     * 用 {@code --help} 而不是 {@code --version}：前者能列出插件注册的参数，
     * 后者只能证明解释器活着（项目记忆里的坑：{@code --version} 通过 ≠ 能推理）。
     */
    private static String runHelp(String pythonBin) throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("pytest-probe-");
        ProcessBuilder builder = new ProcessBuilder(pythonBin, "-m", "pytest", "--help");
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return null;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Files.deleteIfExists(work);
        } catch (IOException ignored) {
            // 临时目录清理失败不影响探测结论
        }
        if (process.exitValue() != 0) {
            throw new IOException("pytest --help 退出码 " + process.exitValue() + "："
                    + firstLines(output, 3));
        }
        return output;
    }

    /** 从帮助头几行取版本号（`pytest 9.2.0` 这种形态），取不到返回 null。 */
    private static String version(String help) {
        for (String line : help.split("\n", 6)) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("pytest ")) {
                return line.trim().substring(7).trim();
            }
        }
        return null;
    }

    private static String firstLines(String text, int count) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, lines.length); i++) {
            sb.append(lines[i].trim()).append(i == count - 1 ? "" : " | ");
        }
        return sb.toString();
    }
}
