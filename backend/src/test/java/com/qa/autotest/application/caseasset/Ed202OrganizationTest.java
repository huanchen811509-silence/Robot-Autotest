package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** SCEN-04：ED202 组织产物（对照表 + JSON）的一致性静态断言（不连数据库）。 */
class Ed202OrganizationTest {

    private static final String MD = "docs/plan/ED202一级分类-场景对照表-20261108.md";
    private static final String JSON =
            "docs/evidence/v3.0.1/v22/SCEN-04/ed202-organization.json";

    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of(relative);
    }

    /** 只数第一个表格块的数据行：跳过块首表头行与分隔行。 */
    private static int tableDataRows(String text) {
        boolean inFirstBlock = false;
        boolean firstBlockDone = false;
        int lineNo = 0;
        int dataRows = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line.strip().startsWith("|")) {
                if (firstBlockDone) {
                    continue;
                }
                inFirstBlock = true;
                lineNo++;
                boolean separator = line.strip().matches("[\\s|:\\-]+") && line.contains("-");
                if (lineNo > 1 && !separator) {
                    dataRows++;
                }
            } else if (inFirstBlock) {
                firstBlockDone = true;
            }
        }
        return dataRows;
    }

    @Test
    void caseSets七个sheet且totals分账成立() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(repoFile(JSON)));
        assertThat(root.path("project").path("name").asText()).isEqualTo("ED202-COMMON");
        JsonNode caseSets = root.path("caseSets");
        assertThat(caseSets.isArray()).isTrue();
        assertThat(caseSets.size()).isEqualTo(7);
        for (JsonNode cs : caseSets) {
            assertThat(cs.path("sheet").asText()).isNotBlank();
            assertThat(cs.path("caseCount").asInt()).isGreaterThan(0);
        }
        JsonNode totals = root.path("totals");
        int dashboard = totals.path("dashboard").asInt();
        int adapter = totals.path("adapter").asInt();
        int uniqueTotal = totals.path("uniqueTotal").asInt();
        assertThat(dashboard).isEqualTo(730);
        assertThat(adapter).isEqualTo(83);
        assertThat(uniqueTotal).isEqualTo(813);
        assertThat(dashboard + adapter).isEqualTo(uniqueTotal);
    }

    @Test
    void 场景数等于对照表数据行数且每个场景有来源() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(repoFile(JSON)));
        JsonNode scenarios = root.path("scenarios");
        assertThat(scenarios.isArray()).isTrue();
        int mdRows = tableDataRows(Files.readString(repoFile(MD)));
        assertThat(mdRows).isGreaterThan(0);
        assertThat(scenarios.size()).isEqualTo(mdRows);
        for (JsonNode sc : scenarios) {
            assertThat(sc.path("name").asText()).isNotBlank();
            JsonNode sources = sc.path("sources");
            assertThat(sources.isArray()).isTrue();
            assertThat(sources.size()).isGreaterThanOrEqualTo(1);
            for (JsonNode src : sources) {
                assertThat(src.path("sheet").asText()).isNotBlank();
                assertThat(src.path("caseCount").asInt()).isGreaterThan(0);
            }
            assertThat(sc.path("caseCount").asInt()).isGreaterThan(0);
        }
    }

    @Test
    void 对照表表头与来源sheet标记齐备() throws Exception {
        String md = Files.readString(repoFile(MD));
        assertThat(md).contains("| 一级分类 | 场景名 | 来源 sheet | 该来源用例数 | 合并依据 |");
        assertThat(md).contains("跨 sheet 同名一级分类合并");
        assertThat(md).contains("测试用例看板");
    }
}
