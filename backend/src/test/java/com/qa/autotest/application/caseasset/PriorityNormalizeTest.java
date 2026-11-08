package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.caseasset.Priority;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** EDIM-02：T0/T1/T2 ↔ P0/P1/P2 归一，原值保留在 sourcePriority。 */
class PriorityNormalizeTest {

    @Test
    void 归一映射保留原值语义() {
        assertThat(CaseImportService.normalizePriority("T0")).isEqualTo("P0");
        assertThat(CaseImportService.normalizePriority("T1")).isEqualTo("P1");
        assertThat(CaseImportService.normalizePriority("T2")).isEqualTo("P2");
        assertThat(CaseImportService.normalizePriority("T3")).isEqualTo("P3");
        assertThat(CaseImportService.normalizePriority("P0")).isEqualTo("P0");
        assertThat(CaseImportService.normalizePriority("p1")).isEqualTo("P1");
        assertThat(CaseImportService.normalizePriority("")).isEmpty();
        assertThat(CaseImportService.normalizePriority(null)).isEmpty();
        assertThat(CaseImportService.normalizePriority("bogus")).isEqualTo("BOGUS");
    }

    @Test
    void 源文件_T0归一为P0且保留原值_P0原值不变() throws Exception {
        CaseImportService.ParsedWorkbook workbook;
        try (InputStream in = Ed202Fixtures.snapshotStream()) {
            workbook = CaseImportService.parse(in);
        }
        CaseImportService.ParsedSheet imp = workbook.sheets().stream()
                .filter(sheet -> "Import详细测试流程".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        CaseImportService.ImportRow first = imp.rows().stream()
                .filter(row -> "IM-FUN-001".equals(row.caseCode()))
                .findFirst().orElseThrow();
        assertThat(first.sourcePriority()).isEqualTo("T0");
        assertThat(first.priority()).isEqualTo("P0");
        assertThat(Priority.from(first.priority()).name()).isEqualTo("P0");

        CaseImportService.ParsedSheet xccl = workbook.sheets().stream()
                .filter(sheet -> "XCCL详细测试流程".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        CaseImportService.ImportRow xFirst = xccl.rows().get(0);
        assertThat(xFirst.sourcePriority()).isEqualTo("P0");
        assertThat(xFirst.priority()).isEqualTo("P0");
    }
}
