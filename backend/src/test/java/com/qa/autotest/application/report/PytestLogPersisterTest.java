package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * pytest 用例日志落盘与切片（FR-v3.9-03 ④：修掉「pytest 日志抽屉为空」）。
 *
 * <p>要点三条：
 * ① 全局序号连续、跨用例不重置（抽屉按 log_offset/log_span 切片，序号一乱就串行）；
 * ② 没有输出的用例**不登记区间**（offset=-1、span=null），抽屉显示「暂无日志」
 *    而不是给一段假区间；
 * ③ JSONL 里中文与控制字符原样保留，切片读回来逐字一致——命令与日志比对靠的就是这个。
 */
class PytestLogPersisterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Path report(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("pytest-report.json"), json);
        return dir;
    }

    @Test
    @DisplayName("stage 文本按全局序号摊平，区间连续且跨用例不重置")
    void persistsGlobalSequence(@TempDir Path dir) throws Exception {
        report(dir, """
                {"tests":[
                  {"nodeid":"t.py::test_a","outcome":"passed",
                   "call":{"stdout":"$ uname -r\\nexit=0\\n"}},
                  {"nodeid":"t.py::test_b","outcome":"failed",
                   "call":{"stderr":"boom\\n"},"teardown":{"longrepr":"Traceback: x"}}
                ]}""");

        PytestLogPersister.Spans spans = new PytestLogPersister(mapper).persist(dir);

        assertEquals(0L, spans.offsetOf("t.py::test_a"));
        assertEquals("0:2", spans.spanOf("t.py::test_a"));
        // 第二条从 2 开始，不重新计数
        assertEquals(2L, spans.offsetOf("t.py::test_b"));
        assertEquals("2:4", spans.spanOf("t.py::test_b"));

        List<LogXmlReader.LogXmlLine> all = new JsonlLogReader(mapper)
                .read(dir.resolve(PytestLogPersister.LOG_FILE), 0, 4);
        assertEquals(4, all.size());
        assertEquals("$ uname -r", all.get(0).message());
        assertEquals("exit=0", all.get(1).message());
        assertEquals("boom", all.get(2).message());
        assertEquals("ERROR", all.get(3).level(), "longrepr 记为 ERROR，便于抽屉着色");
    }

    @Test
    @DisplayName("没有输出的用例不登记区间：offset=-1、span=null")
    void emptyCaseGetsNoSpan(@TempDir Path dir) throws Exception {
        report(dir, """
                {"tests":[
                  {"nodeid":"t.py::test_silent","outcome":"passed"},
                  {"nodeid":"t.py::test_cmd","outcome":"passed",
                   "call":{"stdout":"$ id -un\\n"}}
                ]}""");

        PytestLogPersister.Spans spans = new PytestLogPersister(mapper).persist(dir);

        assertEquals(-1L, spans.offsetOf("t.py::test_silent"));
        assertNull(spans.spanOf("t.py::test_silent"));
        // 空用例不占序号：第二条仍然是 0
        assertEquals("0:1", spans.spanOf("t.py::test_cmd"));
    }

    @Test
    @DisplayName("区间切片按 seq 过滤，只返回请求的那几行")
    void slicesBySequence(@TempDir Path dir) throws Exception {
        report(dir, """
                {"tests":[
                  {"nodeid":"t.py::test_a","outcome":"passed",
                   "call":{"stdout":"line-0\\nline-1\\nline-2\\nline-3\\n"}}
                ]}""");

        new PytestLogPersister(mapper).persist(dir);
        List<LogXmlReader.LogXmlLine> slice = new JsonlLogReader(mapper)
                .read(dir.resolve(PytestLogPersister.LOG_FILE), 1, 3);

        assertEquals(2, slice.size());
        assertEquals("line-1", slice.get(0).message());
        assertEquals("line-2", slice.get(1).message());
    }

    @Test
    @DisplayName("中文与特殊字符逐字保留（命令比对依赖这一点）")
    void keepsUnicodeVerbatim(@TempDir Path dir) throws Exception {
        report(dir, """
                {"tests":[
                  {"nodeid":"t.py::test_cn","outcome":"passed",
                   "call":{"stdout":"$ ip -o link show | wc -l\\n中文输出\\t带制表符\\n"}}
                ]}""");

        new PytestLogPersister(mapper).persist(dir);
        List<LogXmlReader.LogXmlLine> lines = new JsonlLogReader(mapper)
                .read(dir.resolve(PytestLogPersister.LOG_FILE), 0, 2);

        assertEquals("$ ip -o link show | wc -l", lines.get(0).message());
        assertEquals("中文输出\t带制表符", lines.get(1).message());
    }

    @Test
    @DisplayName("报告文件缺失时不抛异常、返回空区间表（日志缺失不该阻断入库）")
    void missingReportIsTolerated(@TempDir Path dir) {
        PytestLogPersister.Spans spans = new PytestLogPersister(mapper).persist(dir);
        assertTrue(spans.byNodeId().isEmpty());
        assertEquals(-1L, spans.offsetOf("any"));
        assertNull(spans.spanOf("any"));
    }
}
