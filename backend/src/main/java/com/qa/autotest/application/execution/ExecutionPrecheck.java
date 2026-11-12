package com.qa.autotest.application.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行前预检。任何一项不满足即拒绝启动，不允许带病执行。
 *
 * <p>v5.1.0 / W51-09（冲突 C-3）：按内核分派。
 * <ul>
 *   <li>{@code kernel=robot}（默认）——行为与 v5.0.0 **逐字不变**（RK-3 红线）；</li>
 *   <li>{@code kernel=pytest} —— <b>不检查 pabot</b>。原来的实现在这里硬跑
 *       {@code pabot --version}，pytest 工程会被永远误拦：「找不到 pabot」跟
 *       「pytest 能不能跑」毫无关系。改查解释器可执行 + 契约探测结果。</li>
 * </ul>
 */
public final class ExecutionPrecheck {

    /** 内核名常量，避免对 {@code PytestKernel.NAME} 产生反向依赖。 */
    public static final String KERNEL_PYTEST = "pytest";

    private final FileSystemProbe files;
    private final PabotVersionProbe versions;

    public ExecutionPrecheck(FileSystemProbe files, PabotVersionProbe versions) {
        this.files = files;
        this.versions = versions;
    }

    public List<Violation> check(PrecheckRequest req) {
        List<Violation> violations = new ArrayList<>();
        boolean pytest = KERNEL_PYTEST.equalsIgnoreCase(req.kernel());

        if (pytest) {
            checkPytestRuntime(req, violations);
        } else {
            // ↓ 以下整段是 v5.0.0 原文，逐字未改（RK-3）。
            Path pabot = req.venvBin() == null ? null : req.venvBin().resolve("pabot");
            if (pabot == null || !files.exists(pabot) || !files.executable(pabot)) {
                violations.add(new Violation("PABOT_NOT_FOUND",
                        "pabot 不存在或不可执行: " + pabot));
            } else {
                String expected = req.pabotVersionPrefix();
                String actual = versions.version(pabot).orElse(null);
                if (actual == null) {
                    violations.add(new Violation("PABOT_VERSION", "无法获取 pabot 版本"));
                } else if (expected != null && !expected.isBlank()
                        && !actual.startsWith(expected)) {
                    violations.add(new Violation("PABOT_VERSION",
                            "pabot 版本不匹配: 期望 " + expected + "，实际 " + actual));
                }
            }
        }

        if (req.artifactsRoot() == null || !files.writable(req.artifactsRoot())) {
            violations.add(new Violation("ARTIFACT_ROOT_NOT_WRITABLE",
                    "产物目录不可写: " + req.artifactsRoot()));
        } else if (files.usableSpace(req.artifactsRoot()) < req.minFreeBytes()) {
            violations.add(new Violation("DISK_LOW",
                    "可用空间低于阈值: " + files.usableSpace(req.artifactsRoot())
                            + " < " + req.minFreeBytes()));
        }

        if (req.suitePaths() == null || req.suitePaths().isEmpty()) {
            violations.add(new Violation("EMPTY_SUITES", "未指定任何 suite"));
        }
        if (req.availablePorts() < req.requestedConcurrency()) {
            violations.add(new Violation("PORT_SHORTAGE",
                    "可用端口数 " + req.availablePorts() + " < 并发 " + req.requestedConcurrency()));
        }
        if (req.deviceProblems() != null && !req.deviceProblems().isEmpty()) {
            violations.add(new Violation("DEVICE_UNAVAILABLE",
                    "设备不可用: " + String.join("; ", req.deviceProblems())));
        }
        return violations;
    }

