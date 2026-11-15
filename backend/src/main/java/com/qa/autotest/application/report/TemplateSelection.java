package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.ReportTemplate;
import java.util.Objects;

/**
 * v5.14.0 / W64-11：四级模板回退的**单一真值**，application 层可见。
 *
 * <p>SQL 的 CASE、JDBC 的排序、报告详情的 {@code templateSource} 元信息必须说同一套话：
 * 三处各写一份就会出现「界面说命中内核模板、实际回退到通用模板」的漂移。
 * 因此把 rank 放到这里，JDBC 与报告服务都来调它。
 *
 * <p>刻意不写进快照 JSON：快照是冻结事实，字段集合与 hash 由 TPLX-02 纪律保护；
 * 「这次选择落在哪一级」是查询期元信息，只用于界面提示，不参与渲染，
 * 也不该因为加一个提示就改变所有历史报告的 snapshot_hash。
 */
public final class TemplateSelection {

    /** ① 本项目默认且 kernel 匹配。 */
    public static final String PROJECT_KERNEL_MATCH = "PROJECT_KERNEL_MATCH";
    /** ② 平台默认且 kernel 匹配（EHT-PYTEST 走这一级）。 */
    public static final String PLATFORM_KERNEL_MATCH = "PLATFORM_KERNEL_MATCH";
    /** ③ 平台默认 any 通用模板。 */
    public static final String PLATFORM_ANY = "PLATFORM_ANY";
    /** ④ 任意平台默认兜底（内核不匹配，判据 3 要在这里提示）。 */
    public static final String PLATFORM_FALLBACK = "PLATFORM_FALLBACK";

    private TemplateSelection() {
    }

    public static int rank(long templateProjectId, String templateKernel, long projectId,
                           String runKernel) {
        if (templateProjectId == projectId
                && Objects.equals(templateKernel, runKernel)) {
            return 0;
        }
        if (templateProjectId == ReportTemplate.PLATFORM_PROJECT_ID
                && Objects.equals(templateKernel, runKernel)) {
            return 1;
        }
        if (templateProjectId == ReportTemplate.PLATFORM_PROJECT_ID
                && ReportTemplate.KERNEL_ANY.equals(templateKernel)) {
            return 2;
        }
        return 3;
    }

    public static String source(int rank) {
        return switch (rank) {
            case 0 -> PROJECT_KERNEL_MATCH;
            case 1 -> PLATFORM_KERNEL_MATCH;
            case 2 -> PLATFORM_ANY;
            default -> PLATFORM_FALLBACK;
        };
    }

    /** 界面判定「当前使用通用模板」的唯一口径：只有 ③④ 算回退。 */
    public static boolean generic(String source) {
        return PLATFORM_ANY.equals(source) || PLATFORM_FALLBACK.equals(source);
    }
}
