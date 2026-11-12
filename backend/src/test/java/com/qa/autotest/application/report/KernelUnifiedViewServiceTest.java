package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.KernelViewRepository.KernelLane;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双内核统一视图（FR-v5.4-06 / W54-06）。
 *
 * <p>判据：通过率<b>分别算</b>、两内核数据不合并、只有一个内核时如实说明。
 * 合并通过率是这里最容易犯的错——合起来好看，但看不出该修哪一边。
 */
class KernelUnifiedViewServiceTest {

    private static KernelUnifiedViewService service(List<KernelLane> lanes) {
        return new KernelUnifiedViewService((projectId, since) -> lanes,
                java.time.Clock.systemUTC());
    }

    @Test
    void 两内核各算各的通过率() {
        KernelUnifiedViewService.UnifiedView view = service(List.of(
                new KernelLane("pytest", 4, 3, 1, 40, 32, 120),
                new KernelLane("robot", 10, 9, 1, 100, 95, 300))).view(1L, 7);

        assertEquals(2, view.lanes().size());
        assertTrue(view.bothKernels());
        assertEquals(80.0, view.lanes().get(0).passRatePercent(), "pytest 32/40");
        assertEquals(95.0, view.lanes().get(1).passRatePercent(), "robot 95/100");
        assertTrue(view.note().contains("分别计算"), view.note());
    }

    @Test
    void 只有一个内核时如实说明而不是空白() {
        KernelUnifiedViewService.UnifiedView view = service(List.of(
                new KernelLane("robot", 10, 10, 0, 100, 100, 300))).view(1L, 7);
        assertFalse(view.bothKernels());
        assertTrue(view.note().contains("robot"), view.note());
        assertTrue(view.note().contains("只有一个内核"));
    }

    @Test
    void 没有执行记录时说明清楚() {
        KernelUnifiedViewService.UnifiedView view = service(List.of()).view(1L, 7);
        assertTrue(view.lanes().isEmpty());
        assertFalse(view.bothKernels());
        assertTrue(view.note().contains("没有执行记录"), view.note());
    }

    @Test
    void 没有用例结果的内核也不会被丢掉() {
        // run 跑了但产物阶段就失败，用例结果为 0——它仍要出现在视图里，
        // 否则页面看起来「这个内核没跑过」，而事实是跑了没出结果
        KernelLane lane = new KernelLane("pytest", 2, 0, 2, 0, 0, 5);
        KernelUnifiedViewService.UnifiedView view = service(List.of(lane)).view(1L, 7);
        assertEquals(1, view.lanes().size());
        assertEquals(0.0, lane.passRatePercent());
        assertEquals(2, lane.failed());
    }

    @Test
    void 时间窗只接受七与三十() {
        KernelUnifiedViewService svc = service(List.of());
        assertEquals(7, svc.view(1L, 7).windowDays());
        assertEquals(30, svc.view(1L, 30).windowDays());
        assertEquals(7, svc.view(1L, 15).windowDays(), "非法档位按 7 处理，并在返回体里说明");
        assertEquals(7, svc.view(1L, 0).windowDays());
    }
}
