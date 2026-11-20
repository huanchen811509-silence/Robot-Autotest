package com.qa.autotest.application.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.kernel.pytest.PytestProjectSettings;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * W-EHT-07：消费用例库的 fake 执行计划（{@code artifacts/<run_id>/fake_plan.json}）。
 *
 * <p>此前平台只写路径、从不读取，导致 fake 模式"没连设备、没下发命令"这个事实
 * 在报告里完全看不见——读者容易把 Plan-only 的 SKIP 当成"执行了但没通过"。
 *
 * <p>数据源头在用例库：{@code RunPlanRecorder.write()} 写出
 * {@code mode / connection_established / stdout_synthesized / commands[] / alternative_branches[]}。
 * 字段名逐字对齐，平台这边不做二次猜测；读不到就是"未生成执行计划"，不编造。
 *
 * <p>安全边界：{@code runId} 参与拼路径，必须挡住 {@code /} 与 {@code ..}——
 * 该值来自 URL，直接拼接等于把服务端任意文件读能力开放给调用方。
 */
@Component
public class PytestPlanEvidenceService {

    private static final int MAX_COMMANDS = 50;
    private static final int MAX_BRANCHES = 20;

    private final ProjectSettingRepository settings;
    private final ObjectMapper mapper;

    public PytestPlanEvidenceService(ProjectSettingRepository settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    /** 读该 run 的执行计划；任何异常/缺失都降级为 {@link EhtSections.Plan#absent}，不阻断报告。 */
    public EhtSections.Plan read(long projectId, String runId) {
        if (!safeRunId(runId)) {
            return EhtSections.Plan.absent("RUN_ID 非法，未读取执行计划");
        }
        Path repo;
        try {
            Map<String, String> config = PytestProjectSettings.load(settings, projectId);
            repo = PytestProjectSettings.repoPath(config);
        } catch (RuntimeException ex) {
            return EhtSections.Plan.absent("读取工程配置失败：" + safeMessage(ex));
        }
        if (repo == null) {
            return EhtSections.Plan.absent("工程未配置用例库路径，未读取执行计划");
        }
        Path file = repo.resolve("artifacts").resolve(runId).resolve("fake_plan.json");
        if (!Files.isRegularFile(file)) {
            // real 模式本来就不生成计划——这不是错误，要说清楚，别让报告留空白。
            return EhtSections.Plan.absent("未生成执行计划（real 模式，或该 run 未启用 fake 计划记录）");
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return EhtSections.Plan.absent("执行计划文件无法解析：" + safeMessage(ex));
        }
    }

    private EhtSections.Plan parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        List<EhtSections.Plan.PlanCommand> commands = new ArrayList<>();
        JsonNode commandNodes = root.path("commands");
        if (commandNodes.isArray()) {
            for (JsonNode node : commandNodes) {
                if (commands.size() >= MAX_COMMANDS) {
                    break;
                }
                commands.add(new EhtSections.Plan.PlanCommand(
                        text(node, "endpoint_key"), text(node, "command"),
                        text(node, "purpose"), text(node, "expected_observation")));
            }
        }
        List<EhtSections.Plan.PlanBranch> branches = new ArrayList<>();
        JsonNode branchNodes = root.path("alternative_branches");
        if (branchNodes.isArray()) {
            for (JsonNode node : branchNodes) {
                if (branches.size() >= MAX_BRANCHES) {
                    break;
                }
                // 用例库写的是 {case_id, action, target{}, branch{name, condition, expected_result}}，
                // 展示层只取人能复核的三项，target 结构随用例变化，不做归一化。
                JsonNode branch = node.path("branch");
                branches.add(new EhtSections.Plan.PlanBranch(
                        text(node, "case_id"), text(node, "action"),
                        text(branch, "name"), text(branch, "condition"),
                        text(branch, "expected_result")));
            }
        }
        return new EhtSections.Plan(true,
                text(root, "mode"),
                bool(root, "connection_established"),
                bool(root, "stdout_synthesized"),
                List.copyOf(commands), List.copyOf(branches), null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() || value.isNumber() ? value.asText() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    /** 只允许单段目录名：挡住路径分隔符与上跳。 */
    static boolean safeRunId(String runId) {
        return runId != null && !runId.isBlank()
                && !runId.contains("/") && !runId.contains("\\") && !runId.contains("..");
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String safe = ReportViewRenderer.scrub(msg);
        return safe.length() > 120 ? safe.substring(0, 120) + "…" : safe;
    }
}
