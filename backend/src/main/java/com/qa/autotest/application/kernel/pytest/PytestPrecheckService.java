package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * pytest 执行前检查（FR-v5.1-03/06/14，验收 A7/A13/A14/A18/A32/A33）。
 *
 * <p>与 {@code ExecutionPrecheck} 的分工：本类只产出「pytest 特有的四类问题」，
 * 通用项（产物目录可写、磁盘、suitePaths 空）仍由 {@code ExecutionPrecheck} 判。
 * 之所以不塞进那一个类：它还要继续服务 RF 分支，混在一起会让 RK-3 的「逐字不变」失去意义。
 *
 * <p>四类拒绝：
 * <ol>
 *   <li>{@code PYTHON_NOT_FOUND} —— 解释器不可执行（配置项 {@code pytest.python_bin}）；</li>
 *   <li>{@code PYTEST_CONTRACT} —— 契约探测缺 {@code --mode/--env_config/--junitxml}，
 *       说明这个解释器上装的不是本用例库认可的那套框架；</li>
 *   <li>{@code CASE_UNBOUND} —— 选中的用例没有绑定脚本（含「库里有、脚本还没写」）；</li>
 *   <li>{@code DUT_SHORTAGE} —— 选中了双端用例却只给了 1 台设备。</li>
 * </ol>
 */
@Service
public class PytestPrecheckService {

    /** 平台必须依赖的三个参数；缺任一，用例库就跑不成平台要的形态。 */
    private static final List<String> REQUIRED_OPTIONS = List.of("--mode", "--env_config",
            "--junitxml");

    private final ProjectSettingRepository settings;
    private final PytestContractProbe probe;
    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final DeviceRepository devices;

    public PytestPrecheckService(ProjectSettingRepository settings, PytestContractProbe probe,
                                 CaseScriptBindingRepository bindings, TestCaseRepository cases,
                                 DeviceRepository devices) {
        this.settings = settings;
        this.probe = probe;
        this.bindings = bindings;
        this.cases = cases;
        this.devices = devices;
    }

    /** 预检上下文：交给 {@code ExecutionPrecheck.PrecheckRequest} 的 pytest 字段。 */
    public record PytestContext(Path pythonBin,
                                List<String> contractMissing,
                                List<String> unboundCodes,
                                List<String> deviceProblems,
                                String pythonVersionProblem) {

        /** 兼容四参形态（不带版本问题）。 */
        public PytestContext(Path pythonBin, List<String> contractMissing,
                             List<String> unboundCodes, List<String> deviceProblems) {
            this(pythonBin, contractMissing, unboundCodes, deviceProblems, null);
        }
    }

