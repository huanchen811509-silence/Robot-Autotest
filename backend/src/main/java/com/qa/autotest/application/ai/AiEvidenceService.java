package com.qa.autotest.application.ai;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.report.ReportSummaryService;
import com.qa.autotest.application.repository.FailureResultRepository;
import com.qa.autotest.application.repository.ReportRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.ai.AiEvidence;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI 证据装配（FR-3.3-02）。
 *
 * <p>只读取已按项目过滤的报告/失败/需求，不新建查询口径；数字全部来自既有
 * {@link ReportSummaryService}，保证「AI 说的数」与「报告显示的数」是同一个来源。
 * 失败文本会截断，避免把整段日志当成上下文，也避免把凭据类字段带进提示词。
 */
@Service
public class AiEvidenceService {

    private static final int FAILURE_MSG_MAX = 220;
    private static final int MAX_FAILURE_ITEMS = 4;
    private static final int REQUIREMENT_MAX = 1500;

    /** 运行证据包：证据条目 + 同一份摘要数字。 */
    public record RunEvidence(AiEvidence evidence, ReportSummaryService.Summary summary) {
    }

    private final ReportRepository reports;
    private final ReportSummaryService summaries;
    private final FailureResultRepository failures;
    private final AccessControlService access;

    public AiEvidenceService(ReportRepository reports, ReportSummaryService summaries,
                             FailureResultRepository failures, AccessControlService access) {
        this.reports = reports;
        this.summaries = summaries;
        this.failures = failures;
        this.access = access;
    }

    public RunEvidence forRun(long projectId, String runId) {
        access.bindProject(projectId);
        reports.find(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        ReportSummaryService.Summary summary = summaries.summarize(projectId, runId);

        List<AiEvidence.Item> items = new ArrayList<>();
        items.add(new AiEvidence.Item("run", runId, "运行 " + runId, "/reports/" + runId,
                runBody(runId, summary)));
        for (ReportSummaryService.TopFailure top : summary.topFailures()) {
            items.add(new AiEvidence.Item("failure-cluster", top.name(),
                    "失败簇 " + shortLabel(top.name()), "/runs/failure-clusters?runId=" + runId,
                    "出现 " + top.count() + " 次"));
        }
        List<FailureResultRepository.FailureRow> rows = failures.failedRows(projectId, runId);
        for (int i = 0; i < rows.size() && i < MAX_FAILURE_ITEMS; i++) {
            FailureResultRepository.FailureRow row = rows.get(i);
            String code = row.caseCode() == null ? ("结果#" + row.resultId()) : row.caseCode();
            items.add(new AiEvidence.Item("failure", code, "失败用例 " + code,
                    row.caseId() == null ? "/reports/" + runId : "/cases?focus=" + row.caseId(),
                    "判定 " + row.verdict() + "；" + truncate(row.failureMsg())));
        }
        for (String risk : summary.risks()) {
            items.add(new AiEvidence.Item("risk", "report", "报告风险", "/reports/" + runId,
                    truncate(risk)));
        }
        return new RunEvidence(new AiEvidence("run:" + runId, items), summary);
    }

    /** 需求起草只带需求文本本身；不回捞用例库，避免把既有用例伪装成新草稿。 */
    public AiEvidence forRequirement(long projectId, String requirement) {
        if (requirement == null || requirement.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "需求描述不能为空");
        }
        access.bindProject(projectId);
        List<AiEvidence.Item> items = List.of(new AiEvidence.Item("requirement", "input",
                "需求描述", "/cases", truncate(requirement.strip())));
        return new AiEvidence("requirement", items);
    }

    /**
     * 对话证据：只从用户输入里识别运行编号，不做模糊猜测。
     * 识别不到就返回空证据，让回答明确说「没有证据」，而不是编造。
     */
    public RunEvidence forPrompt(long projectId, String prompt) {
        String runId = extractRunId(prompt);
        if (runId == null) {
            return null;
        }
        try {
            return forRun(projectId, runId);
        } catch (BusinessException notFound) {
            return null;
        }
    }

    static String extractRunId(String prompt) {
        if (prompt == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("RUN-[0-9]{8,}[0-9A-Za-z-]*").matcher(prompt);
        return matcher.find() ? matcher.group() : null;
    }

    private static String runBody(String runId, ReportSummaryService.Summary summary) {
        return "运行 " + runId + " 结论 " + summary.conclusion()
                + "；总数 " + summary.total() + "，通过 " + summary.passed()
                + "，失败 " + summary.failed() + "，跳过 " + summary.skipped()
                + "，通过率 " + summary.passRate() + "%，耗时 " + summary.durationMs() + "ms";
    }

    private static String shortLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    private static String truncate(String value) {
        return truncate(value, FAILURE_MSG_MAX);
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\r', ' ').replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
