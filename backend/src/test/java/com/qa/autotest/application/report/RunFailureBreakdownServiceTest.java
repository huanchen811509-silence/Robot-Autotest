package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.RunFailureBreakdownRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunFailureBreakdownServiceTest {

    /** 假仓储：只回放构造好的行与计数，不碰数据库。 */
    private static final class FakeRepository implements RunFailureBreakdownRepository {

        private final List<Row> rows;
        private final Counts counts;

        FakeRepository(List<Row> rows, Counts counts) {
            this.rows = rows;
            this.counts = counts;
        }

        @Override
        public List<Row> nonPassedRows(long projectId, String runId) {
            return List.copyOf(rows);
        }

        @Override
        public Counts counts(long projectId, String runId) {
            return counts;
        }
    }

    private static RunFailureBreakdownService serviceOf(List<RunFailureBreakdownRepository.Row> rows,
                                                        int total, int passed, int planned) {
        return new RunFailureBreakdownService(new FakeRepository(rows,
                new RunFailureBreakdownRepository.Counts(total, passed, planned)));
    }

    @Test
    void 环境关键字进环境侧不算产品失败() {
        RunFailureBreakdownService service = serviceOf(List.of(
                new RunFailureBreakdownRepository.Row("IM-NET-131", "pytest", "ERROR",
                        "Connection refused: 10.1.2.3:22"),
                new RunFailureBreakdownRepository.Row("IM-NET-132", "pytest", "FAIL",
                        "assert speed == 100")), 4, 2, 0);

        FailureBreakdown.Snapshot snapshot = service.of(1L, "RUN-1");
        assertEquals(1, snapshot.envFails());
        assertEquals(1, snapshot.productFails());
        assertEquals(3, snapshot.productDenominator(), "分母只含通过 2 + 产品 1");
        assertEquals(66.7, snapshot.productPassRate(), 1e-9);
        assertNull(snapshot.imbalance(), snapshot.imbalance());
    }

    @Test
    void 已派发未回只进计划未执行不进失败侧() {
        List<RunFailureBreakdownRepository.Row> rows = new ArrayList<>();
        rows.add(new RunFailureBreakdownRepository.Row("A-1", "robot", "FAIL", "assert x"));
        rows.add(new RunFailureBreakdownRepository.Row("A-2", "robot", "DISPATCHED", null));
        rows.add(new RunFailureBreakdownRepository.Row("A-3", "robot", "PENDING", null));
        RunFailureBreakdownService service = serviceOf(rows, 5, 2, 2);

        FailureBreakdown.Snapshot snapshot = service.of(1L, "RUN-2");
        assertEquals(1, snapshot.samples(), "只有 FAIL 进样本");
        assertEquals(2, snapshot.planned());
        assertTrue(snapshot.totalConsistent(), snapshot.imbalance());
        assertTrue(snapshot.balanced(), snapshot.imbalance());
    }

    @Test
    void 跳过结论归跳过不算失败也不算通过() {
        RunFailureBreakdownService service = serviceOf(List.of(
                new RunFailureBreakdownRepository.Row("A-1", "pytest", "SKIP", "plan-only")),
                2, 1, 0);

        FailureBreakdown.Snapshot snapshot = service.of(1L, "RUN-3");
        assertEquals(1, snapshot.skipped());
        assertEquals(0, snapshot.productFails());
        assertEquals(1, snapshot.productDenominator(), "分母只有通过 1 条");
        assertEquals(100.0, snapshot.productPassRate(), 1e-9);
    }

    @Test
    void 空run不抛异常且通过率为零() {
        FailureBreakdown.Snapshot snapshot = serviceOf(List.of(), 0, 0, 0).of(1L, "RUN-EMPTY");
        assertEquals(0, snapshot.samples());
        assertEquals(0.0, snapshot.productPassRate(), 1e-9);
        assertNull(snapshot.imbalance());
    }

    @Test
    void 失败原文为空时归未归类而不是产品缺陷() {
        RunFailureBreakdownService service = serviceOf(List.of(
                new RunFailureBreakdownRepository.Row("A-1", "robot", "FAIL", "")), 2, 1, 0);

        FailureBreakdown.Snapshot snapshot = service.of(1L, "RUN-4");
        assertEquals(1, snapshot.unknown(), "空原文不能算产品失败");
        assertEquals(0, snapshot.productFails());
        assertEquals(1, snapshot.productDenominator());
    }

    @Test
    void 第4章直通拿到同口径读数() {
        RunFailureBreakdownService service = serviceOf(List.of(
                new RunFailureBreakdownRepository.Row("A-1", "pytest", "FAIL",
                        "AssertionError: speed mismatch")), 3, 2, 0);

        ReportChapterBuilder.Chapter chapter = service.chapterOf(1L, "RUN-5");
        assertEquals(4, chapter.no());
        assertEquals(ReportChapterBuilder.Status.OK, chapter.status());
        assertTrue(chapter.summary().contains("产品失败 1 条"), chapter.summary());
    }
}