    /**
     * @param caseIds   本次要跑的用例 id；空表示整库跑（此时不判未绑定，因为未绑定
     *                  的用例本来就不会被选中执行）
     * @param deviceIds 本次选中的设备 id
     */
    public PytestContext inspect(long projectId, List<Long> caseIds, List<String> hostKeys) {
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);
        Path pythonPath = pythonBin == null ? null : Path.of(pythonBin);
        List<String> contractMissing = contractMissing(pythonBin);
        List<String> unbound = unboundCodes(projectId, caseIds);
        List<String> deviceProblems = deviceProblems(projectId, caseIds, hostKeys, config);
        return new PytestContext(pythonPath, List.copyOf(contractMissing), unbound,
                List.copyOf(deviceProblems), versionProblem(pythonBin));
    }

    /** 选中用例中缺绑定的编号。caseIds 为空时返回空（整库跑不因未绑定而拒绝）。 */
    private List<String> unboundCodes(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        Set<Long> bound = new LinkedHashSet<>();
        for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
            bound.add(binding.caseId());
        }
        Map<Long, String> codeById = cases.idToCode(projectId);
        List<String> unbound = new ArrayList<>();
        for (Long caseId : caseIds) {
            if (!bound.contains(caseId)) {
                unbound.add(codeById.getOrDefault(caseId, "id=" + caseId));
            }
        }
        return unbound;
    }

    /**
     * 按 nodeid 判定未绑定（执行期真正走的路径）。
     *
     * <p>pytest 的 {@code suitePaths} 本身就是 nodeid，所以「选了哪些用例」在这里是
     * **确定的 nodeid 列表**，比 caseId 更贴近执行意图：一个 nodeid 找不到绑定，
     * 意味着这条命令跑出来的结果无法回到任何用例编号上。
     */
    public PytestContext inspectByNodeIds(long projectId, List<String> nodeIds,
                                          List<String> hostKeys) {
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        String pythonBin = PytestProjectSettings.value(config, PytestProjectSettings.PYTHON_BIN);
        Path pythonPath = pythonBin == null ? null : Path.of(pythonBin);
        List<String> contractMissing = contractMissing(pythonBin);

        List<String> unbound = new ArrayList<>();
        if (nodeIds != null && !nodeIds.isEmpty()) {
            Map<String, String> byNode = new java.util.LinkedHashMap<>();
            Map<Long, String> codeById = cases.idToCode(projectId);
            for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
                if (binding.nodeId() != null) {
                    byNode.put(binding.nodeId(), codeById.getOrDefault(binding.caseId(),
                            "id=" + binding.caseId()));
                }
            }
            for (String nodeId : nodeIds) {
                // 参数化后缀不影响绑定：先按原样查，查不到再剥 `[...]`。
                String key = nodeId.indexOf('[') > 0 ? nodeId.substring(0, nodeId.indexOf('['))
                        : nodeId;
                if (!byNode.containsKey(nodeId) && !byNode.containsKey(key)) {
                    unbound.add(nodeId);
                }
            }
        }

        List<Long> caseIds = new ArrayList<>();
        if (nodeIds != null && !nodeIds.isEmpty()) {
            for (CaseScriptBinding binding : bindings.listByProject(projectId, "pytest")) {
                for (String nodeId : nodeIds) {
                    String key = nodeId.indexOf('[') > 0
                            ? nodeId.substring(0, nodeId.indexOf('[')) : nodeId;
                    if (key.equals(binding.nodeId())) {
                        caseIds.add(binding.caseId());
                    }
                }
            }
        }
        List<String> deviceProblems = deviceProblems(projectId, caseIds, hostKeys, config);
        return new PytestContext(pythonPath, contractMissing, List.copyOf(unbound),
                List.copyOf(deviceProblems), versionProblem(pythonBin));
    }

    /**
     * 解释器版本门槛（FR-v5.1-12 / 验收 A28）。
     *
     * <p>为什么单列一条而不并进「契约不符」：3.10 上 {@code pytest --help} 可能照样成功
     * （框架只是装了但缺 3.11 的语法特性会在 import 期或执行期炸），
     * 混在契约里会让用户去查「用例库版本」，方向完全错。
     */
    private String versionProblem(String pythonBin) {
        if (pythonBin == null) {
            return null;
        }
        return probe.probe(pythonBin, false).versionProblem();
    }

    private List<String> contractMissing(String pythonBin) {
        List<String> contractMissing = new ArrayList<>();
        if (pythonBin == null) {
            contractMissing.addAll(REQUIRED_OPTIONS);
            return contractMissing;
        }
        PytestContractProbe.ProbeResult result = probe.probe(pythonBin, false);
        if (!result.healthy()) {
            contractMissing.addAll(REQUIRED_OPTIONS);
            return contractMissing;
        }
        for (String option : REQUIRED_OPTIONS) {
            boolean ok = switch (option) {
                case "--mode" -> result.contract().supportsMode();
                case "--env_config" -> result.contract().supportsEnvConfig();
                default -> result.contract().supportsJunitXml();
            };
            if (!ok) {
                contractMissing.add(option);
            }
        }
        return contractMissing;
    }

    /**
     * 设备侧问题：缺管理 IP、未选设备、双端用例只给 1 台。
     *
     * <p>双端判定不靠用例标题猜，而是看脚本里有没有 {@code TRAFFIC_DIRECTION}
     * （实测双端用例都有、单端用例没有）。判不出来时按单端处理——
     * 宁可让用例自己在执行期报错，也不要因为猜错把合法执行挡在门外。
     */
    private List<String> deviceProblems(long projectId, List<Long> caseIds,
                                        List<String> hostKeys, Map<String, String> config) {
        List<String> problems = new ArrayList<>();
        String keyRule = PytestProjectSettings.value(config,
                PytestProjectSettings.INVENTORY_KEY_RULE);
        List<Device> all = devices.listAll(projectId);
        List<Device> selected;
        if (hostKeys == null || hostKeys.isEmpty()) {
            selected = all;
        } else {
            selected = new ArrayList<>();
            Set<String> wanted = new LinkedHashSet<>(hostKeys);
            for (Device device : all) {
                if (wanted.contains(device.inventoryKey(keyRule))) {
                    selected.add(device);
                }
            }
            if (selected.size() != wanted.size()) {
                problems.add("选中的主机在资产台账里不存在或不属于本项目");
            }
        }
        if (selected.isEmpty()) {
            problems.add("未选中任何设备");
            return problems;
        }
        for (Device device : selected) {
            if (device.managementIp() == null || device.managementIp().isBlank()) {
                problems.add("设备 " + device.name() + " 缺少管理 IP");
            }
        }
        if (selected.size() < 2 && needsPeer(projectId, caseIds, config)) {
            problems.add("选中了双端用例，但只给了 " + selected.size()
                    + " 台设备（双端用例需要两台：一收一发）");
        }
        return problems;
    }

    private boolean needsPeer(long projectId, List<Long> caseIds, Map<String, String> config) {
        Path repoRoot = PytestProjectSettings.repoPath(config);
        if (repoRoot == null) {
            return false;
        }
        Set<String> wanted;
        if (caseIds == null || caseIds.isEmpty()) {
            return false;
        }
        Map<Long, String> codeById = cases.idToCode(projectId);
        wanted = new LinkedHashSet<>();
        for (Long id : caseIds) {
            String code = codeById.get(id);
            if (code != null) {
                wanted.add(code);
            }
        }
        if (wanted.isEmpty()) {
            return false;
        }
        try {
            for (PytestScriptScanner.ScannedScript script : PytestScriptScanner.scan(repoRoot)) {
                if (wanted.contains(script.caseCode()) && script.requiresPeer()) {
                    return true;
                }
            }
        } catch (IOException e) {
            // 仓库不可读时不在这里判——由命令构造阶段给出更准确的错误。
            return false;
        }
        return false;
    }
}
