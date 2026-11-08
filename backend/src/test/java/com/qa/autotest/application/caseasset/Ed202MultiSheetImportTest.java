package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** EDIM-01：7 个 sheet 全部解析，日志列出各 sheet 表头行号与命中列数。纯解析，不连数据库。 */
class Ed202MultiSheetImportTest {

    @Test
    void 七个sheet全部解析_日志列出表头行号与命中列数() throws Exception {
        CaseImportService.ParsedWorkbook workbook;
        try (InputStream in = Ed202Fixtures.snapshotStream()) {
            workbook = CaseImportService.parse(in);
        }
        assertThat(workbook.sheets()).hasSize(7);
        assertThat(workbook.sheets())
                .extracting(CaseImportService.ParsedSheet::sheetName)
                .containsExactly("测试用例看板", "Import详细测试流程", "XCCL详细测试流程",
                        "AI参数网_详细测试流程", "存储_详细测试流程", "HCI详细测试流程",
                        "UECGSE适配");

        int totalRows = 0;
        for (CaseImportService.ParsedSheet sheet : workbook.sheets()) {
            System.out.println("[EDIM-01] sheet=" + sheet.sheetName()
                    + " headerRow=R" + sheet.headerRowNum()
                    + " mappedColumns=" + sheet.mappedColumnCount()
                    + " rows=" + sheet.rowCount());
            assertThat(sheet.headerRowNum()).isPositive();
            assertThat(sheet.mappedColumnCount()).isPositive();
            assertThat(sheet.rowCount()).isPositive();
            totalRows += sheet.rowCount();
        }
        // 730（看板）+ 730（六张明细并集）+ 83（UECGSE适配）= 1543 个数据行
        assertThat(totalRows).isEqualTo(1543);

        // 看板逐项清单表头在 R15，证明是「逐 sheet 独立 findHeaderRow」而非统一 R1
        assertThat(workbook.sheets().get(0).headerRowNum()).isEqualTo(15);
        assertThat(workbook.sheets().get(1).headerRowNum()).isEqualTo(3);

        // 硬判据：UECGSE适配 83 条不得因「不在看板」被静默丢弃
        CaseImportService.ParsedSheet uec = workbook.sheets().stream()
                .filter(sheet -> "UECGSE适配".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        assertThat(uec.rowCount()).isEqualTo(83);
        assertThat(uec.columnMap()).containsKeys("caseCode", "title");
    }
}
