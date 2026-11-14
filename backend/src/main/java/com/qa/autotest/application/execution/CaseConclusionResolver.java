package com.qa.autotest.application.execution;

import java.util.List;
import java.util.Locale;

/**
 * 唯一结论入口（W57-23）：把「进程状态 status」与「判据结论 criteria」裁定成最终结论。
 *
 * <p><b>5 行判定矩阵</b>（status 是事实，conclusion 是结论，二者不能互相代替）：
 * <pre>
 *  # | status    | criteria | conclusion
 *  1 | completed | PASS     | PASS
 *  2 | completed | FAIL     | FAIL
 *  3 | completed | MISSING  | UNDETERMINED（未判定）
 *  4 | completed | NULL     | UNDETERMINED（旧数据 / 未声明，绝不默认 PASS）
 *  5 | failed    | 任意     | FAIL
 * </pre>
 * 未知 status 同样降级 UNDETERMINED，不默认 PASS。纯静态逻辑，单测直接覆盖矩阵。
 */
public final class CaseConclusionResolver {

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public enum Conclusion {
        PASS, FAIL, UNDETERMINED
    }

    /** 单条结果视图：status 来自 test_result.verdict / run 状态，criteriaVerdict 来自判据列。 */
    public record VerdictView(String status, String criteriaVerdict) {
    }

    /** 覆盖率：判据声明率、已判定率及各计数。 */
    public record Coverage(int total,
                           int declared,
                           int undeclared,
                           int pass,
                           int fail,
                           int missing,
                           int undetermined,
                           double declaredRate,
                           double determinedRate) {
    }

    private CaseConclusionResolver() {
    }

    /** 把 run.run_status / 用例 verdict 归一成矩阵需要的 completed / failed。 */
    public static String normalizeStatus(String runStatus) {
        if (runStatus == null || runStatus.isBlank()) {
            return STATUS_COMPLETED;
        }
        return switch (runStatus.trim().toLowerCase(Locale.ROOT)) {
            case "failed", "cancelled", "error" -> STATUS_FAILED;
            default -> STATUS_COMPLETED;
        };
    }

    public static Conclusion resolve(String status, String criteriaVerdict) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (STATUS_FAILED.equals(s)) {
            return Conclusion.FAIL;                       // 矩阵第 5 行
        }
        if (!STATUS_COMPLETED.equals(s)) {
            return Conclusion.UNDETERMINED;                // 未知状态不默认 PASS
        }
        String v = criteriaVerdict == null ? "" : criteriaVerdict.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "PASS" -> Conclusion.PASS;                // 第 1 行
            case "FAIL" -> Conclusion.FAIL;                // 第 2 行
            case "MISSING" -> Conclusion.UNDETERMINED;     // 第 3 行
            default -> Conclusion.UNDETERMINED;            // 第 4 行：NULL / 空 / 旧数据
        };
    }

    public static Conclusion resolve(VerdictView view) {
        return view == null ? Conclusion.UNDETERMINED
                : resolve(view.status(), view.criteriaVerdict());
    }

    public static Coverage coverage(List<VerdictView> views) {
        List<VerdictView> list = views == null ? List.of() : views;
        int total = list.size();
        int declared = 0;
        int pass = 0;
        int fail = 0;
        int missing = 0;
        int undetermined = 0;
        for (VerdictView view : list) {
            String v = view.criteriaVerdict() == null ? ""
                    : view.criteriaVerdict().trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) {
                declared++;
            }
            switch (v) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                case "MISSING" -> missing++;
                default -> {
                }
            }
            if (resolve(view) == Conclusion.UNDETERMINED) {
                undetermined++;
            }
        }
        double declaredRate = total == 0 ? 0d : (double) declared / total;
        double determinedRate = total == 0 ? 0d : (double) (total - undetermined) / total;
        return new Coverage(total, declared, total - declared, pass, fail, missing,
                undetermined, declaredRate, determinedRate);
    }
}
