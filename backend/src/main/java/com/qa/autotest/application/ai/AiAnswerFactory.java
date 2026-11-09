package com.qa.autotest.application.ai;

import com.qa.autotest.application.report.ReportSummaryService;
import com.qa.autotest.domain.ai.AiAnswer;
import com.qa.autotest.domain.ai.AiCitation;
import com.qa.autotest.domain.ai.AiEvidence;
import com.qa.autotest.domain.ai.DraftCase;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 证据装配式回答（EW33-02 / EW33-04 / EW33-05 / EW33-06）。
 *
 * <p>不调用模型也能产出「有据可依」的结论：事实来自证据，推测显式标注，建议检查项不含
 * 自动改代码或自动提交。接入真实模型时此装配器作为降级路径（{@code degraded=true}），
 * 因此线上永远至少有一条可复核的回答路径。
 */
@Component
public class AiAnswerFactory {

    /** 失败分诊（FR-3.3-04）。 */
    public AiAnswer triage(String runId, AiEvidenceService.RunEvidence ctx) {
        ReportSummaryService.Summary s = ctx.summary();
        AiEvidence evidence = ctx.evidence();
        List<AiCitation> citations = evidence.citations();
        String runRef = refOf(evidence, "run");
        StringBuilder text = new StringBuilder();
        List<String> facts = new ArrayList<>();
        List<String> inferences = new ArrayList<>();

        facts.add("运行 " + runId + " 结论 " + s.conclusion() + "：总数 " + s.total()
                + "，通过 " + s.passed() + "，失败 " + s.failed() + "，跳过 " + s.skipped()
                + "，通过率 " + s.passRate() + "%");
        text.append("事实：\n- ").append(facts.get(0)).append("（").append(runRef).append("）\n");

        List<AiEvidence.Item> clusters = itemsOf(evidence, "failure-cluster");
        if (!clusters.isEmpty()) {
            text.append("- 主要失败特征：\n");
            for (int i = 0; i < clusters.size(); i++) {
                AiEvidence.Item cluster = clusters.get(i);
                String ref = refOf(evidence, cluster);
                facts.add("失败簇 " + cluster.ref() + " 出现 " + countOf(cluster) + " 次");
                text.append("  - ").append(cluster.ref()).append(" 出现 ")
                        .append(countOf(cluster)).append(" 次（").append(ref).append("）\n");
            }
        }
        List<AiEvidence.Item> failures = itemsOf(evidence, "failure");
        if (!failures.isEmpty()) {
            AiEvidence.Item first = failures.get(0);
            text.append("- 首条失败：").append(first.label()).append(" · ")
                    .append(first.body()).append("（").append(refOf(evidence, first))
                    .append("）\n");
        }
        if (s.failed() > 0) {
            String inference = "推测：上述失败簇可能指向同一共享依赖或数据前置，需人工复核，"
                    + "不作为根因结论";
            inferences.add(inference);
            text.append("\n").append(inference).append("。\n");
        }
        List<String> suggestions = List.of(
                "打开运行详情，确认失败环境、节点与凭据版本",
                "用首条失败用例复现最小路径，检查失败前后的日志差异",
                "核对失败簇涉及的共享依赖与测试数据是否一致",
                "如需对比，运行同一计划的上一轮并查看报告对比");
        text.append("\n建议检查项：\n");
        for (int i = 0; i < suggestions.size(); i++) {
            text.append(i + 1).append(". ").append(suggestions.get(i)).append('\n');
        }
        return new AiAnswer("GROUNDED", text.toString().stripTrailing(), facts, inferences,
                suggestions, citations, true);
    }

