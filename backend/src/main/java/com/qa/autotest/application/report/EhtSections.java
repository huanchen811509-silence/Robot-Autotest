package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.MetadataGate;
import java.util.List;
import java.util.Map;

/**
 * v5.14.0 / W64-14：EHT pytest 三段式报告的数据载体（只读装配结果）。
 *
 * <p>刻意不新增业务表：四态计数来自 {@code test_result.criteria_verdict} 的四个互斥取值，
 * 未定项与证据来自 {@code pytest_case_step}（node_id / content / stdout_path），
 * 差异来自既有 {@link CompareService}。报告是**查询期装配**，不是第二份事实源。
 *
 * <p>四态口径（R-24 口径修订）：判据列只有 {@code PASS / FAIL / MISSING / NULL} 四值，
 * 互斥且穷尽，故映射为「通过 / 不通过 / 缺证据 / 未判定」。草案写的第四态「未执行」
 * 在 {@code test_result} 里没有载体（每一行都代表一次已执行的用例），
 * 如实改为「未判定（未声明判据）」，不编造 NOT_RUN 数字。
 */
public record EhtSections(String kernel,
                          Counts counts,
                          List<Undetermined> undetermined,
                          List<Evidence> evidence,
                          Map<String, String> metadata,
                          List<String> metadataRequired,
                          String commitSha,
                          Diff diff) {

    /** 四态计数；{@link #balanced()} 就是 FR-09 判据 2「四态之和 = 用例总数」。 */
    public record Counts(int passed, int failed, int missingEvidence, int undetermined, int total) {
        public int sum() {
            return passed + failed + missingEvidence + undetermined;
        }

        public boolean balanced() {
            return sum() == total;
        }
    }

    /** 判据未定项：nodeRef 优先用真实 pytest nodeid，退化为 test_result.case_key。 */
    public record Undetermined(String nodeRef, String reason) {
    }

    /** 证据行：判据行原文 / 实测值 / 证据文件相对路径。 */
    public record Evidence(String nodeRef, String criteriaText, String measuredValue,
                           String evidencePath) {
    }

    /** 与上次同工程执行的差异；无基线时 hasBaseline=false，界面显示「无上次执行」。 */
    public record Diff(boolean hasBaseline, String baselineRunId,
                       int newFail, int newPass, int bothFail, int notRun) {
        public static Diff none() {
            return new Diff(false, null, 0, 0, 0, 0);
        }

        /**
         * 差异映射的单一真值：无基线时一律 {@link #none()}。
         *
         * <p>既有 {@code CompareService} 在没有基线时仍会把「所有当前 FAIL」计成 newFail
         * （history 为 null 就走 {@code else newFail++}）。直接透出会让报告凭空报「新增失败」，
         * 所以这里显式挡掉，界面显示「无上次执行」。
         */
        public static Diff from(CompareService.CompareResult result) {
            if (result == null || result.baseline() == null) {
                return none();
            }
            CompareService.CompareSummary s = result.summary();
            return new Diff(true, result.baseline().runId(),
                    s.newFail(), s.newPass(), s.bothFail(), s.notRun());
        }
    }

    /** 无任何明细时（run 不存在 / 装配异常）的可解释空值，绝不返回 null。 */
    public static EhtSections empty(String kernel) {
        return new EhtSections(kernel == null ? "robot" : kernel,
                new Counts(0, 0, 0, 0, 0), List.of(), List.of(), Map.of(),
                MetadataGate.REQUIRED, null, Diff.none());
    }

    /**
     * v5.14.0 / W5 真实库探针修复：{@code run_artifact.path} 与
     * {@code pytest_case_step.stdout_path} 在库里**存的是绝对路径**
     * （实测 {@code /home/evergreen/robot-test/artifacts/...}）。
     * 报告是给外部看的，绝对路径既违反 FR-09「相对路径」判据，也会泄漏部署布局；
     * 而 {@code ReportViewRenderer.scrub} 只会把它整段打码，路径反而不可用。
     *
     * <p>口径：已经是相对路径的原样保留；绝对路径优先截到 {@code artifacts/} 段
     * （保留可追溯的目录结构），没有该段时只留文件名，绝不把目录带出去。
     */
    public static String relativePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replace('\\', '/');
        boolean absolute = value.startsWith("/") || value.matches("^[A-Za-z]:/.*");
        if (!absolute) {
            return value;
        }
        String marker = "/artifacts/";
        int at = value.lastIndexOf(marker);
        if (at >= 0) {
            return "artifacts/" + value.substring(at + marker.length());
        }
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return name.isBlank() ? null : name;
    }

    /** metadata 由调用方从冻结快照同一份 ReportRecord 取，避免仓储再读一遍。 */
    public EhtSections withMetadata(Map<String, String> safe) {
        return new EhtSections(kernel, counts, undetermined, evidence,
                safe == null ? Map.of() : safe, metadataRequired, commitSha, diff);
    }
}
