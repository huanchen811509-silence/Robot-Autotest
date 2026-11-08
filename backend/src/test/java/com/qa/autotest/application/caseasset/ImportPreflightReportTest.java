package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** EDIM-05：预检报告四个读数（813/730/83/重复）、清单与 NOT_EVALUATED 不进通过计数。 */
class ImportPreflightReportTest {

    @Test
    void 四读数与清单_缺判据标NOT_EVALUATED() throws Exception {
        ImportPreflight report;
        try (InputStream in = Ed202Fixtures.snapshotStream()) {
            report = CaseImportService.preflight(in, "fixtures/ed202/ED202测试用例_V0.4.xlsx");
        }
        assertThat(report.sheetCount()).isEqualTo(7);
        assertThat(report.totalUniqueCases()).isEqualTo(813);
        assertThat(report.kanbanCorpusCases()).isEqualTo(730);
        assertThat(report.uecgseCases()).isEqualTo(83);
        assertThat(report.duplicateCodeCount()).isEqualTo(730);
        assertThat(report.sha256()).isEqualTo(Ed202Fixtures.SNAPSHOT_SHA256);

        assertThat(report.unmappedColumns())
                .contains("测试结果", "备注", "当前状态", "是否自动化");
        assertThat(report.missingColumns()).isNotEmpty();
        assertThat(report.sheets()).hasSize(7);

        // 缺判据：IM-FUN-079..084 六条预期结果为空 → NOT_EVALUATED，不进通过计数
        assertThat(report.notEvaluatedCount()).isEqualTo(6);
        assertThat(report.notEvaluatedCodes()).contains("IM-FUN-079", "IM-FUN-084");
        assertThat(report.missingCriteriaEntries()).hasSize(6)
                .allSatisfy(entry -> assertThat(entry).contains(ImportPreflight.NOT_EVALUATED));
        assertThat(report.evaluatedCount() + report.notEvaluatedCount())
                .isEqualTo(report.totalUniqueCases());
        assertThat(report.evaluatedCount()).isEqualTo(807);

        assertThat(report.toJson()).contains("813", "730", "83", "NOT_EVALUATED");
    }

    @Test
    void 构造缺判据用例_NOT_EVALUATED不进通过计数() throws Exception {
        ImportPreflight report = CaseImportService.preflight(
                new ByteArrayInputStream(syntheticMissingCriteria()), "synthetic.xlsx");
        assertThat(report.totalUniqueCases()).isEqualTo(1);
        assertThat(report.notEvaluatedCount()).isEqualTo(1);
        assertThat(report.evaluatedCount()).isZero();
        assertThat(report.notEvaluatedCodes()).containsExactly("SYN-001");
        assertThat(report.missingCriteriaEntries().get(0))
                .contains(ImportPreflight.NOT_EVALUATED);
    }

    /** 内存构造一个「预期结果为空」的单行台账，验证缺判据判定，不连数据库。 */
    private static byte[] syntheticMissingCriteria() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("synthetic");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("用例编号");
            header.createCell(1).setCellValue("用例名称");
            header.createCell(2).setCellValue("预置条件");
            header.createCell(3).setCellValue("测试步骤");
            header.createCell(4).setCellValue("预期结果");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("SYN-001");
            data.createCell(1).setCellValue("缺判据用例");
            data.createCell(2).setCellValue("无");
            data.createCell(3).setCellValue("1. 做点什么");
            // 第 4 列「预期结果」故意留空
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