    /** 报告叙述（FR-3.3-05）：结论 + 风险 + 建议，数字全部来自证据。 */
    public AiAnswer narrative(AiEvidenceService.RunEvidence ctx) {
        ReportSummaryService.Summary s = ctx.summary();
        AiEvidence evidence = ctx.evidence();
        String runRef = refOf(evidence, "run");
        StringBuilder text = new StringBuilder();
        List<String> facts = new ArrayList<>();

        String verdict;
        if (s.total() == 0) {
            verdict = "本轮没有可判定的用例，不能给出发布结论";
        } else if (s.failed() == 0 && s.skipped() == 0) {
            verdict = "本轮全部通过，具备进入发布评审的条件";
        } else if (s.failed() == 0) {
            verdict = "本轮无失败但存在跳过的用例，属于有条件通过，需要说明跳过原因";
        } else {
            verdict = "本轮存在失败，不建议直接发布，需先定位或明确豁免";
        }
        facts.add("总数 " + s.total() + "，通过 " + s.passed() + "，失败 " + s.failed()
                + "，跳过 " + s.skipped() + "，通过率 " + s.passRate() + "%");
        text.append("结论：").append(verdict).append("。数据：").append(facts.get(0))
                .append("（").append(runRef).append("）。\n");

        List<AiEvidence.Item> risks = itemsOf(evidence, "risk");
        text.append("\n主要风险：\n");
        if (s.failed() > 0) {
            AiEvidence.Item firstFailure = firstOf(evidence, "failure");
            text.append("- 存在 ").append(s.failed()).append(" 例失败");
            if (firstFailure != null) {
                text.append("，首条为 ").append(firstFailure.label()).append("（")
                        .append(refOf(evidence, firstFailure)).append("）");
            }
            text.append('\n');
        }
        if (s.skipped() > 0) {
            text.append("- 存在 ").append(s.skipped()).append(" 例跳过，未覆盖结论（")
                    .append(runRef).append("）\n");
        }
        for (AiEvidence.Item risk : risks) {
            text.append("- ").append(risk.body()).append("（")
                    .append(refOf(evidence, risk)).append("）\n");
        }
        if (s.failed() == 0 && s.skipped() == 0 && risks.isEmpty()) {
            text.append("- 未发现报告层面的风险项（").append(runRef).append("）\n");
        }
        List<String> suggestions = new ArrayList<>();
        if (s.failed() > 0) {
            suggestions.add("先处理失败用例或记录豁免理由，再进入发布评审");
        }
        if (s.skipped() > 0) {
            suggestions.add("逐条说明跳过原因，并确认是否需要补跑");
        }
        suggestions.add("将本段叙述作为报告附注，正文数字仍以报告明细为准");
        text.append("\n建议动作：\n");
        for (int i = 0; i < suggestions.size(); i++) {
            text.append(i + 1).append(". ").append(suggestions.get(i)).append('\n');
        }
        return new AiAnswer("GROUNDED", text.toString().stripTrailing(), facts,
                List.of("推测：风险项与失败簇之间的因果关系需人工确认，本段只做事实汇总"),
                suggestions, evidence.citations(), true);
    }

    /** 用例草稿（FR-3.3-06）：只产出草稿，强制审阅，不自动保存。 */
    public AiAnswer caseDraft(AiEvidence evidence, String module, int count) {
        List<DraftCase> drafts = draftCases(module, count);
        String ref = refOf(evidence, "requirement");
        StringBuilder text = new StringBuilder();
        text.append("草稿（必须人工审阅后才能保存）：\n\n");
        text.append("| # | 用例 | 前置 | 步骤 | 预期 | 来源 |\n");
        text.append("|---|------|------|------|------|------|\n");
        for (int i = 0; i < drafts.size(); i++) {
            DraftCase draft = drafts.get(i);
            text.append("| ").append(i + 1).append(" | ").append(draft.title()).append(" | ")
                    .append(draft.precondition()).append(" | ").append(draft.steps()).append(" | ")
                    .append(draft.expected()).append(" | ").append(ref).append(" |\n");
        }
        text.append("\n注意：草稿不包含真实执行证据，保存前必须由用例负责人逐条确认。");
        List<String> facts = List.of("需求来源： " + evidence.items().get(0).body());
        return new AiAnswer("DRAFT", text.toString(), facts, List.of(), List.of(
                "逐条确认前置条件与预期结果", "补充项目实际的节点与凭据信息"),
                evidence.citations(), true);
    }

