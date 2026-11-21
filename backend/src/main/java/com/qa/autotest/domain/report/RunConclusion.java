package com.qa.autotest.domain.report;

/**
 * 运行级结论（FR-UIE-12）。
 *
 * <p>在此之前只有 {@code fail == 0 → PASSED} 一条判定，于是「10 条全部跳过」
 * 会被判成 PASSED，而同一页的通过率是 0.0%——两个数字互相打脸。判定必须能表达
 * 「跑了，但没有任何一条给出有效结论」这个状态，否则它只能被塞进「通过」或「失败」，
 * 两种都是错的。
 *
 * <p>判定表（与 PRD FR-UIE-12 一一对应）：
 * <ul>
 *   <li>{@code total == 0} → {@link #NO_DATA}</li>
 *   <li>{@code failed &gt; 0} → {@link #FAILED}（有失败就一定不是通过，不看通过多少）</li>
 *   <li>{@code passed &gt; 0} → {@link #PASSED}</li>
 *   <li>其余（有结果但一条都没通过，全是跳过） → {@link #INCONCLUSIVE}</li>
 * </ul>
 */
public final class RunConclusion {

    /** 没有可统计的结果。 */
    public static final String NO_DATA = "NO_DATA";
    /** 有失败。 */
    public static final String FAILED = "FAILED";
    /** 有通过、无失败。 */
    public static final String PASSED = "PASSED";
    /** 有结果但无通过（全跳过）——不能判通过，也不该判失败。 */
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    private RunConclusion() {
    }

    public static String of(int passed, int failed, int skipped) {
        int total = passed + failed + skipped;
        if (total == 0) {
            return NO_DATA;
        }
        if (failed > 0) {
            return FAILED;
        }
        if (passed > 0) {
            return PASSED;
        }
        return INCONCLUSIVE;
    }
}
