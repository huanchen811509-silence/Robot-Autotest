package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.report.ReportCaseRow;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.22.0 / FR-v5.22-02（W72-03）：用例明细进快照。
 *
 * <p>守三条容易走偏的边界：
 * ① 「没采集」（{@code null}）与「采集到 0 条」（空列表）**不能合并**——
 *    合并之后对外文件分不清「这份报告没记明细」还是「这次执行真的没有用例」；
 * ② 明细含凭据时**拒绝冻结**，不做「遮蔽后照发」；
 * ③ 拒绝时的报错只能报用例编号，不能回显命中内容（否则凭据从报错里漏出去）。
 */
class ReportSnapshotCasesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportSnapshotService snapshots = new ReportSnapshotService(mapper);

    private static ReportRecord source() {
        return new ReportRecord(7L, "RUN-W2-0001", "FINISHED", true, List.of(),
                Map.of("SW_BUILD", "build-2.0"),
                1, 1, 1, Instant.parse("2026-11-06T08:00:00Z"),
                Instant.parse("2026-11-06T08:10:00Z"));
    }

    private static List<ReportCaseRow> rows() {
        return List.of(
                new ReportCaseRow("IM-CER-008", "PFC 使能优先级", "FAIL", 4820L,
                        "AssertionError: 期望 3 实际 5", "ethtool -k ens17np0"),
                new ReportCaseRow("IM-NET-052", "RDMA 建链", "PASS", 1200L, null, null));
    }

    @Test
    @DisplayName("明细写进快照根键 cases，条数与内容都落盘")
    void casesAreFrozenIntoSnapshot() {
        ReportSnapshot snapshot = snapshots.build(source(), 1, null, null, rows());
        assertTrue(snapshot.json().contains("\"cases\""), "快照缺 cases 键");
        assertTrue(snapshot.json().contains("IM-CER-008"));
        assertTrue(snapshot.json().contains("PFC 使能优先级"));
        assertTrue(snapshot.json().contains("\"durationMs\":4820"));
        assertEquals(2, countOccurrences(snapshot.json(), "\"caseCode\""), "两条用例应各有一条 caseCode");
    }

    @Test
    @DisplayName("cases 为 null 时不写该键：历史报告与「没采集」不能被当成空明细")
    void nullCasesMeansKeyAbsent() {
        ReportSnapshot snapshot = snapshots.build(source(), 1, null, null, null);
        assertFalse(snapshot.json().contains("\"cases\""), "null 不该写出 cases 键");
    }

    @Test
    @DisplayName("cases 为空列表时写空数组：表示「采集了，确实 0 条」")
    void emptyCasesMeansEmptyArray() {
        ReportSnapshot snapshot = snapshots.build(source(), 1, null, null, List.of());
        assertTrue(snapshot.json().contains("\"cases\":[]"), "空列表应写成空数组");
    }

    @Test
    @DisplayName("失败信息含凭据形态时拒绝冻结，报错只报用例编号")
    void credentialInFailureMsgBlocksFreeze() {
        List<ReportCaseRow> bad = List.of(new ReportCaseRow("IM-CER-009", "登录", "FAIL", 100L,
                "login failed: password=Adm1n@2026", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> snapshots.build(source(), 1, null, null, bad));
        assertTrue(ex.getMessage().contains("IM-CER-009"), "报错应指明是哪条用例");
        assertFalse(ex.getMessage().contains("Adm1n@2026"), "报错不得回显口令本身");
    }

    @Test
    @DisplayName("命令原文含 token 赋值同样拒绝冻结（不能只查失败信息）")
    void credentialInCommandTextBlocksFreeze() {
        List<ReportCaseRow> bad = List.of(new ReportCaseRow("IM-CER-010", "采集", "PASS", 100L,
                null, "curl -H 'Authorization: Bearer abc.def.ghi' http://x"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> snapshots.build(source(), 1, null, null, bad));
        assertTrue(ex.getMessage().contains("IM-CER-010"));
        assertFalse(ex.getMessage().contains("abc.def.ghi"));
    }

    @Test
    @DisplayName("命令原文本身不是凭据时不拦（对外脱敏在导出期做，不在冻结期删事实）")
    void plainCommandIsAllowed() {
        ReportSnapshot snapshot = snapshots.build(source(), 1, null, null, rows());
        assertTrue(snapshot.json().contains("ethtool -k ens17np0"),
                "快照是内部事实，命令原文应保留；对外导出时才脱敏");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
