package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 导出前质量自检（FR-v5.6-27）。
 *
 * <p>三项机检的语义必须分清：<b>缺 metadata</b> 与<b>失败没原因</b> 是两类问题，
 * 都必须点名到具体条数/键名——只说「不合格」，用户还得自己找。
 */
class ExportQualityGateTest {

    private static final long PROJECT = 1L;
    private static final String RUN = "RUN-1";

    @Test
    @DisplayName("十项齐全、编号与失败原因都在 → FINAL")
    void 全过为终稿() {
        ExportService.QualityGate gate = ExportService.qualityGate(
                report(), completeMetadata(), tree(
                        item("IM-NET-052", "PASS", 1200L, null),
                        item("IM-RDM-022", "FAIL", 4820L, "AssertionError: PFC 使能优先级期望 3 实际 5")));

        assertTrue(gate.passed());
        assertEquals(ExportService.QualityGate.FINAL, gate.label());
        assertTrue(gate.missing().isEmpty());
    }

    @Test
    @DisplayName("metadata 缺项 → DRAFT，并把缺的键名原样列出来")
    void 缺元数据为草稿() {
        GateResult metadata = MetadataGate.check(Map.of("CASE_ID", "IM-NET-052"));

        ExportService.QualityGate gate = ExportService.qualityGate(report(), metadata, tree());

        assertFalse(gate.passed());
        assertEquals(ExportService.QualityGate.DRAFT, gate.label());
        assertTrue(gate.missing().contains("DUT"), "缺项要能直接看出缺哪个键");
    }

    @Test
    @DisplayName("失败用例没有失败原因 → DRAFT，点名条数")
    void 失败无原因为草稿() {
        ExportService.QualityGate gate = ExportService.qualityGate(report(), completeMetadata(),
                tree(item("IM-RDM-022", "FAIL", 4820L, "  "), item("IM-RDM-021", "ERROR", 1L, null),
                        item("IM-NET-052", "PASS", 1200L, null)));

        assertFalse(gate.passed());
        // 通过的用例没有失败原因是对的，不该被算成缺项
        assertTrue(gate.missing().stream().anyMatch(text -> text.contains("失败原因缺失 2 条")),
                "实际缺项: " + gate.missing());
    }

    @Test
    @DisplayName("用例没有编号 → DRAFT，点名条数")
    void 缺编号为草稿() {
        ExportService.QualityGate gate = ExportService.qualityGate(report(), completeMetadata(),
                tree(item(null, "PASS", 10L, null), item("", "PASS", 10L, null)));

        assertFalse(gate.passed());
        assertTrue(gate.missing().stream().anyMatch(text -> text.contains("用例编号缺失 2 条")));
    }

    @Test
    @DisplayName("失败原因缺失判定不看大小写：fail / Fail / FAIL 一视同仁")
    void 判定格大小写不敏感() {
        ExportService.QualityGate gate = ExportService.qualityGate(report(), completeMetadata(),
                tree(item("C-1", "fail", 1L, null)));

        assertFalse(gate.passed());
    }

    private static ReportExportService.CaseDetailRow item(String code, String verdict, Long duration,
                                                          String failureMsg) {
        return new ReportExportService.CaseDetailRow(code, verdict, duration, failureMsg, "NONE");
    }

    private static ReportTreeService.TreeSuite tree(ReportExportService.CaseDetailRow... items) {
        List<ReportTreeService.TreeCase> cases = new java.util.ArrayList<>();
        for (ReportExportService.CaseDetailRow item : items) {
            cases.add(new ReportTreeService.TreeCase("k-" + item.code(), item.code(),
                    item.verdict(), item.durationMs() == null ? 0L : item.durationMs(),
                    item.failureMsg(), item.command(), "NONE", null, List.of()));
        }
        return new ReportTreeService.TreeSuite("root", "FAIL", List.of(), cases);
    }

    private static GateResult completeMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : MetadataGate.REQUIRED) {
            metadata.put(key, "v-" + key);
        }
        return MetadataGate.check(metadata);
    }

    private static Map<String, String> rawMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : MetadataGate.REQUIRED) {
            metadata.put(key, "v-" + key);
        }
        return metadata;
    }

    private static ReportRecord report() {
        Instant started = Instant.parse("2026-11-13T10:00:00Z");
        return new ReportRecord(PROJECT, RUN, "finished", true, List.of(), rawMetadata(),
                8, 2, 0, started, started.plusSeconds(60));
    }
}
