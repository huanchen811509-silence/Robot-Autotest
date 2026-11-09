package com.qa.autotest.common.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EW32-03 · TtlCache 的命中、过期、容量、失效与开关语义。 */
class TtlCacheTest {

    private final AtomicLong now = new AtomicLong(1_000L);

    private TtlCache<String, String> cache(boolean enabled, int maxEntries, long ttlNanos) {
        return new TtlCache<>(maxEntries, Duration.ofNanos(ttlNanos), enabled, now::get);
    }

    @Test
    @DisplayName("命中：同一个 key 只回源一次")
    void hitLoadsOnce() {
        TtlCache<String, String> cache = cache(true, 10, 1_000);
        AtomicLong loads = new AtomicLong();
        Function<String, String> loader = key -> {
            loads.incrementAndGet();
            return "v-" + key;
        };

        assertThat(cache.get("a", loader)).isEqualTo("v-a");
        assertThat(cache.get("a", loader)).isEqualTo("v-a");
        assertThat(loads.get()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(1);
        assertThat(cache.stats().misses()).isEqualTo(1);
        assertThat(cache.stats().hitRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("过期：TTL 到点后回源，不返回陈旧值")
    void expiresAfterTtl() {
        TtlCache<String, String> cache = cache(true, 10, 1_000_000_000L);
        AtomicLong loads = new AtomicLong();
        Function<String, String> loader = key -> "v" + loads.incrementAndGet();

        assertThat(cache.get("a", loader)).isEqualTo("v1");
        now.set(999_000_001L);
        assertThat(cache.get("a", loader)).isEqualTo("v1");
        now.set(1_001_000_001L);
        assertThat(cache.get("a", loader)).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("容量：超过上限淘汰旧条目，不无限增长")
    void evictsWhenFull() {
        TtlCache<String, String> cache = cache(true, 2, 1_000);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(cache.stats().evictions()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("关闭缓存：每次都回源，行为与开缓存一致（等价性）")
    void disabledAlwaysLoads() {
        TtlCache<String, String> cache = cache(false, 10, 1_000);
        AtomicLong loads = new AtomicLong();
        Function<String, String> loader = key -> "v" + loads.incrementAndGet();

        assertThat(cache.get("a", loader)).isEqualTo("v1");
        assertThat(cache.get("a", loader)).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
        assertThat(cache.stats().enabled()).isFalse();
    }

    @Test
    @DisplayName("失效：invalidate 后第一次读立即回源")
    void invalidateRefetches() {
        TtlCache<String, String> cache = cache(true, 10, 1_000);
        AtomicLong loads = new AtomicLong();
        Function<String, String> loader = key -> "v" + loads.incrementAndGet();

        cache.get("a", loader);
        cache.invalidate("a");
        assertThat(cache.get("a", loader)).isEqualTo("v2");
        assertThat(cache.stats().invalidations()).isEqualTo(1);
    }

    @Test
    @DisplayName("按 key 条件失效：只清目标项目，其他项目保留")
    void invalidateIfIsScoped() {
        TtlCache<Long, String> cache = new TtlCache<>(10, Duration.ofMillis(1_000), true, now::get);
        cache.put(29L, "p29");
        cache.put(30L, "p30");

        cache.invalidateIf(key -> key == 29L);
        assertThat(cache.getIfPresent(29L)).isEmpty();
        assertThat(cache.getIfPresent(30L)).contains("p30");
    }

    @Test
    @DisplayName("loader 返回 null 不缓存，避免把空值固化")
    void nullValueNotCached() {
        TtlCache<String, String> cache = cache(true, 10, 1_000);
        assertThat(cache.get("missing", key -> null)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("构造参数非法直接拒绝，避免线上出现 0 容量/0 TTL 的静默缓存")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new TtlCache<>(0, Duration.ofSeconds(1), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TtlCache<>(10, Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
