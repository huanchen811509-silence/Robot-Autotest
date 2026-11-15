package com.qa.autotest.domain.env;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行前环境闸门（v5.15.0 / FR-v5.15-09 / 一票否决 V-1, V-2）。
 *
 * <p>纯领域对象、零框架依赖，与 MetadataGate 同构：声明才校验，无声明不拦截但标记
 * {@code undeclared=true}，由调用方写留痕。不满足必须点名 key / expected / actual / snapshotId，
 * 不接受「环境不匹配」这种无法复核的模糊拒绝。
 */
public final class EnvironmentGate {

    public static final String OP_MIN_GBPS = "MIN_GBPS";
    public static final String OP_EQ = "EQ";
    public static final String OP_PRESENT = "PRESENT";

    public record Requirement(String key, String op, String value) {
    }

    public record Failure(String key, String expected, String actual, long snapshotId) {
        public String describe() {
            return "key=" + key + "; expected=" + expected + "; actual=" + actual
                    + "; snapshotId=" + snapshotId;
        }
    }

    public record Verdict(boolean pass, boolean undeclared, long snapshotId,
                          List<Failure> failures) {
    }

    private EnvironmentGate() {
    }

    public static Verdict evaluate(List<Requirement> requirements,
                                   java.util.Map<String, String> facts, long snapshotId) {
        List<Requirement> declared = requirements == null ? List.of() : requirements;
        if (declared.isEmpty()) {
            return new Verdict(true, true, snapshotId, List.of());
        }
        List<Failure> failures = new ArrayList<>();
        for (Requirement requirement : declared) {
            String key = requirement.key() == null ? "" : requirement.key();
            String op = requirement.op() == null ? OP_PRESENT : requirement.op();
            String expected = requirement.value() == null ? "" : requirement.value();
            String actual = facts.getOrDefault(key, "未提供");
            boolean ok;
            if (OP_MIN_GBPS.equals(op)) {
                String numeric = facts.getOrDefault(key + ".maxGbps", "未提供");
                actual = numeric;
                ok = numeric.matches("\\d+") && Long.parseLong(numeric) >= parseLong(expected);
            } else if (OP_EQ.equals(op)) {
                ok = expected.equals(actual);
            } else {
                ok = !"未提供".equals(actual) && !actual.isBlank()
                        && !actual.startsWith("未采集到") && !"null".equals(actual);
            }
            if (!ok) {
                failures.add(new Failure(key, expected, shorten(actual), snapshotId));
            }
        }
        return new Verdict(failures.isEmpty(), false, snapshotId, failures);
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static String shorten(String raw) {
        if (raw == null) {
            return "未提供";
        }
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...";
    }
}
