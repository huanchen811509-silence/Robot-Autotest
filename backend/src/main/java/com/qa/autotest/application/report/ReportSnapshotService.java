package com.qa.autotest.application.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ReportCaseRow;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 快照物化（RPTX-02 / TPLX-02）。
 *
 * <p>刻意不依赖 run 表：把源 run 的结论、计数、metadata 白名单全部写进 JSON，
 * 冻结之后即便源 run 被物理删除，{@code snapshot_json} 仍能独立渲染（G8 第 5 条）。
 * hash 覆盖 JSON 与 HTML，任何事后改动都能被发现。
 *
 * <p><b>模板也写进快照</b>（TPLX-02）：{@code template} 节点存 id / 版本 / 名称 / 字段清单，
 * 渲染只读这里。若渲染时去查当前模板，用户改一次模板就把所有历史已冻结报告的字段集合改了，
 * 「冻结」这件事就不成立了。
 */
@Service
public class ReportSnapshotService {

    private final ObjectMapper mapper;

    public ReportSnapshotService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 不绑模板的快照（历史口径 / 测试用）：渲染时回落到全量白名单顺序。 */
    public ReportSnapshot build(ReportRecord source, int version) {
        return build(source, version, null);
    }

    public ReportSnapshot build(ReportRecord source, int version, ReportTemplate template) {
        Map<String, Object> root = baseRoot(source, version, template);
        String json = write(root);
        String html = html(root);
        return new ReportSnapshot(json, html, sha256(json + "\n" + html), version,
                summary(source, template));
    }

    /**
     * v5.14.0 / W64-14：三段式快照。只有显式传入 {@link EhtSections} 才走新 HTML，
     * 旧的 2 参 / 3 参重载仍是原平铺 {@code <dl>} —— 既有历史报告与单测零回归。
     *
     * <p>{@code eht} 节点写进 snapshot_json（快照自包含），但渲染器只按 C5 白名单
     * 挑字段，三段式数据不会流进对外字段视图，也不影响 {@code snapshot_hash} 的口径
     * （hash 本来就是 json + html，新报告有新事实，理应有新 hash）。
     */
    public ReportSnapshot build(ReportRecord source, int version, ReportTemplate template,
                                EhtSections sections) {
        Map<String, Object> root = baseRoot(source, version, template);
        root.put("eht", sections);
        String json = write(root);
        String html = sections == null ? html(root) : threeSections(source, version, sections);
        return new ReportSnapshot(json, html, sha256(json + "\n" + html), version,
                summary(source, template));
    }

    /**
     * v5.22.0 / FR-v5.22-02（W72-03）：带用例明细的快照。
     *
     * <p>明细写进根键 {@code cases}，导出时**不再回查 {@code test_result}**：
     * 报告的生命周期比 run 长（保留策略会清 run），冻结件必须能独立交付。
     *
     * <p>三种状态刻意可区分：
     * <ul>
     *   <li>{@code cases == null} → **不写该键**（历史报告 / 调用方未采集），导出时说「未记录明细」；</li>
     *   <li>{@code cases.isEmpty()} → 写空数组，导出时说「本次执行没有用例级记录」；</li>
     *   <li>有数据 → 逐条写入。</li>
     * </ul>
     * 把「没采集」和「采集到 0 条」混成一个空数组，是最容易在对外文件里撒谎的一种合并。
     *
     * <p><b>凭据闸</b>：{@code failureMsg} / {@code commandText} 命中凭据形态（口令 / token / key 赋值）
     * 时**拒绝冻结**，不做「遮蔽后照发」——凭据类不是「涂黑一下还能给」的东西，
     * 遮蔽规则漏一种写法就是一次泄漏。命中信息只报用例编号，不回显命中内容。
     */
    public ReportSnapshot build(ReportRecord source, int version, ReportTemplate template,
                                EhtSections sections, List<ReportCaseRow> cases) {
        if (cases != null) {
            rejectCredentialBearingCases(cases);
        }
        Map<String, Object> root = baseRoot(source, version, template);
        root.put("eht", sections);
        if (cases != null) {
            root.put("cases", caseNodes(cases));
        }
        String json = write(root);
        String html = sections == null ? html(root) : threeSections(source, version, sections);
        return new ReportSnapshot(json, html, sha256(json + "\n" + html), version,
                summary(source, template));
    }

    /**
     * 凭据形态：与 {@code ReportViewRenderer.CREDENTIAL} 同口径（那边负责「渲染前遮蔽」，
     * 这里负责「冻结前拒绝」）。两处分开写而不是共用，是因为处置动作相反：
     * 一边是redact，一边是 fail-closed。
     */
    private static final java.util.regex.Pattern CREDENTIAL = java.util.regex.Pattern.compile(
            "(?i)(password|passwd|api[_-]?key|secret|token|authorization)\\s*[:=]\\s*\\S+");

