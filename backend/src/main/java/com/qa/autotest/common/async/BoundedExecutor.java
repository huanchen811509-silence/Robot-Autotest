package com.qa.autotest.common.async;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.MDC;

/**
 * 有界线程池（EW32-04 / D32-4）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>队列有界</b>：{@link ArrayBlockingQueue} 容量固定，不用无界 LinkedBlockingQueue，
 *       否则高峰期任务堆积到 OOM；</li>
 *   <li><b>拒绝即降级</b>：队列满时不丢任务，改为在调用线程同步执行（审计写入尤其不能丢）；</li>
 *   <li><b>停机排空</b>：{@link #close()} 先 {@code shutdown()} 再等待队列排空，超时才
 *       {@code shutdownNow()}，保证「优雅停机不丢审计」。</li>
 * </ol>
 *
 * <p>额外做 MDC 透传：提交时抓取 traceId 等上下文，执行前恢复、执行后清空，
 * 否则异步线程的日志没有 traceId，排查断链。
 */
public final class BoundedExecutor implements Executor, AutoCloseable {

    private final ThreadPoolExecutor pool;
    private final Duration shutdownTimeout;
    private final LongAdder rejected = new LongAdder();
    private final LongAdder submitted = new LongAdder();

    public BoundedExecutor(AsyncProperties properties) {
        if (properties.getCoreSize() < 1 || properties.getMaxSize() < properties.getCoreSize()
                || properties.getQueueCapacity() < 1) {
            throw new IllegalArgumentException("异步线程池参数非法：core>=1、max>=core、queue>=1");
        }
        java.util.concurrent.atomic.AtomicInteger sequence =
                new java.util.concurrent.atomic.AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                properties.getCoreSize(),
                properties.getMaxSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "async-" + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.shutdownTimeout = Duration.ofSeconds(properties.getShutdownTimeoutSeconds());
    }

    @Override
    public void execute(Runnable task) {
        submitted.increment();
        Runnable wrapped = withMdc(task);
        try {
            pool.execute(wrapped);
        } catch (RejectedExecutionException rejectedExecution) {
            // 队列满 / 已停机：降级为调用线程同步执行，宁可慢也不丢
            rejected.increment();
            wrapped.run();
        }
    }

    /** 停机排空：先拒绝新任务，再等队列跑完。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    public Stats stats() {
        return new Stats(pool.getActiveCount(), pool.getQueue().size(),
                pool.getCompletedTaskCount(), submitted.sum(), rejected.sum(),
                pool.isShutdown());
    }

    private static Runnable withMdc(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }

    /** 对外指标快照（供 observability summary 读取）。 */
    public record Stats(int active, int queued, long completed, long submitted, long rejected,
                        boolean shutdown) {
    }
}
