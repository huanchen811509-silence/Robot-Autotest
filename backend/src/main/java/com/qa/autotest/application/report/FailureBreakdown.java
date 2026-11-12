package com.qa.autotest.application.report;

import com.qa.autotest.application.execution.FailureFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 失败归因分布与环境失败分流（FR-v5.2-06 / B15 / W52-10）。
 *
 * <p><b>要解决的问题</b>：一份 400 条用例的 run 里有 30 条红，如果报告只写「失败 30 条、
 * 通过率 92.5%」，读报告的人无法判断「这版能不能发」——因为 30 条里可能有 25 条是管理网
 * 断了、3 条是 run_env 没渲染对、只有 2 条真的是产品不符合规格。把环境问题算进产品通过率，
 * 后果是双向的：环境抖动时把产品判死（该发的版本不敢发），环境长期差时又让人对通过率麻木
 * （真缺陷被淹没在噪声里）。
 *
 * <p><b>口径</b>（三层，逐层可复核）：
 * <ol>
 *   <li>全部用例 = 通过 + 失败侧样本 + 计划未执行（PLANNED）；</li>
 *   <li>失败侧 = 产品 + 环境 + 跳过 + 未归类；</li>
 *   <li>产品通过率分母 = 通过 + 产品失败 —— 排除环境、跳过、未归类。</li>
 * </ol>
 *
 * <p><b>为什么 UNKNOWN 单独一类，不塞进产品侧</b>：归因文本不足时（比如只有一行
 * 「AssertionError」而没有任何上下文），把它算成产品缺陷会让「产品通过率」随归因质量
 * 波动；把它算成环境问题则是粉饰。单独计数并在报告里点名「N 条归因不足，需人工判断」，
 * 是唯一不撒谎的做法。
 *
 * <p><b>为什么环境侧包含 CONFIG</b>：配置/契约类失败（参数不认识、run_env 渲染缺项、
 * fixture 找不到）的处置动作与产品缺陷不同——它要改的是平台或用例库的契约，不是产品。
 * 这与 {@link FailureAttributor.Attribution#environmentSide()} 的定义保持同一口径。
 */
public final class FailureBreakdown {

    /** 侧别标签（报告里直接显示这几个词，改词要同步机检脚本）。 */
    public static final String SIDE_PRODUCT = "产品";
    public static final String SIDE_ENV = "环境";
    public static final String SIDE_SKIP = "跳过";
    public static final String SIDE_UNKNOWN = "未归类";

    /** 侧别的固定显示顺序：产品在前，因为它才是「能不能发」的依据。 */
    private static final List<String> SIDE_ORDER =
            List.of(SIDE_PRODUCT, SIDE_ENV, SIDE_SKIP, SIDE_UNKNOWN);

    /** 下钻时每条聚合项最多带出的用例编号数（再多就该去明细页看，不该塞进报告）。 */
    private static final int DRILL_DOWN_LIMIT = 20;

    /**
     * 一条失败侧样本。
     *
     * @param caseCode 用例编号（用于下钻）
     * @param kernel   robot / pytest（参与指纹计算：同一失败在两内核下位置表达不同）
     * @param verdict  用例结论（PASS/FAIL/ERROR/SKIP）；SKIP 一律归「跳过」，不看 category
     * @param category {@link FailureAttributor} 的分类结论
     * @param location L3 位置（参与指纹）
     * @param summary  失败首行原文（参与指纹，会被归一化掉数字与 IP）
     */
    public record Sample(String caseCode, String kernel, String verdict, String category,
                         String location, String summary) {

        public Sample {
            caseCode = caseCode == null ? "" : caseCode;
            kernel = kernel == null ? "" : kernel;
            verdict = verdict == null ? "" : verdict;
            category = category == null ? "" : category;
            location = location == null ? "" : location;
            summary = summary == null ? "" : summary;
        }
    }

    /**
     * 一个分类桶。
     *
     * @param category 归因分类（ASSERT/ENV/CONFIG/…）
     * @param side     侧别（产品/环境/跳过/未归类）
     * @param count    条数
     * @param caseCodes 下钻用例编号（最多 {@value #DRILL_DOWN_LIMIT} 条，超出用「等 N 条」标注）
     */
    public record Bucket(String category, String side, int count, List<String> caseCodes) {

        /** 「等 N 条」后缀：下钻列表被截断时给读者一个数，避免以为只有这些。 */
        public String drillDown() {
            if (caseCodes.isEmpty()) {
                return "";
            }
            String joined = String.join(", ", caseCodes);
            return caseCodes.size() < count ? joined + " 等 " + count + " 条" : joined;
        }
    }

    /**
     * TopN 聚合项：同一类失败（指纹相同）聚成一条。
     *
     * @param fingerprint 指纹（32 位十六进制）
     * @param category    分类
     * @param count       命中条数
     * @param sample      代表性原文（第一条，已截断）
     * @param caseCodes   下钻用例编号
     */
    public record Cluster(String fingerprint, String category, int count, String sample,
                          List<String> caseCodes) {
    }

    /**
     * 归因分布快照。
     *
     * @param total               全部用例数（含 PLANNED）
     * @param passed              通过数
     * @param planned             计划未执行数
     * @param samples             失败侧样本数（= 产品 + 环境 + 跳过 + 未归类）
     * @param productFails        产品侧失败数
     * @param envFails            环境侧失败数（含配置/契约）
     * @param skipped             跳过数（含 Plan-only）
     * @param unknown             未归类数
     * @param productDenominator  产品通过率分母（= 通过 + 产品失败）
     * @param productPassRate     产品通过率（百分比，保留 1 位小数）
     * @param buckets             分类桶（固定顺序）
     * @param clusters            TopN 聚合项（按条数降序、同数按指纹升序）
     */
    public record Snapshot(int total, int passed, int planned, int samples,
                           int productFails, int envFails, int skipped, int unknown,
                           int productDenominator, double productPassRate,
                           List<Bucket> buckets, List<Cluster> clusters) {

        /** 失败侧合计与样本数是否对得上。 */
        public boolean balanced() {
            return productFails + envFails + skipped + unknown == samples;
        }

        /** 全部用例数是否等于「通过 + 失败侧 + 计划未执行」。 */
        public boolean totalConsistent() {
            return passed + samples + planned == total;
        }

        /** 不平衡时的说明；平衡时为 null（报告里不加噪声）。 */
        public String imbalance() {
            if (!balanced()) {
                return "口径不平衡：失败侧 " + samples + " 条 ≠ 产品 " + productFails
                        + " + 环境 " + envFails + " + 跳过 " + skipped + " + 未归类 " + unknown;
            }
            if (!totalConsistent()) {
                return "口径不平衡：全部 " + total + " 条 ≠ 通过 " + passed
                        + " + 失败侧 " + samples + " + 计划未执行 " + planned;
            }
            return null;
        }

        /** TopN（n 会被夹到 [0, clusters.size()]）。 */
        public List<Cluster> top(int n) {
            if (n <= 0 || clusters.isEmpty()) {
                return List.of();
            }
            return clusters.subList(0, Math.min(n, clusters.size()));
        }

        /**
         * 第 4 章（失败分析）。
         *
         * <p>没有失败侧样本时返回 {@code NO_DATA}，由
         * {@link ReportChapterBuilder#build} 的 optional 兜底成「无失败」——
         * 这里不自己拼「无失败」文案，是因为兜底文案由章模型统一维护，两处写会漂。
         */
        public ReportChapterBuilder.Chapter chapter() {
            if (samples == 0) {
                return new ReportChapterBuilder.Chapter(4,
                        ReportChapterBuilder.TITLES.get(3),
                        ReportChapterBuilder.Status.NO_DATA,
                        "test_result + FailureAttributor 归因",
                        "无数据", "无失败（本次执行没有 FAIL/ERROR 用例）", List.of());
            }
            List<ReportChapterBuilder.Item> items = new ArrayList<>();
            for (Bucket bucket : buckets) {
                items.add(new ReportChapterBuilder.Item(
                        bucket.side() + " · " + bucket.category() + "（" + bucket.count() + " 条）",
                        bucket.drillDown()));
            }
            String imbalance = imbalance();
            if (imbalance != null) {
                items.add(new ReportChapterBuilder.Item("口径校验", imbalance));
            }
            items.add(new ReportChapterBuilder.Item("产品通过率分母",
                    "通过 " + passed + " + 产品失败 " + productFails + " = " + productDenominator
                            + "（已排除环境 " + envFails + "、跳过 " + skipped
                            + "、未归类 " + unknown + "）"));
            items.add(new ReportChapterBuilder.Item("产品通过率", productPassRate + "%"));
            if (unknown > 0) {
                items.add(new ReportChapterBuilder.Item("需人工判断",
                        unknown + " 条归因文本不足，既不能算产品缺陷也不能算环境问题"));
            }
            return new ReportChapterBuilder.Chapter(4,
                    ReportChapterBuilder.TITLES.get(3),
                    ReportChapterBuilder.Status.OK,
                    "test_result + FailureAttributor 归因",
                    "产品失败 " + productFails + " 条 / 环境失败 " + envFails
                            + " 条 / 跳过 " + skipped + " 条",
                    null, List.copyOf(items));
        }
    }

    private FailureBreakdown() {
    }

    /**
     * 汇总。
     *
     * @param total   全部用例数（含 PLANNED）
     * @param passed  通过数
     * @param planned 计划未执行数
     * @param samples 失败侧样本（FAIL/ERROR/SKIP 三类，PASS 不要传进来）
     */
    public static Snapshot of(int total, int passed, int planned, List<Sample> samples) {
        List<Sample> rows = samples == null ? List.of() : samples;
        int product = 0;
        int env = 0;
        int skipped = 0;
        int unknown = 0;

        Map<String, List<String>> bySideCategory = new LinkedHashMap<>();
        Map<String, Cluster> clusters = new LinkedHashMap<>();

        for (Sample row : rows) {
            String side = sideOf(row);
            String category = categoryOf(row, side);
            switch (side) {
                case SIDE_PRODUCT -> product++;
                case SIDE_ENV -> env++;
                case SIDE_SKIP -> skipped++;
                default -> unknown++;
            }

            String key = side + "·" + category;
            bySideCategory.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(row.caseCode().isBlank() ? "（无编号）" : row.caseCode());

            String fingerprint = FailureFingerprint.of(row.kernel(), category,
                    row.location(), row.summary());
            Cluster existing = clusters.get(fingerprint);
            if (existing == null) {
                clusters.put(fingerprint, new Cluster(fingerprint, category, 1,
                        truncate(row.summary(), 160), new ArrayList<>(List.of(codeOf(row)))));
            } else {
                List<String> codes = new ArrayList<>(existing.caseCodes());
                if (codes.size() < DRILL_DOWN_LIMIT) {
                    codes.add(codeOf(row));
                }
                clusters.put(fingerprint, new Cluster(fingerprint, existing.category(),
                        existing.count() + 1, existing.sample(), codes));
            }
        }

        List<Bucket> buckets = new ArrayList<>();
        for (String side : SIDE_ORDER) {
            bySideCategory.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(side + "·"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String category = entry.getKey().substring(side.length() + 1);
                        List<String> codes = entry.getValue();
                        List<String> limited = codes.size() <= DRILL_DOWN_LIMIT
                                ? List.copyOf(codes)
                                : List.copyOf(codes.subList(0, DRILL_DOWN_LIMIT));
                        buckets.add(new Bucket(category, side, codes.size(), limited));
                    });
        }

        List<Cluster> ranked = new ArrayList<>(clusters.values());
        ranked.sort(Comparator.comparingInt(Cluster::count).reversed()
                .thenComparing(Cluster::fingerprint));

        int denominator = passed + product;
        double rate = denominator == 0 ? 0.0 : round1(passed * 100.0 / denominator);
        int safeTotal = total <= 0 ? rows.size() + Math.max(passed, 0) + Math.max(planned, 0) : total;

        return new Snapshot(safeTotal, passed, planned, rows.size(), product, env, skipped, unknown,
                denominator, rate, List.copyOf(buckets), List.copyOf(ranked));
    }

    /** 侧别判定：结论是 SKIP 一律归跳过，不看分类。 */
    static String sideOf(Sample row) {
        String verdict = row.verdict().trim().toUpperCase(Locale.ROOT);
        if ("SKIP".equals(verdict) || "SKIPPED".equals(verdict)) {
            return SIDE_SKIP;
        }
        return switch (row.category().trim().toUpperCase(Locale.ROOT)) {
            case FailureAttributor.CATEGORY_ASSERT,
                 FailureAttributor.CATEGORY_SCRIPT,
                 FailureAttributor.CATEGORY_DATA -> SIDE_PRODUCT;
            case FailureAttributor.CATEGORY_ENV, FailureAttributor.CATEGORY_CONFIG -> SIDE_ENV;
            case FailureAttributor.CATEGORY_PLAN_ONLY -> SIDE_SKIP;
            default -> SIDE_UNKNOWN;
        };
    }

    /** 桶里的分类名：跳过与未归类的分类名统一成侧别名，避免出现「跳过 · UNKNOWN」。 */
    private static String categoryOf(Sample row, String side) {
        if (SIDE_SKIP.equals(side) || SIDE_UNKNOWN.equals(side)) {
            return side;
        }
        return row.category().isBlank() ? SIDE_UNKNOWN : row.category().trim().toUpperCase(Locale.ROOT);
    }

    private static String codeOf(Sample row) {
        return row.caseCode().isBlank() ? "（无编号）" : row.caseCode();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