    private static void rejectCredentialBearingCases(List<ReportCaseRow> cases) {
        for (ReportCaseRow row : cases) {
            if (hitsCredential(row.failureMsg()) || hitsCredential(row.commandText())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "报告明细含凭据形态，拒绝冻结（用例 " + row.caseCode() + "）；"
                                + "请从执行记录中清除凭据后重新执行");
            }
        }
    }

    private static boolean hitsCredential(String text) {
        return text != null && CREDENTIAL.matcher(text).find();
    }

    private static List<Map<String, Object>> caseNodes(List<ReportCaseRow> cases) {
        List<Map<String, Object>> out = new ArrayList<>(cases.size());
        for (ReportCaseRow row : cases) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("caseCode", blankToEmpty(row.caseCode()));
            node.put("name", blankToEmpty(row.name()));
            node.put("verdict", blankToEmpty(row.verdict()));
            node.put("durationMs", row.durationMs());
            node.put("failureMsg", blankToEmpty(row.failureMsg()));
            node.put("commandText", blankToEmpty(row.commandText()));
            out.add(node);
        }
        return out;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> baseRoot(ReportRecord source, int version,
                                         ReportTemplate template) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", source.runId());
        root.put("projectId", source.projectId());
        root.put("state", source.state());
        root.put("exportable", source.exportable());
        root.put("passCount", source.passCount());
        root.put("failCount", source.failCount());
        root.put("skipCount", source.skipCount());
        root.put("caseTotal", source.passCount() + source.failCount() + source.skipCount());
        root.put("passRate", rate(source));
        // W57-50：结论按判据汇总冻结进快照，渲染期不再回查 test_result——
        // 冻结之后源数据被重跑覆盖，也不该改历史报告的口径。
        root.put("criteriaConclusion", source.effectiveConclusion());
        root.put("criteriaEvaluated", source.criteriaEvaluated());
        root.put("criteriaMissingCount", source.criteriaMissingCount());
        root.put("metadataMissing", source.metadataMissing());
        root.put("metadata", ReportViewRenderer.safeMetadata(source.metadata()));
        root.put("startedAt", source.startedAt() == null ? null : source.startedAt().toString());
        root.put("finishedAt", source.finishedAt() == null ? null : source.finishedAt().toString());
        root.put("version", version);
        root.put("templateId", template == null ? null : template.id());
        root.put("templateVersion", template == null ? null : template.version());
        root.put("template", templateNode(template));
        return root;
    }

    /**
     * 三段式 HTML：结论 / 证据 / 附录。
     *
     * <p>两条硬约束：① 不用 {@code <style>} 块、不用 {@code on*} 属性 ——
     * {@code ReportHtmlSanitizer} 会整块删 style，写了等于没写；
     * ② 目录用 {@code href="#..."} 站内锚点，iframe {@code sandbox=""} 里
     * 同文档 fragment 跳转不需要脚本，也不需要 allow-same-origin。
     * 所有外部值都经 escape（字段值已在装配期 scrub 脱敏）。
     */
    private static String threeSections(ReportRecord source, int version, EhtSections s) {
        StringBuilder b = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>Test Report</title></head><body><h1>Test Report</h1>"
                + "<nav id=\"toc\"><a href=\"#conclusion\">一、结论层</a> · "
                + "<a href=\"#evidence\">二、证据层</a> · "
                + "<a href=\"#appendix\">三、附录</a></nav>");

        // 一、结论层
        EhtSections.Counts c = s.counts();
        b.append("<section id=\"conclusion\"><h2>一、结论层</h2><dl>")
                .append("<dt>本次执行结论</dt><dd>").append(esc(source.effectiveConclusion())).append("</dd>")
                .append("<dt>通过</dt><dd>").append(c.passed()).append("</dd>")
                .append("<dt>不通过</dt><dd>").append(c.failed()).append("</dd>")
                .append("<dt>缺证据</dt><dd>").append(c.missingEvidence()).append("</dd>")
                .append("<dt>未判定</dt><dd>").append(c.undetermined()).append("</dd>")
                .append("<dt>用例总数</dt><dd>").append(c.total()).append("</dd>")
                .append("<dt>四态合计校验</dt><dd>")
                .append(c.balanced() ? "一致（" + c.sum() + " = " + c.total() + "）"
                        : "不一致（" + c.sum() + " ≠ " + c.total() + "）")
                .append("</dd></dl>");
        b.append("<h3>判据未定项清单</h3>");
        if (s.undetermined().isEmpty()) {
            b.append("<p>无</p>");
        } else {
            b.append("<ul>");
            for (EhtSections.Undetermined item : s.undetermined()) {
                b.append("<li>").append(esc(item.nodeRef())).append(" — ")
                        .append(esc(item.reason())).append("</li>");
            }
            b.append("</ul>");
        }
        EhtSections.Diff d = s.diff();
        b.append("<h3>与上次同工程执行的差异</h3>");
        if (!d.hasBaseline()) {
            b.append("<p>无上次执行</p>");
        } else {
            b.append("<dl><dt>基线 RUN_ID</dt><dd>").append(esc(d.baselineRunId())).append("</dd>")
                    .append("<dt>新增失败</dt><dd>").append(d.newFail()).append("</dd>")
                    .append("<dt>修复</dt><dd>").append(d.newPass()).append("</dd>")
                    .append("<dt>仍失败</dt><dd>").append(d.bothFail()).append("</dd>")
                    .append("<dt>本轮未执行</dt><dd>").append(d.notRun()).append("</dd></dl>");
        }
        b.append("</section>");

        // 二、证据层
        b.append("<section id=\"evidence\"><h2>二、证据层</h2>");
        if (!"pytest".equalsIgnoreCase(s.kernel())) {
            // FR-09 判据 5：RF 没有判据载体，明确写「不适用」，不许留空列装样子。
            b.append("<p>不适用（RF 判据载体未建立）</p>");
        } else if (s.evidence().isEmpty()) {
            b.append("<p>本次执行没有用例级证据记录</p>");
        } else {
            b.append("<dl>");
            for (EhtSections.Evidence row : s.evidence()) {
                b.append("<dt>").append(esc(row.nodeRef())).append("</dt><dd>")
                        .append("判据行原文：").append(esc(row.criteriaText()))
                        .append("<br>实测值：").append(esc(row.measuredValue()))
                        .append("<br>证据文件：");
                String path = row.evidencePath();
                if (path == null || path.isBlank()) {
                    b.append("未提供");
                } else {
                    b.append("<a href=\"").append(esc(path)).append("\">")
                            .append(esc(path)).append("</a>");
                }
                b.append("</dd>");
            }
            b.append("</dl>");
        }
        b.append("</section>");

        // 三、附录
        b.append("<section id=\"appendix\"><h2>三、附录</h2><dl>");
        for (String key : s.metadataRequired()) {
            String value = s.metadata().get(key);
            b.append("<dt>").append(esc(key)).append("</dt><dd>")
                    .append(value == null || value.isBlank() ? "未提供" : esc(value))
                    .append("</dd>");
        }
        b.append("<dt>执行内核</dt><dd>").append(esc(s.kernel())).append("</dd>")
                .append("<dt>插件版本</dt><dd>未采集</dd>")
                .append("<dt>仓库 commit</dt><dd>")
                .append(s.commitSha() == null || s.commitSha().isBlank() ? "未采集" : esc(s.commitSha()))
                .append("</dd>")
                .append("<dt>快照引用</dt><dd>").append(esc(source.runId()))
                .append("@v").append(version).append("</dd>")
                .append("</dl></section></body></html>");
        return b.toString();
    }

    private static String esc(String value) {
        return ReportViewRenderer.escape(value == null ? "" : value);
    }

    /**
     * 模板节点。字段只保留渲染需要的三项——{@code description} / 创建人这类管理字段
     * 与报告内容无关，进快照只会让 hash 因为无关改动而变。
     */
    private static Map<String, Object> templateNode(ReportTemplate template) {
        if (template == null) {
            return Map.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>(template.fields().size());
        for (ReportTemplate.Field field : template.fields()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("fieldKey", field.fieldKey());
            node.put("sortOrder", field.sortOrder());
            node.put("titleOverride", field.titleOverride());
            fields.add(node);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", template.id());
        node.put("version", template.version());
        node.put("name", template.name());
        node.put("fields", fields);
        return node;
    }

    private static String summary(ReportRecord source, ReportTemplate template) {
        String base = "run=" + source.runId() + "; pass=" + source.passCount()
                + "; fail=" + source.failCount() + "; skip=" + source.skipCount();
        return template == null ? base
                : base + "; template=" + template.name() + "@v" + template.version();
    }

    private static double rate(ReportRecord source) {
        int total = source.passCount() + source.failCount() + source.skipCount();
        return total == 0 ? 0.0 : Math.round(10000.0 * source.passCount() / total) / 100.0;
    }

    private String write(Map<String, Object> root) {
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("报告快照序列化失败", e);
        }
    }

    private static String html(Map<String, Object> root) {
        StringBuilder b = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>Test Report</title></head><body><h1>Test Report</h1><dl>");
        for (Map.Entry<String, Object> e : root.entrySet()) {
            b.append("<dt>").append(ReportViewRenderer.escape(e.getKey())).append("</dt><dd>")
                    .append(ReportViewRenderer.escape(String.valueOf(e.getValue()))).append("</dd>");
        }
        return b.append("</dl></body></html>").toString();
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
