package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * pytest 命令构造（W51-05 / FR-v5.1-01/07）。
 *
 * 最关键的一条：argv 里**不能出现** `--json-report` / `--metadata` / `-n`
 * ——用例库 requirements 只有 pytest/paramiko/PyYAML，带这些参数实测直接
 * `error: unrecognized arguments`。
 */
class PytestNicKernelCommandTest {

    private static final long PROJECT = 3L;

    @Test
    void 默认契约下不出现插件参数(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "config/environments/run_env_default.yaml")));

        List<PytestNicKernelCommand.Command> plans = command.build(PROJECT, "RUN-1",
                List.of("tests/nic/base/test_im_fun_020.py::test_im_fun_020"),
                "fake", dir.resolve("run"), PytestNicKernelCommand.Contract.minimal());

        assertThat(plans).hasSize(1);
        List<String> argv = plans.get(0).argv();
        assertThat(argv).containsSequence("/usr/bin/python3", "-m", "pytest");
        assertThat(argv).contains("--env_config").contains("--mode").contains("fake");
        assertThat(argv).anyMatch(item -> item.startsWith("--junitxml="));
        assertThat(argv).noneMatch(item -> item.equals("--json-report"));
        assertThat(argv).noneMatch(item -> item.equals("--metadata"));
        assertThat(argv).noneMatch(item -> item.equals("-n"));
    }

    @Test
    void 契约声明支持时才追加jsonReport(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));

        PytestNicKernelCommand.Contract full = new PytestNicKernelCommand.Contract(
                true, true, true, true, true, true);
        List<PytestNicKernelCommand.Command> plans = command.build(PROJECT, "RUN-1", List.of(),
                "real", dir.resolve("run"), full);

        assertThat(plans.get(0).argv()).contains("--json-report");
    }

    @Test
    void 超过一百条按批切分且产物不重名(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));
        List<String> nodeIds = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            nodeIds.add("tests/x/test_case_" + i + ".py::test_case_" + i);
        }

        List<PytestNicKernelCommand.Command> plans = command.build(PROJECT, "RUN-1", nodeIds,
                "fake", dir.resolve("run"), PytestNicKernelCommand.Contract.minimal());

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).nodeIds()).hasSize(100);
        assertThat(plans.get(1).nodeIds()).hasSize(50);
        assertThat(plans.get(0).junitFile()).isNotEqualTo(plans.get(1).junitFile());
    }

    @Test
    void 渲染出的runEnv覆盖配置值(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path rendered = dir.resolve("run/run_env.yaml");
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "template.yaml")));

        List<PytestNicKernelCommand.Command> plans = command.build(PROJECT, "RUN-1", List.of(),
                "fake", dir.resolve("run"), PytestNicKernelCommand.Contract.minimal(), rendered);

        List<String> argv = plans.get(0).argv();
        int idx = argv.indexOf("--env_config");
        assertThat(argv.get(idx + 1)).isEqualTo(rendered.toString());
    }

    @Test
    void 未配置仓库路径时拒绝构造(@TempDir Path dir) {
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "python_bin", "/usr/bin/python3")));

        assertThatThrownBy(() -> command.build(PROJECT, "RUN-1", List.of(), "fake", dir,
                PytestNicKernelCommand.Contract.minimal()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repo_path");
    }

    @Test
    void 仓库路径不存在时拒绝构造(@TempDir Path dir) {
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", dir.resolve("missing").toString(),
                "python_bin", "/usr/bin/python3")));

        assertThatThrownBy(() -> command.build(PROJECT, "RUN-1", List.of(), "fake", dir,
                PytestNicKernelCommand.Contract.minimal()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 环境变量prepend解释器目录且固定时区(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/opt/venv/bin/python",
                "env_config", "env.yaml")));

        Map<String, String> env = command.build(PROJECT, "RUN-1", List.of(), "fake",
                dir.resolve("run"), PytestNicKernelCommand.Contract.minimal()).get(0).environment();

        assertThat(env.get("PATH")).startsWith("/opt/venv/bin");
        assertThat(env.get("TZ")).isEqualTo("Asia/Shanghai");
        // 这里曾经断言 PYTEST_ARTIFACT_ROOT 存在——那是自造开关：框架 Runtime 的
        // artifact_root 固定为 "artifacts"，从不读任何环境变量。留着它等于给下一个人
        // 一个「改了就能搬走证据」的错觉。改为断言变量不存在，并用 artifactRoot 定位。
        assertThat(env).doesNotContainKey("PYTEST_ARTIFACT_ROOT");
        assertThat(PytestNicKernelCommand.artifactRoot(repo, "RUN-1"))
                .isEqualTo(repo.resolve("artifacts").resolve("RUN-1"));
    }

    @Test
    void 末尾追加capture_fd覆盖用例库的_s(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));

        List<String> argv = command.build(PROJECT, "RUN-1", List.of(), "fake", dir.resolve("run"),
                PytestNicKernelCommand.Contract.minimal()).get(0).argv();

        // 用例库 addopts 带 -s；不加 --capture=fd 的话 junit 的 system-out/err 全是空的，
        // 步骤树会退化成「一条都抽不到」。位置也要在 -q 之前、nodeid 之前，便于阅读。
        assertThat(argv).contains("--capture=fd");
        assertThat(argv.indexOf("--capture=fd")).isLessThan(argv.indexOf("-q"));
        assertThat(argv).containsSequence("-o", "junit_family=xunit2");
        // junit_logging 默认 no：没有这一项，捕获到了也不会写进 junit（实测 0 条明细）。
        assertThat(argv).containsSequence("-o", "junit_logging=out-err");
    }

    @Test
    void 全量执行必须带仓库根避免rootdir漂到runDir(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));

        PytestNicKernelCommand.Command plan = command.build(PROJECT, "RUN-1", List.of(),
                "fake", dir.resolve("run"), PytestNicKernelCommand.Contract.minimal()).get(0);

        // EHT-14 实测：nodeIds 为空时若不带仓库路径，pytest 把 rootdir 推到 runDir，
        // 仓库根 pyproject.toml 的 addopts 加载不到，--mode / --env_config 直接 usage error。
        assertThat(plan.argv()).endsWith(repo.toString());
        assertThat(plan.nodeIds()).isEmpty();
        assertThat(plan.workDir()).isEqualTo(repo);
    }

    /**
     * FR-v5.6-24：复测产物必须换文件名。
     *
     * <p>判据不是「有 junit-retry」这么粗——还要断言参数与首轮**逐字相同**：
     * 换参数跑出来的通过不能证明首轮是抖动。
     */
    @Test
    void 复测产物换名且参数与首轮一致(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));
        Path runDir = dir.resolve("run");
        List<String> nodeIds = List.of("tests/nic/base/test_im_fun_020.py::test_im_fun_020");

        PytestNicKernelCommand.Command first = command.build(PROJECT, "RUN-1", nodeIds,
                "real", runDir, PytestNicKernelCommand.Contract.minimal()).get(0);
        PytestNicKernelCommand.Command retry = command.buildRetry(PROJECT, "RUN-1", nodeIds,
                "real", runDir, PytestNicKernelCommand.Contract.minimal(), null, 1).get(0);

        assertThat(retry.junitFile().getFileName().toString()).isEqualTo("junit-retry-1.xml");
        assertThat(first.junitFile().getFileName().toString())
                .isNotEqualTo(retry.junitFile().getFileName().toString());
        assertThat(retry.argv()).anyMatch(arg -> arg.equals("--junitxml=" + retry.junitFile()));
        assertThat(retry.outputDir()).isNotEqualTo(first.outputDir());
        // 参数逐字比对（除产物路径外）：环境、模式、用例集合都不能变
        assertThat(retry.argv().stream().filter(arg -> !arg.startsWith("--junitxml=")).toList())
                .isEqualTo(first.argv().stream().filter(arg -> !arg.startsWith("--junitxml="))
                        .toList());
    }

    @Test
    void 复测轮次必须从1开始(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        PytestNicKernelCommand command = new PytestNicKernelCommand(new FakeSettings(Map.of(
                "repo_path", repo.toString(),
                "python_bin", "/usr/bin/python3",
                "env_config", "env.yaml")));
        assertThatThrownBy(() -> command.buildRetry(PROJECT, "RUN-1",
                List.of("t.py::a"), "fake", dir.resolve("run"),
                PytestNicKernelCommand.Contract.minimal(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(command.buildRetry(PROJECT, "RUN-1", List.of(), "fake", dir.resolve("run"),
                PytestNicKernelCommand.Contract.minimal(), null, 1)).isEmpty();
    }

    private record FakeSettings(Map<String, String> values) implements ProjectSettingRepository {

        @Override
        public Optional<String> find(long projectId, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, String> findAll(long projectId, String prefix) {
            return values;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(long projectId, String key) {
            throw new UnsupportedOperationException();
        }
    }
}
