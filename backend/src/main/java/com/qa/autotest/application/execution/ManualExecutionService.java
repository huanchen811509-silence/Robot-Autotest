package com.qa.autotest.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.qa.autotest.application.repository.ManualExecutionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.ManualExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 手工执行（FR-v5.4-04 / R5–R6 / W54-04）。
 *
 * <p>核心约束只有一条，但它决定了整个功能有没有意义：<b>未确认的步骤不能提交结论</b>（R5）。
 * 没有这条，手工执行就退化成「填一个 PASS 就完事」，而步骤表存在与否毫无差别。
 *
 * <p>另外三条是刻意的「不做」：
 * <ul>
 *   <li>不占资源租约——人在现场，平台不该和环境互斥抢；</li>
 *   <li>不进执行队列——手工执行没有「排队」这个状态；</li>
 *   <li>不触发自动报告（R6）——手工记录进的是台账与分离统计，不产出对外报告。</li>
 * </ul>
 */
@Service
public class ManualExecutionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ManualExecutionRepository repository;

    public ManualExecutionService(ManualExecutionRepository repository) {
        this.repository = repository;
    }

    /** 提交结果。 */
    public record SubmitResult(boolean submitted, String reason, List<Integer> unconfirmedSteps,
                               ManualExecution execution) {
    }

    /**
     * 开始一次手工执行：建执行头 + 为每个步骤建「未确认」占位。
     *
     * <p>占位必须先建：只有先列出全部步骤，用户才知道「还有几步没确认」。
     * 若等到确认时才插入，未确认的步骤在页面上根本不存在，R5 就无从校验。
     *
     * @param stepNumbers 该用例的步骤号（来自 {@code test_case_step}，按 step_no 升序）
     */
    public ManualExecution start(long projectId, long runId, long caseId,
                                 List<Integer> stepNumbers, Long executorId, String executorName,
                                 String envNote) {
        if (stepNumbers == null || stepNumbers.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "该用例没有步骤，无法作为手工用例执行；请先在用例编辑里补步骤");
        }
        Instant now = Instant.now();
        List<ManualExecution.StepResult> steps = new ArrayList<>();
        for (Integer stepNo : stepNumbers) {
            steps.add(new ManualExecution.StepResult(stepNo, null, null, List.of(), null));
        }
        ManualExecution draft = new ManualExecution(null, projectId, runId, caseId, executorId,
                executorName, now, null, envNote, null, null, List.copyOf(steps), now, now);
        long id = repository.insert(draft, executorId);
        return repository.find(projectId, id).orElse(draft);
    }

    /**
     * 确认一步。
     *
     * <p>只允许确认「本来就存在」的步骤号：允许新增步骤等于允许绕过用例定义，
     * 那样手工执行记录就和用例台账对不上了。
     */
    public ManualExecution confirm(long projectId, long executionId, int stepNo, String verdict,
                                   String note, List<String> attachments) {
        ManualExecution execution = require(projectId, executionId);
        if (execution.submitted()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该执行已提交结论（" + execution.verdict() + "），不能再改步骤；"
                            + "需要修正请重新发起一次手工执行");
        }
        String normalized = ManualExecution.normalizeVerdict(verdict);
        if (normalized == null || !ManualExecution.VERDICTS.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单步结论必须是 " + String.join(" / ", ManualExecution.VERDICTS)
                            + "，收到 " + verdict);
        }
        boolean exists = execution.steps().stream()
                .anyMatch(step -> step.stepNo() == stepNo);
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "本次执行没有第 " + stepNo + " 步（步骤来自用例定义，不能临时加）");
        }
        ManualExecution.StepResult updated = new ManualExecution.StepResult(stepNo, normalized,
                note, attachments == null ? List.of() : List.copyOf(attachments), Instant.now());
        repository.updateStep(executionId, updated, null);
        return require(projectId, executionId);
    }

    /**
     * 提交结论。
     *
     * <p>两道校验：步骤全确认（R5）+ 结论合法。校验失败<b>不写任何东西</b>——
     * 半提交的手工执行比没提交更糟：它看起来「有结论」，但步骤是缺的。
     */
    public SubmitResult submit(long projectId, long executionId, String verdict, String note) {
        ManualExecution execution = require(projectId, executionId);
        if (execution.submitted()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该执行已提交结论（" + execution.verdict() + "），不能重复提交");
        }
        String normalized = ManualExecution.normalizeVerdict(verdict);
        if (normalized == null || !ManualExecution.VERDICTS.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "结论必须是 " + String.join(" / ", ManualExecution.VERDICTS)
                            + "，收到 " + verdict);
        }
        List<Integer> unconfirmed = execution.unconfirmedSteps();
        if (!unconfirmed.isEmpty()) {
            return new SubmitResult(false,
                    "还有 " + unconfirmed.size() + " 步未确认，不能提交结论（第 "
                            + join(unconfirmed) + " 步）", unconfirmed, execution);
        }
        Instant now = Instant.now();
        ManualExecution updated = new ManualExecution(execution.id(), execution.projectId(),
                execution.runId(), execution.caseId(), execution.executorId(),
                execution.executorName(), execution.startedAt(), now, execution.envNote(),
                normalized, note, execution.steps(), execution.createdAt(), now);
        repository.update(updated, null);
        return new SubmitResult(true, null, List.of(), updated);
    }

    public ManualExecution find(long projectId, long executionId) {
        return require(projectId, executionId);
    }

    public List<ManualExecution> listByRun(long projectId, long runId) {
        return repository.listByRun(projectId, runId);
    }

    /** 附件清单 → JSON（写库用）。只存路径与大小，不存内容。 */
    public static String attachmentsJson(List<String> paths) {
        ArrayNode array = JSON.createArrayNode();
        if (paths != null) {
            for (String path : paths) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                var node = array.addObject();
                node.put("path", path.trim());
            }
        }
        return array.toString();
    }

    /** JSON → 附件路径列表；坏数据当空列表，不让一条脏记录把查询打挂。 */
    public static List<String> parseAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            node.forEach(item -> {
                String path = item.path("path").asText(null);
                if (path != null && !path.isBlank()) {
                    out.add(path);
                }
            });
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private ManualExecution require(long projectId, long executionId) {
        Optional<ManualExecution> found = repository.find(projectId, executionId);
        return found.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "手工执行记录不存在：" + executionId));
    }

    private static String join(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(numbers.get(i));
        }
        return sb.toString();
    }
}