    /**
     * pytest 分支的五类拒绝（FR-v5.1-03/12、A7/A13/A14/A18/A28）：
     * ① 解释器不可执行；② 解释器版本低于用例库要求（3.11）；
     * ③ 契约不符（用例库没注册平台需要的参数）；④ 有用例没绑定脚本；
     * ⑤ 证据目录问题由上面的通用检查覆盖。
     */
    private void checkPytestRuntime(PrecheckRequest req, List<Violation> violations) {
        Path python = req.pytestPythonBin();
        if (python == null || !files.exists(python) || !files.executable(python)) {
            violations.add(new Violation("PYTHON_NOT_FOUND",
                    "pytest 解释器不存在或不可执行: " + python + "（配置项 pytest.python_bin）"));
        }
        if (req.pytestPythonVersionProblem() != null
                && !req.pytestPythonVersionProblem().isBlank()) {
            violations.add(new Violation("PYTHON_TOO_OLD", req.pytestPythonVersionProblem()));
        }
        if (req.pytestContractMissing() != null && !req.pytestContractMissing().isEmpty()) {
            violations.add(new Violation("PYTEST_CONTRACT",
                    "用例库未注册平台需要的参数: "
                            + String.join(", ", req.pytestContractMissing())
                            + "（建议检查用例库版本，或在 /kernels 页重新探测）"));
        }
        if (req.unboundCaseCodes() != null && !req.unboundCaseCodes().isEmpty()) {
            List<String> sample = req.unboundCaseCodes().size() > 10
                    ? req.unboundCaseCodes().subList(0, 10) : req.unboundCaseCodes();
            violations.add(new Violation("CASE_UNBOUND",
                    req.unboundCaseCodes().size() + " 条用例没有绑定脚本，例如 "
                            + String.join(", ", sample)
                            + "（去「用例绑定」页处理后再提交）"));
        }
    }

    /**
     * 预检请求。v5.1 追加四个 pytest 专用字段，旧 8 参构造器保留（RF 调用点零改动）。
     *
     * @param kernel                robot / pytest
     * @param pytestPythonBin       pytest 解释器路径
     * @param pytestContractMissing 契约探测缺的参数名（空 = 契约满足）
     * @param unboundCaseCodes      未绑定脚本的用例编号
     */
    public record PrecheckRequest(Path venvBin,
                                  Path artifactsRoot,
                                  List<String> suitePaths,
                                  int requestedConcurrency,
                                  int availablePorts,
                                  long minFreeBytes,
                                  String pabotVersionPrefix,
                                  List<String> deviceProblems,
                                  String kernel,
                                  Path pytestPythonBin,
                                  List<String> pytestContractMissing,
                                  List<String> unboundCaseCodes,
                                  String pytestPythonVersionProblem) {

        /** v5.0.0 的 8 参形态：默认 robot 内核，pytest 字段全空。 */
        public PrecheckRequest(Path venvBin, Path artifactsRoot, List<String> suitePaths,
                               int requestedConcurrency, int availablePorts, long minFreeBytes,
                               String pabotVersionPrefix, List<String> deviceProblems) {
            this(venvBin, artifactsRoot, suitePaths, requestedConcurrency, availablePorts,
                    minFreeBytes, pabotVersionPrefix, deviceProblems, "robot", null, List.of(),
                    List.of(), null);
        }

        /** v5.1.0 的 12 参形态（无版本门槛）。 */
        public PrecheckRequest(Path venvBin, Path artifactsRoot, List<String> suitePaths,
                               int requestedConcurrency, int availablePorts, long minFreeBytes,
                               String pabotVersionPrefix, List<String> deviceProblems, String kernel,
                               Path pytestPythonBin, List<String> pytestContractMissing,
                               List<String> unboundCaseCodes) {
            this(venvBin, artifactsRoot, suitePaths, requestedConcurrency, availablePorts,
                    minFreeBytes, pabotVersionPrefix, deviceProblems, kernel, pytestPythonBin,
                    pytestContractMissing, unboundCaseCodes, null);
        }

        public PrecheckRequest {
            kernel = kernel == null || kernel.isBlank() ? "robot" : kernel;
            pytestContractMissing = pytestContractMissing == null
                    ? List.of() : List.copyOf(pytestContractMissing);
            unboundCaseCodes = unboundCaseCodes == null ? List.of() : List.copyOf(unboundCaseCodes);
        }
    }

    public record Violation(String code, String message) {
    }
}
