package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** EDIM-02：10 类别名映射正确；逐 sheet 编号/名称列 100% 命中；二级分类不再丢弃。 */
class CaseHeaderAliasTest {

    @Test
    void 十类别名映射到正确字段() {
        assertThat(CaseImportService.mappedField("测试编号")).isEqualTo("caseCode");
        assertThat(CaseImportService.mappedField("测试用例编号")).isEqualTo("caseCode");
        assertThat(CaseImportService.mappedField("测试用例")).isEqualTo("title");
        assertThat(CaseImportService.mappedField("测试名称")).isEqualTo("title");
        assertThat(CaseImportService.mappedField("二级分类")).isEqualTo("categoryL2");
        assertThat(CaseImportService.mappedField("用例分类")).isEqualTo("caseClass");
        assertThat(CaseImportService.mappedField("测试归类")).isEqualTo("testCategory");
        assertThat(CaseImportService.mappedField("CX7测试结果记录")).isEqualTo("ed202Coverage");
        assertThat(CaseImportService.mappedField("ED202覆盖情况")).isEqualTo("ed202Coverage");
        assertThat(CaseImportService.mappedField("测试资源")).isEqualTo("ed202Coverage");
    }

    @Test
    void 逐sheet编号与名称列百分百命中_二级分类不再丢弃() throws Exception {
        CaseImportService.ParsedWorkbook workbook;
        try (InputStream in = Ed202Fixtures.snapshotStream()) {
            workbook = CaseImportService.parse(in);
        }
        for (CaseImportService.ParsedSheet sheet : workbook.sheets()) {
            assertThat(sheet.columnMap())
                    .as("工作表 %s 的编号/名称列", sheet.sheetName())
                    .containsKeys("caseCode", "title");
        }
        CaseImportService.ParsedSheet imp = workbook.sheets().stream()
                .filter(sheet -> "Import详细测试流程".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        assertThat(imp.columnMap()).containsKey("categoryL2");
        assertThat(imp.rows().get(0).categoryL2()).isNotBlank();
        assertThat(imp.rows().get(0).caseClass()).isNotBlank();
    }
}
