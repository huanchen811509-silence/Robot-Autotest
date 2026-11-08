package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/** EDIM-03：测试步骤按换行编号拆入 List&lt;String&gt;，并与源表行数比对。纯解析，不连数据库。 */
class CaseStepIngestTest {

    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+\\s*[.、)．].*");

    @Test
    void 拆分编号步骤_续行并入当前步骤() {
        List<String> steps = CaseImportService.splitSteps(
                "1. 第一步\n继续行 A\n2. 第二步\n3. 第三步");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).isEqualTo("第一步\n继续行 A");
        assertThat(steps.get(1)).isEqualTo("第二步");
        assertThat(steps.get(2)).isEqualTo("第三步");
        assertThat(CaseImportService.splitSteps("")).isEmpty();
        assertThat(CaseImportService.splitSteps(null)).isEmpty();
    }

    @Test
    void 抽查五条用例_步骤数与源表编号行一致() throws Exception {
        CaseImportService.ParsedWorkbook parsed;
        try (InputStream in = Ed202Fixtures.snapshotStream()) {
            parsed = CaseImportService.parse(in);
        }
        CaseImportService.ParsedSheet imp = parsed.sheets().stream()
                .filter(sheet -> "Import详细测试流程".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        Map<String, CaseImportService.ImportRow> byCode = new HashMap<>();
        for (CaseImportService.ImportRow row : imp.rows()) {
            byCode.put(row.caseCode(), row);
        }

        int checked = 0;
        try (InputStream in = Ed202Fixtures.snapshotStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheet("Import详细测试流程");
            DataFormatter formatter = new DataFormatter();
            for (int excelRow : new int[]{4, 5, 6, 7, 8}) {
                Row row = sheet.getRow(excelRow - 1);
                String code = formatter.formatCellValue(row.getCell(0)).trim();
                String rawSteps = formatter.formatCellValue(row.getCell(7)).trim();
                int numberedLines = 0;
                for (String line : rawSteps.split("\\R", -1)) {
                    if (NUMBERED.matcher(line).matches()) {
                        numberedLines++;
                    }
                }
                CaseImportService.ImportRow parsedRow = byCode.get(code);
                assertThat(parsedRow).as("源表 %s 应被解析", code).isNotNull();
                assertThat(parsedRow.steps())
                        .as("%s 步骤数应与源表编号行数一致", code)
                        .hasSize(numberedLines);
                assertThat(parsedRow.sourceRow()).isEqualTo(excelRow);
                assertThat(parsedRow.steps()).allSatisfy(step ->
                        assertThat(step.startsWith("1.")).isFalse());
                checked++;
            }
        }
        assertThat(checked).isEqualTo(5);
        // 全表步骤总数必须 > 0（commit 会写 test_case_step）
        long totalSteps = imp.rows().stream().mapToLong(row -> row.steps().size()).sum();
        assertThat(totalSteps).isPositive();
    }
}