    /** 结构化草稿：前端可编辑后再走「审阅后保存」，与 Markdown 展示同源。 */
    public List<DraftCase> draftCases(String module, int count) {
        int size = Math.max(1, Math.min(count, 10));
        String name = module == null || module.isBlank() ? "本次需求" : module.strip();
        String[][] templates = {
                {"主流程", "项目与执行节点可用", "按需求主路径执行一次", "结果与需求描述一致"},
                {"必填校验", "表单可编辑", "留空必填项并提交", "提示缺失字段且不写入"},
                {"边界值", "功能可用", "使用最小/最大边界值执行", "边界值被正确处理"},
                {"异常路径", "依赖可模拟失败", "模拟下游超时/错误返回", "错误可读且可重试"},
                {"权限", "准备只读账号", "用只读账号访问变更入口", "入口不可用并返回 403"},
                {"重复提交", "表单可编辑", "快速双击提交", "只产生一条记录"},
                {"取消/中断", "任务可运行", "运行中取消或刷新页面", "状态一致且无残留"},
                {"数据隔离", "准备两个项目", "在 A 项目访问 B 项目数据", "不可见且不可改"},
                {"可观测", "日志可查看", "制造一次失败", "日志含可定位的错误信息"},
                {"回归", "存在既有用例", "执行关联回归集", "无新增失败"}};
        List<DraftCase> drafts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String[] row = templates[i % templates.length];
            drafts.add(new DraftCase(name + " - " + row[0], row[1], row[2], row[3], "E1"));
        }
        return List.copyOf(drafts);
    }

    /** 对话（FR-3.3-01）：有证据才回答；无证据时明确说明并给出补全上下文的方式。 */
    public AiAnswer chat(AiEvidenceService.RunEvidence ctx, String prompt) {
        if (ctx == null) {
            String text = "当前上下文没有可用证据，无法给出有据结论。\n"
                    + "请在问题里附带运行编号（RUN-…）、用例编号或报告链接，"
                    + "我会基于报告与失败明细回答。";
            return new AiAnswer("NO_EVIDENCE", text, List.of(), List.of(), List.of(
                    "补充运行编号后重试", "或从报告页直接发起分诊"), List.of(), true);
        }
        String subject = ctx.evidence().subject();
        String runId = subject.startsWith("run:") ? subject.substring(4) : subject;
        AiAnswer triage = triage(runId, ctx);
        String prefix = prompt == null || prompt.isBlank() ? "" : "问题：" + prompt.strip() + "\n\n";
        return new AiAnswer("GROUNDED", prefix + triage.text(), triage.facts(),
                triage.inferences(), triage.suggestions(), triage.citations(), true);
    }

    private static List<AiEvidence.Item> itemsOf(AiEvidence evidence, String type) {
        return evidence.items().stream().filter(item -> type.equals(item.type())).toList();
    }

    private static AiEvidence.Item firstOf(AiEvidence evidence, String type) {
        return evidence.items().stream().filter(item -> type.equals(item.type()))
                .findFirst().orElse(null);
    }

    private static String refOf(AiEvidence evidence, String type) {
        AiEvidence.Item item = firstOf(evidence, type);
        return item == null ? "" : refOf(evidence, item);
    }

    static String refOf(AiEvidence evidence, AiEvidence.Item item) {
        int index = evidence.items().indexOf(item);
        return "[" + AiEvidence.idOf(Math.max(index, 0)) + "]";
    }

    private static int countOf(AiEvidence.Item cluster) {
        var matcher = java.util.regex.Pattern.compile("\\d+").matcher(cluster.body());
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }
}
