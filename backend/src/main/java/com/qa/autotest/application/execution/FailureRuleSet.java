package com.qa.autotest.application.execution;

import com.qa.autotest.domain.execution.FailureClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 失败分类规则集：一组「类别 + 关键词 + 正则」规则，匹配时按全局优先级
 * CANCELED &gt; ENV &gt; INFRA &gt; ASSERT 返回第一个命中。
 *
 * <p>关键词按大小写不敏感的子串匹配；正则按大小写不敏感的部分匹配。
 * 配置里写坏的正则或无法识别的类别都只跳过、不抛错——配置瑕疵不应中断执行链路。
 */
public record FailureRuleSet(List<Rule> rules) {

    /** 分类优先级：与 spec §4.7「CANCELED &gt; ENV &gt; INFRA &gt; ASSERT」一致。 */
    private static final List<FailureClass> PRIORITY = List.of(
            FailureClass.CANCELED, FailureClass.ENV, FailureClass.INFRA, FailureClass.ASSERT);

    /** 单条规则：类别 + 关键词（子串）+ 正则。 */
    public record Rule(FailureClass cls, List<String> keywords, List<String> regex) {

        public Rule {
            cls = cls == null ? FailureClass.ASSERT : cls;
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            regex = regex == null ? List.of() : List.copyOf(regex);
        }

        boolean matches(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank()
                        && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            for (String expression : regex) {
                if (expression == null || expression.isBlank()) {
                    continue;
                }
                try {
                    if (Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(text).find()) {
                        return true;
                    }
                } catch (PatternSyntaxException ignored) {
                    // 写坏的正则跳过，不影响其余规则。
                }
            }
            return false;
        }
    }

    public FailureRuleSet {
        rules = rules == null ? List.of()
                : rules.stream().filter(Objects::nonNull).toList();
    }

    /** spec §4.7 默认规则。CANCELED 不在此列，由 {@link FailureClassifier} 单独判定。 */
    public static FailureRuleSet defaults() {
        List<Rule> defaults = new ArrayList<>();
        defaults.add(new Rule(FailureClass.ENV,
                List.of("Connection refused",
                        "Connection reset",
                        "timed out",
                        "No route to host",
                        "Temporary failure in name resolution",
                        // 覆盖 "Permission denied (publickey,password)" 及其它公钥/口令变体
                        "Permission denied (publickey",
                        "SSH 认证失败",
                        // v5.3 补：这几条是最常见的连接类错误，早期漏收会把环境问题判成断言失败，
                        // 后果是「该重试的不重试」且雷达低估环境故障占比（两侧同时失真）。
                        "network is unreachable",
                        "Network is unreachable",
                        "host key verification failed",
                        "Connection closed",
                        "Connection timed out",
                        "broken pipe"),
                List.of()));
        defaults.add(new Rule(FailureClass.INFRA,
                List.of("no tests executed",
                        "no_tests_executed",
                        "parse failed",
                        "parse_failed",
                        "too_small",
                        "No such file or directory: 'robot'",
                        "Killed",
                        "disk full"),
                // output.xml 缺失：文件名与「缺失/missing/not found」必须同时出现，避免误伤普通断言文本
                List.of("output\\.xml.*(missing|not found|缺失)",
                        "(missing|not found|缺失).*output\\.xml")));
        defaults.add(new Rule(FailureClass.ASSERT,
                List.of("FAIL", "AssertionError", "Expected"),
                List.of()));
        return new FailureRuleSet(defaults);
    }

    /** 由 {@code execution.failure-rules} 配置派生；为空、全为非法行时回落 {@link #defaults()}。 */
    public static FailureRuleSet from(List<ExecutionProperties.FailureRuleConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return defaults();
        }
        List<Rule> parsed = new ArrayList<>();
        for (ExecutionProperties.FailureRuleConfig config : configs) {
            if (config == null) {
                continue;
            }
            FailureClass cls = parseClass(config.failureClass());
            if (cls == null) {
                continue;
            }
            parsed.add(new Rule(cls, config.keywords(), config.regex()));
        }
        return parsed.isEmpty() ? defaults() : new FailureRuleSet(parsed);
    }

    /** 按 CANCELED &gt; ENV &gt; INFRA &gt; ASSERT 顺序返回第一个命中的类别。 */
    public Optional<FailureClass> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (FailureClass target : PRIORITY) {
            for (Rule rule : rules) {
                if (rule.cls() == target && rule.matches(text)) {
                    return Optional.of(target);
                }
            }
        }
        return Optional.empty();
    }

    private static FailureClass parseClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FailureClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // 配置里的类别拼错只跳过该行，不影响其它规则。
            return null;
        }
    }
}
