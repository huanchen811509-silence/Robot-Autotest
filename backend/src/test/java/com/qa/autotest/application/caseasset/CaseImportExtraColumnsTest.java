package com.qa.autotest.application.caseasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OPT-02 / FR-49-05..09：四列补录别名、14 列对账、重复导入幂等、详情 DTO 脱敏。
 * 全部 DB-free（POI 内存工作簿 + Mockito），不依赖真实库。
 */
class CaseImportExtraColumnsTest {

    /** 参考《基本功能导入测试》5 张「详细测试流程」表：表头第 3 行、14 列。 */
    private static final String[] HEADERS_14 = {
            "用例编号", "一级分类", "二级分类", "用例名称", "用例分类", "优先级",
            "预置条件", "测试步骤", "A端命令", "B端命令", "交换机配置", "预期结果",
            "测试结果", "测试资源"};

    @Test
    void 四个新别名映射到独立字段() {
        assertEquals("aSideCommand", CaseImportService.mappedField("A端命令"));
        assertEquals("bSideCommand", CaseImportService.mappedField("B端命令"));
        assertEquals("switchConfig", CaseImportService.mappedField("交换机配置"));
        assertEquals("testResult", CaseImportService.mappedField("测试结果"));
    }

    @Test
    void 十四列对账命中十四且测试资源分流到独立列() throws Exception {
        CaseImportService.ParsedWorkbook workbook = CaseImportService.parse(
                new ByteArrayInputStream(workbook14()));
        CaseImportService.ParsedSheet sheet = workbook.sheets().get(0);
        assertEquals(14, sheet.mappedColumnCount(), "14 列必须全部命中别名");
        for (String field : List.of("aSideCommand", "bSideCommand", "switchConfig", "testResult")) {
            assertTrue(sheet.columnMap().containsKey(field), "缺少补录列字段 " + field);
        }
        CaseImportService.ImportRow row = sheet.rows().get(0);
        assertEquals("v8", row.aSideCommand());
        assertEquals("v9", row.bSideCommand());
        assertEquals("v10", row.switchConfig());
        assertEquals("v12", row.testResult());
        // FR-49-06：测试资源不再写 ed202_coverage，分流到 testResource（独立列）。
        assertEquals("v13", row.testResource());
        assertEquals("", row.ed202Coverage());
    }

    @Test
    void 同一文件重复导入不新增用例与版本() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AccessControlService access = mock(AccessControlService.class);
        PlatformProjectService platform = mock(PlatformProjectService.class);
        when(platform.id()).thenReturn(1L);
        when(access.isAdmin()).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(42L), eq(1L)))
                .thenReturn(List.of(Map.of("status", "COMMITTED")));
        GlobalCaseService service = new GlobalCaseService(jdbc, new ObjectMapper(), platform,
                access, mock(AuditService.class));

        GlobalCaseService.CommitResult result = service.commit(42L);

        assertTrue(result.replayed(), "重复导入必须命中 replay 分支");
        assertEquals(0, result.inserted() + result.updated() + result.unchanged()
                + result.deprecated(), "重复导入不得新增/更新/失效任何用例");
        // replay 分支只查一次批次、直接返回：jdbc 上再无任何交互（无 INSERT INTO test_case、
        // 无 INSERT INTO case_version），即用例总数与 case_version 数均不变。
        verify(jdbc).queryForList(anyString(), eq(42L), eq(1L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void 详情DTO序列化后无敏感键且凭据仅掩码() throws Exception {
        GlobalCaseService.CaseDetailView view = new GlobalCaseService.CaseDetailView(
                1L, "C-1", "EXT-1", "标题", "L1", "L2", "P0", "LX",
                "active", 1, "sheet", "sha",
                "cmdA", "cmdB", "cfg", "pass", "resource",
                9L, false, "GLOBAL_SYNC", "****");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(view);
        String lower = json.toLowerCase(Locale.ROOT);

        assertFalse(lower.contains("secret"), "响应不得含 secret");
        assertFalse(lower.contains("password"), "响应不得含明文口令字段");
        assertFalse(lower.contains("\"token\""), "响应不得含凭据 token");
        assertTrue(json.contains("keyMaskedCredential"), "凭据字段只允许 keyMasked 前缀");
        assertTrue(json.contains("externalId") && json.contains("syncJobId")
                && json.contains("localOverride"), "来源徽章三要素必须齐全");
        assertTrue(json.contains("aSideCommand") && json.contains("bSideCommand")
                && json.contains("switchConfig") && json.contains("testResult"),
                "四列补录必须进详情契约");

        JsonNode node = mapper.readTree(json);
        assertEquals("****", node.get("keyMaskedCredential").asText(), "凭据值只能是掩码");
        assertEquals("EXT-1", node.get("externalId").asText());
        assertEquals(9L, node.get("syncJobId").asLong());
    }

    /** 表头落在第 3 行（R1 标题 / R2 环境说明 / R3 表头 / R4 数据）。 */
    private static byte[] workbook14() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Import详细测试流程");
            sheet.createRow(0).createCell(0).setCellValue("基本功能导入测试");
            sheet.createRow(1).createCell(0).setCellValue("环境说明");
            Row header = sheet.createRow(2);
            for (int i = 0; i < HEADERS_14.length; i++) {
                header.createCell(i).setCellValue(HEADERS_14[i]);
            }
            Row data = sheet.createRow(3);
            for (int i = 0; i < HEADERS_14.length; i++) {
                data.createCell(i).setCellValue("v" + i);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
