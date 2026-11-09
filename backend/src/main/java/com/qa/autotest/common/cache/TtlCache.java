package com.qa.autotest.common.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 进程内 TTL 缓存（EW32-03 / D32-3：自建，不引 Caffeine 等第三方库）。
 *
 * <p>只做三件事：按 key 缓存、到点失效、容量上限。命中/未命中/淘汰计数对外暴露，
 * 供 {@code /api/admin/observability/summary} 读取。
 *
 * <p><b>不做</b>：分布式一致性、跨实例同步、异步刷新。单实例部署下这些都不需要，
 * 真要做时再换成独立缓存服务，不在本版范围。
 *
 * <p>容量淘汰是近似 FIFO（{@link ConcurrentHashMap} 弱一致迭代），不保证严格 LRU：
 * 缓存条目都是可重算的只读聚合，淘汰顺序不影业务正确性。
 */
public final class TtlCache<K, V> {

    private final int maxEntries;
    private final long ttlNanos;
    private final boolean enabled;
    private final LongSupplier clock;
    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder invalidations = new LongAdder();

    public TtlCache(int maxEntries, Duration ttl, boolean enabled) {
        this(maxEntries, ttl, enabled, System::nanoTime);
    }

    /** 测试入口：可注入时钟，避免 sleep 等真实时间。 */
    public TtlCache(int maxEntries, Duration ttl, boolean enabled, LongSupplier clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 必须 >= 1");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正数");
        }
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.enabled = enabled;
        this.clock = clock;
    }

    /** 命中返回缓存值；未命中调用加载器并回填。加载器返回 null 时不缓存。 */
    public V get(K key, Function<K, V> loader) {
        if (!enabled) {
            return loader.apply(key);
        }
        Entry<V> entry = entries.get(key);
        if (entry != null && !expired(entry)) {
            hits.increment();
            return entry.value;
        }
        if (entry != null) {
            entries.remove(key);
        }
        misses.increment();
        V value = loader.apply(key);
        if (value != null) {
            put(key, value);
        }
        return value;
    }

    public Optional<V> getIfPresent(K key) {
        if (!enabled) {
            return Optional.empty();
        }
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (expired(entry)) {
            entries.remove(key);
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(entry.value);
    }

    public void put(K key, V value) {
        if (!enabled || value == null) {
            return;
        }
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            evictOne();
        }
        entries.put(key, new Entry<>(value, clock.getAsLong()));
    }

    public void invalidate(K key) {
        if (entries.remove(key) != null) {
            invalidations.increment();
        }
    }

    /** 按 key 条件失效（例如按 projectId 前缀）。 */
    public void invalidateIf(Predicate<K> predicate) {
        entries.keySet().removeIf(key -> {
            if (predicate.test(key)) {
                invalidations.increment();
                return true;
            }
            return false;
        });
    }

    public void clear() {
        entries.clear();
        invalidations.increment();
    }

    public int size() {
        return entries.size();
    }

    public boolean enabled() {
        return enabled;
    }

    public Stats stats() {
        long hitsNow = hits.sum();
        long missesNow = misses.sum();
        long total = hitsNow + missesNow;
        double rate = total == 0 ? 0.0 : (double) hitsNow / total;
        return new Stats(hitsNow, missesNow, evictions.sum(), invalidations.sum(), entries.size(),
                maxEntries, ttlNanos / 1_000_000L, enabled, rate);
    }

    private boolean expired(Entry<V> entry) {
        return clock.getAsLong() - entry.storedAtNanos > ttlNanos;
    }

    private void evictOne() {
        var iterator = entries.keySet().iterator();
        if (iterator.hasNext()) {
            entries.remove(iterator.next());
            evictions.increment();
        }
    }

    private record Entry<V>(V value, long storedAtNanos) {
    }

    /** 对外指标快照（纯数据，便于 JSON 序列化）。 */
    public record Stats(long hits, long misses, long evictions, long invalidations,
                        int size, int maxEntries, long ttlMillis, boolean enabled, double hitRate) {
    }
}
