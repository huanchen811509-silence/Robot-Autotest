package com.qa.autotest.domain.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 AI 请求可用的证据集合（FR-3.3-02）。
 *
 * <p>只承载「已经按权限过滤并脱敏」的事实；构造器不做二次过滤。
 */
public record AiEvidence(String subject, List<Item> items) {

    /** 单条证据：编号、类型、引用键、可读标签、跳转路径与正文。 */
    public record Item(String type, String ref, String label, String path, String body) {
    }

    public AiEvidence {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static AiEvidence empty(String subject) {
        return new AiEvidence(subject, List.of());
    }

    /** 渲染成编号证据块：{@code [E1] (run) RUN-xxx: ...}。 */
    public String render() {
        if (items.isEmpty()) {
            return "（无可用证据）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            sb.append('[').append(idOf(i)).append("] (").append(item.type()).append(") ")
                    .append(item.label()).append(": ").append(item.body()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public List<AiCitation> citations() {
        List<AiCitation> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            result.add(new AiCitation(idOf(i), item.type(), item.ref(), item.label(), item.path()));
        }
        return List.copyOf(result);
    }

    /** 证据正文里出现过的全部数字（含小数），供事实一致性校验使用。 */
    public List<Double> numbers() {
        List<Double> values = new ArrayList<>();
        for (Item item : items) {
            collectNumbers(item.body(), values);
            collectNumbers(item.label(), values);
        }
        return List.copyOf(values);
    }

    private static void collectNumbers(String text, List<Double> target) {
        if (text == null) {
            return;
        }
        var matcher = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?")
                .matcher(text);
        while (matcher.find()) {
            target.add(Double.valueOf(matcher.group()));
        }
    }

    public static String idOf(int index) {
        return "E" + (index + 1);
    }
}
