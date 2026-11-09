package com.qa.autotest.application.ai;

import com.qa.autotest.domain.ai.AiAnswer;
import com.qa.autotest.domain.ai.AiEvidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 事实一致性守门（EW33-08 / FR-3.3-02）。
 *
 * <p>规则很窄但可执行：
 * <ol>
 *   <li>正文里出现的每个数字，必须在证据里出现过（保留两位小数比对）；</li>
 *   <li>所有 {@code [E#]} 引用必须指向真实存在的证据；</li>
 *   <li>有证据时至少要引用一条，不能「无引用断言」；</li>
 *   <li>没有证据时必须明确写出「没有可用证据」，不能装作知道。</li>
 * </ol>
 * 列表序号（行首 {@code 1.} / {@code 2)}）不作为事实数字，避免把排版当结论。
 */
@Component
public class AiFactVerifier {

    public record Report(boolean pass, List<String> unsupportedNumbers,
                         List<String> missingCitations, int citationCount) {
    }

    private static final java.util.regex.Pattern CITATION =
            java.util.regex.Pattern.compile("\\[E(\\d+)]");

    public Report verify(AiAnswer answer, AiEvidence evidence) {
        Set<Double> allowed = new LinkedHashSet<>();
        for (Double number : evidence.numbers()) {
            allowed.add(Math.round(number * 100) / 100.0);
        }
        List<String> unsupported = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        String text = answer.text();
        List<String> referenced = new ArrayList<>();
        var citationMatcher = CITATION.matcher(text);
        while (citationMatcher.find()) {
            referenced.add(citationMatcher.group(1));
            int id = Integer.parseInt(citationMatcher.group(1));
            if (id < 1 || id > evidence.items().size()) {
                missing.add("E" + id);
            }
        }
        String withoutCitations = CITATION.matcher(text).replaceAll("");

        for (String line : withoutCitations.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.matches("^\\d+[.)、]\\s*.*")) {
                continue;
            }
            var numberMatcher = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?")
                    .matcher(trimmed);
            while (numberMatcher.find()) {
                double value = Math.round(Double.parseDouble(numberMatcher.group()) * 100) / 100.0;
                if (!allowed.contains(value)) {
                    unsupported.add(numberMatcher.group());
                }
            }
        }

        boolean noEvidence = evidence.items().isEmpty();
        boolean declaresNoEvidence = text.contains("没有可用证据") || text.contains("无可用证据");
        if (noEvidence && !declaresNoEvidence) {
            missing.add("NO_EVIDENCE_DECLARATION");
        }
        if (!noEvidence && referenced.isEmpty() && !answer.facts().isEmpty()) {
            missing.add("NO_CITATION");
        }
        boolean pass = unsupported.isEmpty() && missing.isEmpty();
        return new Report(pass, List.copyOf(unsupported), List.copyOf(missing),
                evidence.items().size());
    }
}
