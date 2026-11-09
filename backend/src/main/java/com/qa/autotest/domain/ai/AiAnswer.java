package com.qa.autotest.domain.ai;

import java.util.List;

/**
 * 一条「有据可依」的 AI 回答（FR-3.3-02 / EW33-02）。
 *
 * <ul>
 *   <li>{@code facts} 只能来自证据，数字必须能在证据里找到；</li>
 *   <li>{@code inferences} 是从事实推导的判断，正文里必须显式标注「推测」；</li>
 *   <li>{@code suggestions} 是可人工执行的检查项，不包含自动改代码 / 自动提交；</li>
 *   <li>{@code degraded=true} 表示未接入模型，仅由本地证据装配器产出，属正常降级态。</li>
 * </ul>
 */
public record AiAnswer(String mode, String text, List<String> facts, List<String> inferences,
                       List<String> suggestions, List<AiCitation> citations,
                       boolean degraded) {

    /** 会话导出用：把引用附在正文之后，保证导出件也能追溯。 */
    public String withCitations() {
        if (citations.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        sb.append("\n\n证据：");
        for (AiCitation citation : citations) {
            sb.append("\n- [").append(citation.id()).append("] ")
                    .append(citation.label()).append("（").append(citation.path()).append("）");
        }
        return sb.toString();
    }
}
