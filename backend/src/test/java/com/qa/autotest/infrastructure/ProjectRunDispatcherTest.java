package com.qa.autotest.infrastructure;

import com.qa.autotest.application.execution.ExecutionProperties;
import com.qa.autotest.application.execution.RunDispatcher;
import com.qa.autotest.application.execution.RunQueueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXEC-10 派发语义：同项目按 priority 再按提交顺序、达 MAX_PARALLEL 排队不拒绝、跨项目互不阻塞、同 runId 在途去重。
 */
class ProjectRunDispatcherTest {

    private static final long PROJECT_A = 1L;
    private static final long PROJECT_B = 2L;

    private ProjectRunDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private ProjectRunDispatcher dispatcher(int maxParallel) {
        ExecutionProperties props = new ExecutionProperties();
        props.setMaxParallel(maxParallel);
        dispatcher = new ProjectRunDispatcher(props, new RunQueueRepository() {
        });
        return dispatcher;
    }

    @Test
    @DisplayName("同一项目按 priority 降序，同优先级按提交先后")
    void 同项目按优先级再按提交顺序() throws Exception {
        ProjectRunDispatcher queue = dispatcher(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch seedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        queue.submit(PROJECT_A, null, "seed", 0, () -> {
            seedStarted.countDown();
            await(release);
            order.add("seed");
            done.countDown();
        });
        assertTrue(seedStarted.await(5, TimeUnit.SECONDS), "占位 run 应立刻占用唯一线程");

        queue.submit(PROJECT_A, null, "low", 2, () -> {
            order.add("low");
            done.countDown();
        });
        queue.submit(PROJECT_A, null, "high", 8, () -> {
            order.add("high");
            done.countDown();
        });
        queue.submit(PROJECT_A, null, "low-later", 2, () -> {
            order.add("low-later");
            done.countDown();
        });

        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "四条 run 应在超时前跑完");
        assertEquals(List.of("seed", "high", "low", "low-later"), order);
    }

    @Test
    @DisplayName("达到 MAX_PARALLEL 时排队而非拒绝")
    void 达到并发上限排队不拒绝() throws Exception {
        ProjectRunDispatcher queue = dispatcher(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            String runId = "run-" + i;
            queue.submit(PROJECT_A, null, runId, 0, () -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                started.countDown();
                await(release);
                active.decrementAndGet();
                done.countDown();
            });
        }

        assertTrue(started.await(10, TimeUnit.SECONDS), "前两条应在上限内立即开跑");
        Thread.sleep(50);
        assertEquals(2, active.get(), "第三条应仍在队列中等待，只有 MAX_PARALLEL 条在跑");
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "第三条应在释放后补位跑完");
        assertEquals(2, peak.get(), "单项目峰值并发等于 MAX_PARALLEL");
        awaitInFlightIdle(queue);
    }

    @Test
    @DisplayName("不同项目分道并行，互不阻塞")
    void 跨项目互不阻塞() throws Exception {
        ProjectRunDispatcher queue = dispatcher(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        queue.submit(PROJECT_A, null, "a", 0, () -> {
            await(bStarted);
            done.countDown();
        });
        queue.submit(PROJECT_B, null, "b", 0, () -> {
            bStarted.countDown();
            done.countDown();
        });

        assertTrue(done.await(10, TimeUnit.SECONDS),
                "项目 A 占满自己的 1 个线程时，项目 B 仍应立即开跑");
        assertEquals(2, queue.laneCount());
    }

    @Test
    @DisplayName("同一 runId 在途重复提交只执行一次")
    void 在途去重() throws Exception {
        ProjectRunDispatcher queue = dispatcher(1);
        AtomicInteger count = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        queue.submit(PROJECT_A, null, "run-dup", 0, () -> {
            count.incrementAndGet();
            started.countDown();
            await(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        queue.submit(PROJECT_A, null, "run-dup", 0, count::incrementAndGet);
        release.countDown();

        Thread.sleep(200);
        assertEquals(1, count.get(), "重复提交必须被丢弃");
        awaitInFlightIdle(queue);
    }

    @Test
    @DisplayName("位次：排队按 priority 排序给 1..N，执行中给 running，结束后查不到；queue_state 同步回写")
    void 队列位次与状态回写() throws Exception {
        ExecutionProperties props = new ExecutionProperties();
        props.setMaxParallel(1);
        FakeQueueRepository repo = new FakeQueueRepository();
        ProjectRunDispatcher queue = new ProjectRunDispatcher(props, repo);
        dispatcher = queue;

        CountDownLatch seedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        queue.submit(PROJECT_A, null, "seed", 0, () -> {
            seedStarted.countDown();
            await(release);
            done.countDown();
        });
        assertTrue(seedStarted.await(5, TimeUnit.SECONDS));

        queue.submit(PROJECT_A, null, "low", 2, done::countDown);
        queue.submit(PROJECT_A, null, "high", 9, done::countDown);
        queue.submit(PROJECT_A, null, "lowest", 1, done::countDown);

        RunDispatcher.QueueStatus high = queue.status("high").orElseThrow();
        assertEquals("queued", high.state());
        assertEquals(1, high.position(), "优先级最高的排第一");
        assertEquals(2, queue.status("low").orElseThrow().position());
        assertEquals(3, queue.status("lowest").orElseThrow().position());
        assertEquals(3, high.waiting(), "等待总数含自己");
        assertEquals(1, high.running());
        assertEquals(1, high.maxParallel());

        RunDispatcher.QueueStatus running = queue.status("seed").orElseThrow();
        assertEquals("running", running.state());
        assertEquals(0, running.position());
        assertTrue(queue.status("unknown-run").isEmpty(), "不在派发器里的 run 交给调用方按 DB 兜底");
        assertEquals("running", repo.states.get("seed"));
        assertEquals("waiting", repo.states.get("high"));

        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals("done", repo.states.get("seed"));
        assertTrue(queue.status("seed").isEmpty());
        awaitInFlightIdle(queue);
    }

    private static final class FakeQueueRepository implements RunQueueRepository {
        private final Map<String, String> states = new ConcurrentHashMap<>();

        @Override
        public void enqueue(long projectId, String runId, int priority) {
            states.put(runId, "waiting");
        }

        @Override
        public void markRunning(long projectId, String runId) {
            states.put(runId, "running");
        }

        @Override
        public void markDone(long projectId, String runId) {
            states.put(runId, "done");
        }
    }

    /**
     * 等派发器把 inFlight 回减到 0。
     *
     * `inFlightCount()` 是**工作线程跑完 runnable 之后**才减的，而用例里的 latch 在 runnable
     * 内部就 countDown。latch 一响立刻断言 0，等于和派发线程的收尾动作抢跑——单跑必过、
     * 全量跑被 CPU 挤一下就红（2026-11-14 全量门禁实际红过一次）。这里改成轮询等收敛，
     * 断言语义（跑完后队列清空）不变，只是不再要求「瞬时」。
     */
    private static void awaitInFlightIdle(ProjectRunDispatcher queue) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (queue.inFlightCount() == 0) {
                assertEquals(0, queue.inFlightCount());
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(0, queue.inFlightCount(), "任务结束后派发器应回减到 0");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
