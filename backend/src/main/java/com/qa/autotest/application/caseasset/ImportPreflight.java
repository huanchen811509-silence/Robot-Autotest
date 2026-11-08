package com.qa.autotest.application.caseasset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * EDIM-05 预检报告（纯数据、无数据库依赖，Jackson 可直接序列化为 JSON）。
 *
 * <p>四个读数：{@code totalUniqueCases}（813）／{@code kanbanCorpusCases}（730，六张明细表并集，
 * 即看板口径）／{@code uecgseCases}（83，UECGSE适配，与看板零交集，不得被静默丢弃）／
 * {@code duplicateCodeCount}（跨 sheet 重复编号数）。另有「未映射列」「缺列」「缺判据条目」清单。
 *
 * <p>缺判据条目一律标 {@link #NOT_EVALUATED}，只进 {@code notEvaluatedCount}，不进
 * {@code evaluatedCount}（通过计数分母），避免用「没判据」的用例凑通过率。
 */
public record ImportPreflight(
        String sourceFile,
        String sha256,
        int sheetCount,
        int totalUniqueCases,
        int kanbanCorpusCases,
        int uecgseCases,
        int duplicateCodeCount,
        int duplicateRowCount,
        int evaluatedCount,
        int notEvaluatedCount,
        List<SheetReport> sheets,
        List<String> unmappedColumns,
        List<String> missingColumns,
        List<String> missingCriteriaEntries,
        List<String> notEvaluatedCodes) {

    /** 缺判据状态：不进通过计数。 */
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    /** 逐 sheet 对账项。 */
    public record SheetReport(
            String sheetName,
            int headerRowNum,
            int mappedColumnCount,
            int rowCount,
            List<String> unmappedColumns,
            List<String> missingRequiredColumns) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 导出为 JSON 字符串（返回对象本身亦可直接交给 Jackson 序列化）。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("预检报告序列化失败", ex);
        }
    }
}
