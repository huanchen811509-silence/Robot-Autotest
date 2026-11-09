package com.qa.autotest.common.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EW32-04 · 有界池的异步执行、拒绝降级、停机排空、MDC 透传。 */
class BoundedExecutorTest {

    private AsyncProperties props(int core, int max, int queue) {
        AsyncProperties properties = new AsyncProperties();
        properties.setCoreSize(core);
        properties.setMaxSize(max);
        properties.setQueueCapacity(queue);
        properties.setShutdownTimeoutSeconds(5);
        return properties;
    }

    @Test
    @DisplayName("异步执行：任务在池线程跑完，调用线程不被阻塞")
    void runsAsync() throws Exception {
        try (BoundedExecutor executor = new BoundedExecutor(props(1, 1, 10))) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("async-");
            assertThat(executor.stats().submitted()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("队列满：拒绝后降级为调用线程同步执行，任务不丢")
    void rejectionFallsBackToCaller() throws Exception {
        try (BoundedExecutor executor = new BoundedExecutor(props(1, 1, 1))) {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.execute(() -> {
                blockerStarted.countDown();
                await(release);
            });
            assertThat(blockerStarted.await(3, TimeUnit.SECONDS)).isTrue();

            executor.execute(() -> await(release));     // 占满唯一队列位

            AtomicInteger inline = new AtomicInteger();
            String caller = Thread.currentThread().getName();
            executor.execute(() -> {
                inline.incrementAndGet();
                assertThat(Thread.currentThread().getName()).isEqualTo(caller);
            });

            assertThat(inline.get()).isEqualTo(1);
            assertThat(executor.stats().rejected()).isGreaterThanOrEqualTo(1);
            release.countDown();
        }
    }

    @Test
    @DisplayName("停机排空：close 之前提交的任务全部执行完，不丢审计")
    void shutdownDrainsQueue() {
        BoundedExecutor executor = new BoundedExecutor(props(1, 1, 20));
        AtomicInteger completed = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            executor.execute(completed::incrementAndGet);
        }
        executor.close();
        assertThat(completed.get()).isEqualTo(10);
        assertThat(executor.stats().shutdown()).isTrue();
    }

    @Test
    @DisplayName("停机后提交：不再入队，直接同步执行")
    void executesInlineAfterShutdown() {
        BoundedExecutor executor = new BoundedExecutor(props(1, 1, 10));
        executor.close();
        AtomicInteger ran = new AtomicInteger();
        executor.execute(ran::incrementAndGet);
        assertThat(ran.get()).isEqualTo(1);
        assertThat(executor.stats().rejected()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("MDC 透传：异步线程能拿到提交方的 traceId，且不污染调用线程")
    void propagatesMdc() throws Exception {
        MDC.put("traceId", "trace-abc");
        try (BoundedExecutor executor = new BoundedExecutor(props(1, 1, 2))) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> captured = new AtomicReference<>();
            executor.execute(() -> {
                captured.set(MDC.get("traceId"));
                done.countDown();
            });
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get()).isEqualTo("trace-abc");
        } finally {
            MDC.remove("traceId");
        }
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("非法配置直接拒绝：0 队列等于无界退化，必须在启动期暴露")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new BoundedExecutor(props(1, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("异步线程池参数非法");
        assertThatThrownBy(() -> new BoundedExecutor(props(2, 1, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
