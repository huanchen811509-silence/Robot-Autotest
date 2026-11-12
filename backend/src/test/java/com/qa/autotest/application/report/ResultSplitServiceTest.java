package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.ExecModeStatsRepository.ModeCount;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动 / 手工分离统计（FR-v5.4-05 / W54-05）。
 *
 * <p>三条判据：合计 = 自动 + 手工；合计必须标注含手工条数；未知执行方式不得静默并入自动。
 * 第三条看着吹毛求疵，但它挡的是「自动通过率里混进了来路不明的数据」——
 * 那种混入不会报错，只会让数字慢慢失去意义。
 */
class ResultSplitServiceTest {

    private static ResultSplitService service() {
        return new ResultSplitService((projectId, since, runId) -> List.of(),
                java.time.Clock.systemUTC());
    }

    @Test
    void 合计等于自动加手工() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount("auto", 80, 72),
                new ModeCount("manual", 20, 15)));

        assertEquals(80, snapshot.auto().total());
        assertEquals(20, snapshot.manual().total());
        assertEquals(100, snapshot.total().total());
        assertEquals(87, snapshot.total().passed());
        assertTrue(snapshot.consistent());
        assertEquals(87.0, snapshot.total().passRatePercent());
        assertEquals(90.0, snapshot.auto().passRatePercent());
        assertEquals(75.0, snapshot.manual().passRatePercent());
    }

    @Test
    void 合计标注含手工条数() {
        ResultSplitService.Snapshot withManual = service().summarize(List.of(
                new ModeCount("auto", 80, 72), new ModeCount("manual", 20, 15)));
        assertTrue(withManual.manualNote().contains("含手工 20 条"), withManual.manualNote());
        assertTrue(withManual.manualNote().contains("自动 80 条"));

        ResultSplitService.Snapshot autoOnly = service().summarize(List.of(
                new ModeCount("auto", 80, 72)));
        assertTrue(autoOnly.manualNote().contains("全部为自动"));
        assertFalse(autoOnly.hasManual());
    }

    @Test
    void 对外口径只报自动() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount("auto", 80, 72), new ModeCount("manual", 20, 15)));
        assertEquals(80, snapshot.external().total(),
                "对外默认只报自动，手工要显式勾选");
        assertEquals(90.0, snapshot.external().passRatePercent());
    }

    @Test
    void 未知执行方式单列不并入自动() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount("auto", 10, 10),
                new ModeCount("hybrid", 5, 5)));

        assertEquals(10, snapshot.auto().total(), "hybrid 不能算进 auto");
        assertEquals(1, snapshot.unknownModes().size());
        assertTrue(snapshot.unknownModes().get(0).startsWith("hybrid"));
        assertTrue(snapshot.consistent(), "合计仍应只等于 auto + manual，未知项不进合计");
    }

    @Test
    void 空数据时全部为零不报除零() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of());
        assertEquals(0, snapshot.total().total());
        assertEquals(0.0, snapshot.total().passRatePercent());
        assertTrue(snapshot.consistent());
        assertFalse(snapshot.hasManual());
    }

    @Test
    void 只有手工数据时也成立() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount("manual", 6, 6)));
        assertEquals(0, snapshot.auto().total());
        assertEquals(6, snapshot.total().total());
        assertEquals(100.0, snapshot.total().passRatePercent());
        assertTrue(snapshot.hasManual());
    }

    @Test
    void 执行方式为_null_时按自动处理() {
        // test_result.exec_mode 在 V11_55 之前没有这列，历史行回填为 auto；
        // 万一有 null（例如直接 SQL 灌进去的），按 auto 处理并计入合计
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount(null, 4, 3)));
        assertEquals(4, snapshot.auto().total());
        assertTrue(snapshot.unknownModes().isEmpty());
    }

    @Test
    void 大小写与空白不影响归类() {
        ResultSplitService.Snapshot snapshot = service().summarize(List.of(
                new ModeCount(" AUTO ", 4, 4),
                new ModeCount("Manual", 2, 1)));
        assertEquals(4, snapshot.auto().total());
        assertEquals(2, snapshot.manual().total());
    }
}
