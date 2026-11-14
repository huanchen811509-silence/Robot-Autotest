package com.qa.autotest.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 判据评估（v5.7.0 FR-v5.7-12 / 13，复核 W57-50）。
 *
 * <p><b>为什么独立成类</b>：评估是**纯函数**，既要被报告入库链路（{@code infrastructure}
 * 写 {@code test_result} 时）调用，也要被 {@code CriteriaIngestService} 的人工重采调用。
 * 放进带 Spring 依赖的服务里，infrastructure 层调用就会引入服务依赖，且单测必须起容器。
 *
 * <p><b>四条硬口径</b>（与 V11_59 迁移注释一致）：
 * <ul>
 *   <li>未声明判据 → 三列全 NULL，界面显示「未声明」，<b>禁止默认 PASS</b>；</li>
 *   <li>解析异常 → verdict={@code MISSING} 且 {@code missing} 写明原因，不静默吞掉；</li>
 *   <li>快照 JSON 超过 64KB 截断并打 {@code [TRUNCATED]} 标记；</li>
 *   <li>只有全部判据明确 {@code passed=true} 才判 PASS；缺实测值 / 未给结论一律 MISSING。</li>
 * </ul>
 */
public final class CriteriaEvaluation {

    public static final int MAX_JSON_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 评估结果。
     *
     * @param json      判据快照（未声明时为 null）
     * @param verdict   PASS / FAIL / MISSING；未声明时为 null
     * @param missing   缺失指标与原因；无缺失时为 null
     * @param declared  是否真的声明了判据
     */
    public record Result(String json, String verdict, String missing, boolean declared) {

        /** 未声明判据：三列全 NULL，调用方据此写 NULL 而不是写 PASS。 */
        public static Result undeclared() {
            return new Result(null, null, null, false);
        }
    }

    private CriteriaEvaluation() {
    }

    /**
     * 评估一段文本里的判据。
     *
     * <p>文本来源：junit {@code <system-out>}、pytest 的 JSON 报告、或用例脚本打印的判据块。
     * 解析策略是「截取第一个完整 JSON 对象」——判据块前面通常有日志行，不能整段当 JSON。
     *
     * @param payload 原文；null / 空白视作「未声明」
     */
    public static Result evaluate(String payload) {
        if (payload == null || payload.isBlank()) {
            return Result.undeclared();
        }
        List<CaseCriteria> criteria;
        try {
            criteria = parse(payload);
        } catch (Exception e) {
            String reason = "采集异常：" + e.getClass().getSimpleName()
                    + " / " + abbreviate(e.getMessage());
            return new Result(null, "MISSING", reason, false);
        }
        if (criteria.isEmpty()) {
            // JSON 有但里面没有 criteria 数组：等于没声明，不能当成 PASS。
            return Result.undeclared();
        }

        String snapshot;
        try {
            snapshot = JSON.writeValueAsString(criteria);
        } catch (Exception e) {
            return new Result(null, "MISSING",
                    "采集异常：判据快照序列化失败 / " + abbreviate(e.getMessage()), false);
        }
        boolean truncated = snapshot.length() > MAX_JSON_BYTES;
        if (truncated) {
            snapshot = snapshot.substring(0, MAX_JSON_BYTES) + "[TRUNCATED]";
        }

        List<String> missingMetrics = new ArrayList<>();
        boolean allPassed = true;
        boolean anyFailed = false;
        for (CaseCriteria c : criteria) {
            if (c.actual() == null || c.actual().isBlank() || c.passed() == null) {
                missingMetrics.add(c.metric() == null ? "(未命名指标)" : c.metric());
                allPassed = false;
            } else if (!c.passed()) {
                anyFailed = true;
                allPassed = false;
            }
        }

        String verdict;
        String missing = null;
        if (!missingMetrics.isEmpty()) {
            verdict = "MISSING";
            missing = "缺失指标：" + String.join(",", missingMetrics);
        } else if (anyFailed) {
            verdict = "FAIL";
        } else if (allPassed) {
            verdict = "PASS";
        } else {
            verdict = "MISSING";
            missing = "判据存在但未给出明确达标结论";
        }
        if (truncated) {
            missing = (missing == null ? "" : missing + "; ")
                    + "判据 JSON 超过 64KB 已截断标记 [TRUNCATED]";
        }
        return new Result(snapshot, verdict, missing, true);
    }

    /**
     * 从文本里截取第一个 JSON 对象并取 {@code criteria} 数组。
     * 找不到对象抛异常（→ MISSING）；对象里没有 criteria 返回空清单（→ 未声明）。
     */
    static List<CaseCriteria> parse(String payload) throws Exception {
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("文本中未找到判据 JSON 对象");
        }
        JsonNode root = JSON.readTree(payload.substring(start, end + 1));
        JsonNode array = root.get("criteria");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<CaseCriteria> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(new CaseCriteria(
                    text(node, "metric"), text(node, "op"), text(node, "threshold"),
                    text(node, "actual"),
                    node.hasNonNull("passed") ? node.get("passed").asBoolean() : null));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "(无详细信息)";
        }
        String trimmed = message.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
