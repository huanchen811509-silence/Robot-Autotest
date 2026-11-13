package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * pytest 内核命令装配（FR-v5.1-01 / FR-v5.1-07，验收 A1/A2/A15/A17/A30/A31）。
 *
 * <p><b>为什么不能沿用 {@code PytestKernel.command()}</b>：那条命令写死了
 * {@code --json-report} / {@code --metadata} / {@code -n}，而自研用例库
 * {@code requirements.txt} 只有 pytest / paramiko / PyYAML。实测
 * {@code error: unrecognized arguments: --json-report} —— 平台现状在真实用例库上
 * **直接起不来**。本类改用 {@code --junitxml}（pytest 内置，无需插件）+ 框架自己的
 * {@code --env_config / --mode}，并以「契约探测」结果为准决定是否追加可选参数（W51-06）。
 *
 * <p><b>工作区真源</b>：{@code project_setting.pytest.repo_path}，不是 {@code robot_file}。
 * RF 的 {@code ExecutionWorkspaceMaterializer} 从 DB 落盘 robot 文件；pytest 的脚本
 * 本来就在 git 里（D2 方案 A），平台只负责把工作区指过去，不复制、不改写。
 *
 * <p><b>分批</b>：单条命令行长度有上限（Linux ARG_MAX 与 shell 实践都建议控制在
 * 千级参数内），{@value #MAX_CASES_PER_BATCH} 个 nodeid 一批；每批独立 junit 文件，
 * 结果合并交给 {@link PytestResultNormalizer}。
 */
@Service
public class PytestNicKernelCommand {

    /** 单批 nodeid 上限。 */
    public static final int MAX_CASES_PER_BATCH = 100;

    private final ProjectSettingRepository settings;

    public PytestNicKernelCommand(ProjectSettingRepository settings) {
        this.settings = settings;
    }

    /** 一条待执行命令。复用 RF 的 {@code PabotCommand} 三件套语义（argv/env/workDir），便于共用执行设施。 */
    public record Command(List<String> argv,
                          Map<String, String> environment,
                          Path workDir,
                          Path outputDir,
                          Path junitFile,
                          int batchNo,
                          List<String> nodeIds) {
    }

    /** 契约探测结论，决定可选参数是否可用（W51-06 产出，缺省为「只认 junit」）。 */
    public record Contract(boolean supportsMode,
                           boolean supportsEnvConfig,
                           boolean supportsJunitXml,
                           boolean supportsJsonReport,
                           boolean supportsMetadata,
                           boolean supportsXdist) {

        /** 最保守的契约：只用 pytest 内置能力。 */
        public static Contract minimal() {
            return new Contract(true, true, true, false, false, false);
        }
    }

    /**
     * 构造命令列表。nodeid 为空表示「跑整个仓库」，此时不追加位置参数。
     *
     * @param mode     fake / real，缺省取工程配置（安全默认 fake）
     * @param runDir   本次运行的产物目录（junit / 日志 / 渲染配置都落这里）
     */
    public List<Command> build(long projectId, String runId, List<String> nodeIds,
                               String mode, Path runDir, Contract contract) {
        return build(projectId, runId, nodeIds, mode, runDir, contract, null);
    }

    /**
     * @param envConfigPath 渲染产物 {@code run_env.yaml} 的绝对路径；null 则用工程配置
     *                      {@code pytest.env_config}。执行链路必须传前者——配置里那条
     *                      只是模板位置，真正要跑的是按本次资产渲染出来的那份。
     */
    public List<Command> build(long projectId, String runId, List<String> nodeIds,
                               String mode, Path runDir, Contract contract, Path envConfigPath) {
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        Path repoPath = PytestProjectSettings.repoPath(config);
        if (repoPath == null) {
            throw new IllegalStateException(
                    "未配置 pytest.repo_path：pytest 工程的脚本真源是 git 工作区，必须先指到本地路径");
        }
        if (!java.nio.file.Files.isDirectory(repoPath)) {
            throw new IllegalStateException("pytest.repo_path 指向的目录不存在: " + repoPath);
        }
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);
        if (pythonBin == null) {
            throw new IllegalStateException("未配置 pytest.python_bin：无法确定用哪个解释器执行");
        }
        String envConfig = envConfigPath != null ? envConfigPath.toString()
                : PytestProjectSettings.value(config, PytestProjectSettings.ENV_CONFIG);
        if (envConfig == null) {
            // 渲染器把 run_env.yaml 落在 runDir，这里直接用绝对路径，避免「相对谁」的歧义。
            throw new IllegalStateException("未配置 pytest.env_config：run_env 路径为空");
        }
        String effectiveMode = mode != null && !mode.isBlank() ? mode.trim()
                : PytestProjectSettings.modeDefault(config);
        Contract effective = contract == null ? Contract.minimal() : contract;

        List<List<String>> batches = batch(nodeIds);
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            Path outputDir = runDir.resolve("pytest-batch-" + i);
            Path junit = batches.size() == 1
                    ? runDir.resolve("junit.xml")
                    : runDir.resolve("junit-" + i + ".xml");
            // EHT-14：全量执行（nodeIds 为空）必须显式把仓库根作为收集目标。
            // 否则 pytest 只看到 /tmp 侧的 --env_config / --junitxml，会把 rootdir
            // 推断成 runDir、inifile=None，仓库根 pyproject.toml 的 addopts
            //（-p nic_platform.pytest_plugin）加载不到，--mode / --env_config
            // 随即变成 unrecognized arguments（退出码 4，junit 一个字都不写）。
            List<String> targets = batches.get(i).isEmpty()
                    ? List.of(repoPath.toString()) : batches.get(i);
            commands.add(new Command(argv(pythonBin, envConfig, effectiveMode, junit, targets,
                    runDir, effective), environment(pythonBin, runDir), repoPath, outputDir, junit,
                    i, List.copyOf(batches.get(i))));
        }
        return List.copyOf(commands);
    }

    private static List<String> argv(String pythonBin, String envConfig, String mode, Path junit,
                                     List<String> nodeIds, Path runDir, Contract contract) {
        List<String> argv = new ArrayList<>();
        // 用 `python -m pytest` 而不是直接调 pytest 脚本：前者不依赖 venv/bin 是否在 PATH 上，
        // 也避开了 pip 安装的 console script shebang 指向错误解释器的经典问题。
        argv.add(pythonBin);
        argv.add("-m");
        argv.add("pytest");
        argv.add("--env_config");
        argv.add(envConfig);
        argv.add("--mode");
        argv.add(mode);
        if (contract.supportsJunitXml()) {
            argv.add("--junitxml=" + junit);
            // 显式钉住 xunit2：用例库 ini 若被改成 xunit1，<system-out> 的落法会变，
            // 步骤证据的解析就要跟着变。钉死比「探测 ini 里有没有写」可靠。
            argv.add("-o");
            argv.add("junit_family=xunit2");
            // junit_logging 的默认值是 **no**（`_pytest/junitxml.py` parser.addini default="no"），
            // 也就是说即便日志被捕获，junit 里也一个 <system-out>/<system-err> 都不会写。
            // 实测：只加 --capture=fd 时 junit 里 0 条明细，而 framework.log 有 3 条——
            // 光看 junit 会误判成「用例没写步骤」。取 out-err 而不是 all：
            // all 会把 caplog 也拼进 system-out，而框架的 logging 同时进 caplog 与 stderr，
            // 结果每条明细重复两遍，步骤树里会出现成对的假重复。
            argv.add("-o");
            argv.add("junit_logging=out-err");
        }
        if (contract.supportsJsonReport()) {
            argv.add("--json-report");
            argv.add("--json-report-file=" + runDir.resolve("pytest-report.json"));
        }
        // 用例库 addopts 带 -s（--capture=no，为的是 PyCharm 里日志实时滚动）。平台侧相反：
        // 我们要的是**按用例切好的**日志证据，不是控制台。用例库 pyproject.toml 的注释
        // 原话是「需要恢复捕获时，在命令行末尾追加 --capture=fd 覆盖这里的 -s」——
        // addopts 排在命令行参数之前，所以末尾这一个 --capture=fd 就能覆盖掉 -s。
        // 没有它，junit 的 <system-out>/<system-err> 全是空的，步骤树只能靠 log 顺序猜。
        // 代价：控制台不再实时输出。监视（W53）改为 tail framework.log，不依赖 stdout。
        argv.add("--capture=fd");
        argv.add("-q");
        argv.addAll(nodeIds);
        return List.copyOf(argv);
    }

    /**
     * 框架证据根：{@code <repo>/artifacts/<run_id>}。
     *
     * <p>这不是可配项。框架 {@code Runtime} 的 {@code artifact_root} 参数固定为字符串
     * {@code "artifacts"}，再经 {@code _resolve_project_path(must_exist=False)} 落到
     * {@code PROJECT_ROOT}（即仓库根），<b>不读任何环境变量</b>。我最初在这里塞过一个
     * {@code PYTEST_ARTIFACT_ROOT} 环境变量，框架根本不认，属于自造开关——已删除。
     * 唯一真实可控的是 {@code run_env.yaml} 的 {@code run_id}（决定目录名），
     * 由 {@link PytestEnvRenderer} 写成本次平台 run id，于是目录可确定定位。
     */
    public static Path artifactRoot(Path repoPath, String runId) {
        return repoPath.resolve("artifacts").resolve(runId);
    }

    private static Map<String, String> environment(String pythonBin, Path runDir) {
        Path binDir = Path.of(pythonBin).getParent();
        Map<String, String> env = new LinkedHashMap<>();
        String original = System.getenv().getOrDefault("PATH", "");
        env.put("PATH", (binDir == null ? "" : binDir.toString()) + File.pathSeparator + original);
        env.put("TZ", "Asia/Shanghai");
        env.put("LANG", "C.UTF-8");
        env.put("PYTHONUNBUFFERED", "1");
        return Map.copyOf(env);
    }

    /** 按 {@value #MAX_CASES_PER_BATCH} 切批；空输入返回一个空批（表示全仓库执行）。 */
    static List<List<String>> batch(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of(List.of());
        }
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < nodeIds.size(); start += MAX_CASES_PER_BATCH) {
            int end = Math.min(start + MAX_CASES_PER_BATCH, nodeIds.size());
            batches.add(List.copyOf(nodeIds.subList(start, end)));
        }
        return List.copyOf(batches);
    }
}
