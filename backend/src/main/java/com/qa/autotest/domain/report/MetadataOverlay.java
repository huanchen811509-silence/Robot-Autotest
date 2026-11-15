package com.qa.autotest.domain.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 补录项叠加（HRD-02）：把 {@code run_metadata} 里的补录值叠加到解析出的 metadata 上，
 * 并按合并结果重算十项门禁。
 *
 * <p>口径：
 * <ul>
 *   <li>解析结果保持不可变 —— 原始 {@code output.xml} 才是事实来源，补录不回写 {@code metadata_json}；</li>
 *   <li>叠加只在读取视图时生效，因此「补录后能否对外导出」永远与当前补录内容一致；</li>
 *   <li>空值 / 空白补录项不参与覆盖（删除项在补录落库时已移除，不会走到这里）。</li>
 * </ul>
 */
public final class MetadataOverlay {

    private MetadataOverlay() {
    }

    /**
     * 叠加补录值并重算门禁。
     *
     * @param record    解析入库得到的报告视图
     * @param overrides 补录项（键 → 值），允许为 null 或空
     * @return 叠加后的报告视图；无补录时原样返回
     */
    public static ReportRecord apply(ReportRecord record, Map<String, String> overrides) {
        if (record == null || overrides == null || overrides.isEmpty()) {
            return record;
        }
        Map<String, String> merged = merge(record.metadata(), overrides);
        if (merged.equals(record.metadata())) {
            return record;
        }
        GateResult gate = MetadataGate.check(merged);
        // v5.14.0 / W64-02：重建时必须带上判据汇总与套件数。此前用 11 参兼容构造器，
        // 补录一次就把 criteriaConclusion / suiteCount 冲成「未知」——叠加只该改门禁与
        // metadata，不该把同一次运行的其它事实一起抹掉。
        return new ReportRecord(record.projectId(), record.runId(), record.state(),
                gate.exportable(), gate.missing(), merged,
                record.passCount(), record.failCount(), record.skipCount(),
                record.startedAt(), record.finishedAt(), record.criteriaConclusion(),
                record.criteriaEvaluated(), record.criteriaMissingCount(), record.suiteCount());
    }

    /**
     * 只做键值合并，不重算门禁 —— 入库（{@code replace}）与读取（{@link #apply}）共用同一口径，
     * 保证「重新解析」不会把已补录的门禁结论冲回红。
     *
     * @param base      解析出的 metadata
     * @param overrides 补录项，允许为 null
     * @return 新 map；无补录时返回 base 本身
     */
    public static Map<String, String> merge(Map<String, String> base, Map<String, String> overrides) {
        if (base == null || overrides == null || overrides.isEmpty()) {
            return base;
        }
        Map<String, String> merged = new LinkedHashMap<>(base);
        overrides.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                merged.put(key, value);
            }
        });
        return merged;
    }
}
