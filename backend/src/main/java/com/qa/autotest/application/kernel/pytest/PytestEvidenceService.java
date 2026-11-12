package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.PytestCaseStepRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.PytestCaseStep;
import com.qa.autotest.domain.execution.RunRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pytest 步骤证据编排（FR-v5.1-08 / W51-12）。
 *
 * <p>三步：<b>定位</b>（junit 在 run 产物目录、命令证据在框架 artifacts 目录）→
 * <b>抽取</b>（{@link PytestStepExtractor}）→ <b>入库</b>（按 run 覆盖写，幂等）。
 *
 * <p><b>两处证据的根不一样，这是刻意的</b>：
 * <ul>
 *   <li>junit 由平台放进 {@code run.artifacts_dir}（我们传给 pytest 的 {@code --junitxml=}）；</li>
 *   <li>命令证据由框架写在 {@code <repo>/artifacts/<run_id>/cases/<node>/raw}——框架把
 *       「证据一律落在项目根」写死在 {@code Runtime} 里（{@code artifact_root="artifacts"}，
 *       且不读环境变量），所以只能按它的规矩去取，见
 *       {@link PytestNicKernelCommand#artifactRoot(Path, String)}。</li>
 * </ul>
 *
 * <p><b>不猜</b>：junit 里没有捕获文本就如实返回 {@code captured=false} 与一条 hint，
 * 不拿 framework.log 去按顺序拼一棵「看起来对」的树。会话级日志没有用例边界，
 * 靠顺序对齐在重试/并行下必然错位——错的证据比没有证据更糟。
 */
@Service
public class PytestEvidenceService {

    /** 框架把 nodeid 变成目录名的规则，与 {@code Runtime.begin_case} 的 re.sub 逐字对应。 */
    private static final String NODE_ID_SANITIZER = "[^A-Za-z0-9_.-]+";

    private final RunRepository runs;
    private final PytestCaseStepRepository steps;
    private final PytestStepExtractor extractor;
    private final ProjectSettingRepository settings;

    public PytestEvidenceService(RunRepository runs,
                                 PytestCaseStepRepository steps,
                                 PytestStepExtractor extractor,
                                 ProjectSettingRepository settings) {
        this.runs = runs;
        this.steps = steps;
        this.extractor = extractor;
        this.settings = settings;
    }

    /** 抽取结果。{@code hints} 是给人看的排查方向，正常情况下为空。 */
    public record ExtractResult(String runId,
                                long runDbId,
                                int cases,
                                int stepCount,
                                int commandCount,
                                int capturedCases,
                                int unboundCases,
                                boolean captured,
                                String junitSource,
                                String commandRoot,
                                List<String> hints) {
    }

    /**
     * 抽取并入库。
     *
     * @param includeCommands 是否一并读框架的命令证据（读盘，可能慢；页面首次可先只要步骤树）
     */
    @Transactional
    public ExtractResult extract(long projectId, String runId, boolean includeCommands) {
        RunRecord run = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "运行记录不存在或不属于当前项目: " + runId));
        List<String> hints = new ArrayList<>();
        List<Path> junitFiles = locateJunit(run, hints);
        Map<String, String> config = PytestProjectSettings.load(settings, projectId);
        Path repoPath = PytestProjectSettings.repoPath(config);
        Path commandRoot = repoPath == null ? null
                : PytestNicKernelCommand.artifactRoot(repoPath, runId);
        if (repoPath == null) {
            hints.add("未配置 pytest.repo_path：命令证据无法定位（步骤树不受影响）");
        }

        List<PytestStepExtractor.CaseSteps> cases = extractor.fromJunit(junitFiles);
        Map<String, Long> caseKeyToId = steps.testResultIdsByCaseKey(run.id());
        Map<String, Long> codeToId = byCaseCode(caseKeyToId);

        List<PytestCaseStep> rows = new ArrayList<>();
        int capturedCases = 0;
        int commandCount = 0;
        int unbound = 0;
        for (PytestStepExtractor.CaseSteps item : cases) {
            if (item.captured()) {
                capturedCases++;
            }
            Long testResultId = null;
            if (item.matchKey() != null && item.matchKey().contains("::")) {
                // 先按 matchKey 直接命中（同源时），再退到「用例库编号」命中（报告侧口径）。
                testResultId = caseKeyToId.get(item.matchKey());
                if (testResultId == null) {
                    testResultId = codeToId.get(item.matchKey().substring(
                            item.matchKey().indexOf("::") + 2));
                }
            }
            if (testResultId == null) {
                unbound++;
            }
            for (PytestStepExtractor.Step step : item.steps()) {
                rows.add(PytestCaseStep.draft(run.id(), testResultId, item.nodeId(),
                        item.matchKey(), step.seq(), step.kind(), step.label(), step.content(),
                        step.status(), PytestStepExtractor.KIND_COMMAND.equals(step.kind())
                                ? step.label() : null,
                        step.attachment(), step.logLine(), "JUNIT"));
            }
            if (includeCommands && commandRoot != null) {
                Path raw = commandRoot.resolve("cases").resolve(sanitize(item.nodeId())).resolve("raw");
                int base = item.steps().size();
                List<PytestStepExtractor.Step> commands = extractor.commandsOf(raw, base + 1);
                commandCount += commands.size();
                for (PytestStepExtractor.Step step : commands) {
                    rows.add(PytestCaseStep.draft(run.id(), testResultId, item.nodeId(),
                            item.matchKey(), step.seq(), step.kind(), step.label(), step.content(),
                            step.status(), step.label(), step.attachment(), step.logLine(),
                            "COMMANDS_JSONL"));
                }
            }
        }

        if (!cases.isEmpty() && capturedCases == 0) {
            hints.add("本次 junit 里没有任何用例捕获文本：检查用例库 addopts 的 -s 是否被文件覆盖，"
                    + "平台侧必须以 --capture=fd 覆盖它才能拿到按用例切好的日志");
        }
        if (cases.isEmpty()) {
            hints.add("没有解析到任何用例：确认 junit 文件存在且是用例库真实执行产生的");
        }
        if (unbound > 0) {
            hints.add(unbound + " 个用例的步骤未能挂到报告行（用例库编号与 test_result 对不上），"
                    + "步骤本身仍已入库，可凭 nodeid 查询");
        }

        int written = steps.replace(run.id(), rows);
        String source = junitFiles.isEmpty() ? null : junitFiles.get(0).toString();
        return new ExtractResult(runId, run.id(), cases.size(), written, commandCount,
                capturedCases, unbound, capturedCases > 0, source,
                commandRoot == null ? null : commandRoot.toString(), List.copyOf(hints));
    }

    /** 查询某 run 已入库的证据（按 nodeId + seq 排序）。 */
    public List<PytestCaseStep> list(long projectId, String runId) {
        RunRecord run = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "运行记录不存在或不属于当前项目: " + runId));
        return steps.listByRun(run.id());
    }

    /**
     * 逐用例归因（W52-09 / FR-v5.2-06）。
     *
     * <p>只用**已入库的证据**，不重新读盘：归因结果要能复算，而磁盘上的产物会被归档/清理。
     * 分类由 {@code FailureAttributor} 的确定性规则给出，不调 AI——分类要进统计与环境分流，
     * 每天漂一点的指标没有意义。
     */
    public List<CaseAttribution> attribute(long projectId, String runId) {
        return attribute(list(projectId, runId));
    }

    /**
     * 纯函数部分：证据行 → 归因。抽出来是为了能用手造的证据行直接测，
     * 不必搭 RunRepository / JdbcTemplate 的桩——分组逻辑最容易写错，
     * 而它恰恰不需要数据库才能验证。
     */
    public static List<CaseAttribution> attribute(List<PytestCaseStep> rows) {
        Map<String, List<PytestCaseStep>> grouped = new LinkedHashMap<>();
        if (rows != null) {
            for (PytestCaseStep row : rows) {
                grouped.computeIfAbsent(row.nodeId(), key -> new ArrayList<>()).add(row);
            }
        }
        List<CaseAttribution> result = new ArrayList<>();
        for (Map.Entry<String, List<PytestCaseStep>> entry : grouped.entrySet()) {
            List<PytestCaseStep> caseSteps = entry.getValue();
            boolean anyFail = caseSteps.stream().anyMatch(s -> STATUS_FAIL.equals(s.status()));
            boolean allSkip = !caseSteps.isEmpty() && caseSteps.stream()
                    .filter(s -> s.status() != null)
                    .allMatch(s -> "SKIP".equals(s.status()));
            String verdict = anyFail ? "FAIL" : allSkip ? "SKIP" : "PASS";
            // 失败原文优先取 FAILURE 行（junit 的 failure message），其次取 FAIL 判定行。
            String message = lastContent(caseSteps, PytestStepExtractor.KIND_FAILURE);
            if (message == null) {
                message = lastContent(caseSteps, PytestStepExtractor.KIND_VERDICT);
            }
            // 传「全部步骤内容」而不是只传失败步：Plan-only 的判据（步骤里有没有 plan-only 字样）
            // 恰恰落在 SKIP 明细行上，只过滤失败步就永远看不到它。
            List<String> stepContents = caseSteps.stream()
                    .map(PytestCaseStep::content)
                    .filter(content -> content != null && !content.isBlank())
                    .toList();
            com.qa.autotest.application.report.FailureAttributor.Attribution attribution =
                    com.qa.autotest.application.report.FailureAttributor.of(
                            "pytest", verdict, message, stepContents);
            result.add(new CaseAttribution(entry.getKey(), caseSteps.get(0).caseKey(), verdict,
                    attribution));
        }
        return List.copyOf(result);
    }

    /** 单用例归因结果：nodeId + 用例库编号 + 结论 + 五层。 */
    public record CaseAttribution(String nodeId,
                                  String caseKey,
                                  String verdict,
                                  com.qa.autotest.application.report.FailureAttributor.Attribution
                                          attribution) {
    }

    private static final String STATUS_FAIL = "FAIL";

    private static String lastContent(List<PytestCaseStep> steps, String kind) {
        String found = null;
        for (PytestCaseStep step : steps) {
            if (kind.equals(step.kind()) && step.content() != null && !step.content().isBlank()) {
                found = step.content();
            }
        }
        return found;
    }

    /**
     * junit 文件定位：{@code run.artifacts_dir} 下的 {@code junit*.xml}。
     *
     * <p>按文件名自然序（{@code junit.xml} / {@code junit-0.xml} / {@code junit-1.xml}），
     * 保证批次顺序与执行顺序一致——步骤行号依赖顺序，乱序遍历会让行号对不上原文。
     */
    private List<Path> locateJunit(RunRecord run, List<String> hints) {
        String dir = run.artifactsDir();
        if (dir == null || dir.isBlank()) {
            hints.add("run 没有产物目录记录（artifacts_dir 为空）");
            return List.of();
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            hints.add("产物目录不存在: " + root);
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("junit") && name.endsWith(".xml");
            }).sorted().forEach(files::add);
        } catch (IOException e) {
            hints.add("产物目录不可读: " + root + " — " + e.getMessage());
        }
        return List.copyOf(files);
    }

    /** {@code <路径>.<用例库编号>} → 编号；编号为空或重复的条目跳过（重复由绑定机检报出）。 */
    private static Map<String, Long> byCaseCode(Map<String, Long> caseKeyToId) {
        Map<String, Long> result = new LinkedHashMap<>();
        caseKeyToId.forEach((caseKey, id) -> {
            if (caseKey == null || caseKey.isBlank()) {
                return;
            }
            int dot = caseKey.lastIndexOf('.');
            String code = dot < 0 ? caseKey : caseKey.substring(dot + 1);
            if (!code.isBlank()) {
                result.putIfAbsent(code, id);
            }
        });
        return result;
    }

    /** 复刻 {@code Runtime.begin_case} 的 {@code re.sub(r"[^A-Za-z0-9_.-]+", "_", nodeid)}。 */
    static String sanitize(String nodeId) {
        return nodeId == null ? "" : nodeId.replaceAll(NODE_ID_SANITIZER, "_");
    }
}
