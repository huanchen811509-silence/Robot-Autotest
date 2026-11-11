package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「当前测试环境」分节解析单测（v4.7.0 / FR-v4.7-01..02、v4.9.3 采集与状态）。
 *
 * <p>样本工作簿有四类易错点，这里逐条钉住：
 * 节标题识别、组合表头（BMC / 账户）不被误读、交换机无主机名时用「角色」当名称、
 * 端口状态与 Mb/s → Gbps 的换算。
 */
class AssetImportServiceTest {

    @Test
    @DisplayName("服务器/交换机/网卡端口三节都能落位，端口带上状态与速率")
    void parsesSectionsWithCollectedFacts() throws Exception {
        AssetImportService.ParsedAssets parsed = AssetImportService.parse(
                new ByteArrayInputStream(workbook()));

        assertThat(parsed.devices()).extracting(AssetImportService.DeviceRow::name)
                .containsExactly("gpu01", "800G-RoCE-A");
        AssetImportService.DeviceRow server = parsed.devices().get(0);
        assertThat(server.deviceType()).isEqualTo("AI计算节点");
        assertThat(server.managementIp()).isEqualTo("10.0.0.11");
        assertThat(server.externalId()).isEqualTo("10.0.0.11");
        assertThat(server.operatingSystem()).isEqualTo("Ubuntu 24.04");
        assertThat(server.kernel()).isEqualTo("6.8.0-45");
        assertThat(server.bmcAddress()).isEqualTo("BMC-01");
        assertThat(server.account()).isEqualTo("admin");
        assertThat(server.environment()).isEqualTo("当前测试环境");

        AssetImportService.DeviceRow switchRow = parsed.devices().get(1);
        assertThat(switchRow.deviceType()).isEqualTo("交换机");
        assertThat(switchRow.model()).isEqualTo("SZ-SW800G");
        assertThat(switchRow.serialNo()).isEqualTo("SN-001");

        assertThat(parsed.ports()).hasSize(2);
        AssetImportService.PortRow up = parsed.ports().get(0);
        assertThat(up.deviceName()).isEqualTo("gpu01");
        assertThat(up.portName()).isEqualTo("ens16f0");
        assertThat(up.portStatus()).isEqualTo("up");
        assertThat(up.speedGbps()).isEqualTo(200);
        assertThat(up.mtu()).isEqualTo(9000);
        assertThat(up.macAddress()).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(up.ipAddress()).isEqualTo("172.30.1.11");
        assertThat(up.nicName()).isEqualTo("EHTech TUNAN-7");

        AssetImportService.PortRow down = parsed.ports().get(1);
        assertThat(down.portStatus()).isEqualTo("down");
        assertThat(down.speedGbps()).isEqualTo(400);
        assertThat(down.macAddress()).isNull();
    }

    @Test
    @DisplayName("汇总分节（RDMA 映射 / 环境事实）不产生资产；一行多个网口拆成多条端口")
    void skipsSummarySectionsAndSplitsMultiLinePorts() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("当前测试环境");
            int r = 0;
            row(sheet, r++, "1. 服务器（1 台）");
            row(sheet, r++, "主机名", "管理IP", "角色");
            row(sheet, r++, "gpu01", "10.0.0.11", "AI计算节点");
            row(sheet, r++, "2. RDMA 端点绑定（实测；用例 A/B 端即取此处）");
            row(sheet, r++, "主机", "RDMA设备", "网口", "数据IP", "MTU");
            row(sheet, r++, "gpu01", "mlx5_0", "ens17np0", "172.30.1.1", "9000");
            row(sheet, r++, "3. 其他环境事实");
            row(sheet, r++, "perftest", "24.01.0");
            row(sheet, r++, "4. 网卡与端口信息");
            row(sheet, r++, "主机", "网口", "当前MAC", "状态", "速率(Mb/s)", "MTU", "IP地址");
            row(sheet, r++, "gpu01", "ens20f0np0\nens20f1np1",
                    "aa:bb:cc:dd:ee:01\naa:bb:cc:dd:ee:02", "up\nup",
                    "25000\n25000", "1500\n1500", "—\n—");
            row(sheet, r++, "gpu01", "（无网口）");
            wb.write(out);

            AssetImportService.ParsedAssets parsed = AssetImportService.parse(
                    new ByteArrayInputStream(out.toByteArray()));

            assertThat(parsed.devices()).extracting(AssetImportService.DeviceRow::name)
                    .containsExactly("gpu01");
            assertThat(parsed.ports()).extracting(AssetImportService.PortRow::portName)
                    .containsExactly("ens20f0np0", "ens20f1np1");
            assertThat(parsed.ports()).extracting(AssetImportService.PortRow::macAddress)
                    .containsExactly("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02");
            assertThat(parsed.ports()).allMatch(port -> port.ipAddress() == null);
        }
    }

    @Test
    @DisplayName("空工作簿给出可解释的告警，不编造资产")
    void warnsWhenNoEnvironmentSection() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("说明").createRow(0).createCell(0).setCellValue("这个工作簿没有环境区");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            AssetImportService.ParsedAssets parsed = AssetImportService.parse(
                    new ByteArrayInputStream(out.toByteArray()));
            assertThat(parsed.devices()).isEmpty();
            assertThat(parsed.ports()).isEmpty();
            assertThat(parsed.warnings()).anyMatch(text -> text.contains("未识别到"));
        }
    }

    private static byte[] workbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("当前测试环境");
            int r = 0;
            row(sheet, r++, "1. 服务器（1 台）");
            row(sheet, r++, "主机名", "管理IP", "角色", "型号", "操作系统", "内核",
                    "BMC地址 / 映射地址 / 账户", "账户 / 权限 / 凭据引用");
            row(sheet, r++, "gpu01", "10.0.0.11", "AI计算节点", "X8", "Ubuntu 24.04", "6.8.0-45",
                    "BMC-01", "admin");
            row(sheet, r++, "2. 交换机（1 台）");
            row(sheet, r++, "角色", "管理IP", "型号", "软件版本", "序列号");
            row(sheet, r++, "800G-RoCE-A", "10.0.0.21", "SZ-SW800G", "5.9.1", "SN-001");
            row(sheet, r++, "3. 网卡与端口信息");
            row(sheet, r++, "主机", "网卡型号 / 类别", "网口", "当前MAC", "状态",
                    "速率(Mb/s)", "MTU", "IP地址");
            row(sheet, r++, "gpu01", "EHTech TUNAN-7\n200G RoCE", "ens16f0",
                    "aa:bb:cc:dd:ee:ff", "up", "200000", "9000", "172.30.1.11");
            row(sheet, r++, "gpu01", "EHTech TUNAN-7\n200G RoCE", "ens17np0", "—", "down",
                    "400000", "9000", "172.30.2.11");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
