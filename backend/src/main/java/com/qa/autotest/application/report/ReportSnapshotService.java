package com.qa.autotest.application.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        String json = write(root);
        String html = html(root);
        return new ReportSnapshot(json, html, sha256(json + "\n" + html), version,
                summary(source, template));
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
